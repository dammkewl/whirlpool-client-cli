package com.samourai.whirlpool.client.run;

import com.samourai.api.SamouraiApi;
import com.samourai.api.beans.UnspentResponse;
import com.samourai.rpc.client.RpcClientService;
import com.samourai.wallet.hd.HD_Address;
import com.samourai.wallet.util.FormatsUtilGeneric;
import com.samourai.whirlpool.client.CliUtils;
import com.samourai.whirlpool.client.exception.NotifiableException;
import com.samourai.whirlpool.client.tx0.TxAggregateService;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RunAggregatePostmix {
  private Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private static final int AGGREGATED_UTXOS_PER_TX = 500;

  private NetworkParameters params;
  private SamouraiApi samouraiApi;
  private Optional<RpcClientService> rpcClientService;

  public RunAggregatePostmix(
      NetworkParameters params,
      SamouraiApi samouraiApi,
      Optional<RpcClientService> rpcClientService) {
    this.params = params;
    this.samouraiApi = samouraiApi;
    this.rpcClientService = rpcClientService;
  }

  public void run(VpubWallet vpubWallet) throws Exception {
    if (!FormatsUtilGeneric.getInstance().isTestNet(params)) {
      throw new Exception("AggregatePostmix cannot be run on mainnet for privacy reasons.");
    }

    List<UnspentResponse.UnspentOutput> utxos = vpubWallet.fetchUtxos(RunVPubLoop.ACCOUNT_POSTMIX);
    if (utxos.isEmpty()) {
      // maybe you need to declare vpub as bip84 with /multiaddr?bip84=
      throw new NotifiableException("AggregatePostmix failed: no utxo found from postmix");
    }
    log.info("Found " + utxos.size() + " utxo from postmix:");
    CliUtils.printUtxos(utxos);

    int round = 0;
    int offset = 0;
    while (offset < utxos.size()) {
      List<UnspentResponse.UnspentOutput> subsetUtxos = new ArrayList<>();
      offset = AGGREGATED_UTXOS_PER_TX * round;
      for (int i = offset; i < (offset + AGGREGATED_UTXOS_PER_TX) && i < utxos.size(); i++) {
        subsetUtxos.add(utxos.get(i));
      }
      log.info("Aggregating " + subsetUtxos.size() + " utxos from postmix (pass #" + round + ")");
      runAggregate(vpubWallet, subsetUtxos);
      round++;
    }
  }

  private void runAggregate(VpubWallet vpubWallet, List<UnspentResponse.UnspentOutput> postmixUtxos)
      throws Exception {
    List<TransactionOutPoint> spendFromOutPoints = new ArrayList<>();
    List<HD_Address> spendFromAddresses = new ArrayList<>();

    postmixUtxos.forEach(
        utxo -> {
          spendFromOutPoints.add(utxo.computeOutpoint(params));
          spendFromAddresses.add(vpubWallet.getAddressPostmix(utxo.computePathAddressIndex()));
        }
    );
    int addressIndex = vpubWallet.fetchAddress(RunVPubLoop.ACCOUNT_DEPOSIT_AND_PREMIX).change_index;
    HD_Address toAddress = vpubWallet.getAddressDepositAndPremix(addressIndex);
    int feeSatPerByte = samouraiApi.fetchFees();

    // tx
    Transaction txAggregate =
        new TxAggregateService(params)
            .txAggregate(spendFromOutPoints, spendFromAddresses, toAddress, feeSatPerByte);

    log.info("txAggregate:");
    log.info(txAggregate.toString());

    // broadcast
    log.info(" • Broadcasting TxAggregate...");
    CliUtils.broadcastOrNotify(rpcClientService, txAggregate);
  }
}