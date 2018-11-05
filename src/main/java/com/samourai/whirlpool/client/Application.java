package com.samourai.whirlpool.client;

import com.samourai.api.SamouraiApi;
import com.samourai.http.client.IHttpClient;
import com.samourai.http.client.JavaHttpClient;
import com.samourai.rpc.client.JSONRpcClientServiceImpl;
import com.samourai.rpc.client.RpcClientService;
import com.samourai.stomp.client.IStompClient;
import com.samourai.stomp.client.JavaStompClient;
import com.samourai.wallet.hd.HD_Wallet;
import com.samourai.wallet.util.FormatsUtilGeneric;
import com.samourai.whirlpool.client.exception.NotifiableException;
import com.samourai.whirlpool.client.run.*;
import com.samourai.whirlpool.client.utils.Bip84ApiWallet;
import com.samourai.whirlpool.client.utils.CliUtils;
import com.samourai.whirlpool.client.utils.HdWalletFactory;
import com.samourai.whirlpool.client.utils.LogbackUtils;
import com.samourai.whirlpool.client.whirlpool.WhirlpoolClientConfig;
import com.samourai.whirlpool.client.whirlpool.WhirlpoolClientImpl;
import com.samourai.whirlpool.client.whirlpool.beans.Pool;
import com.samourai.whirlpool.client.whirlpool.beans.Pools;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.Optional;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.NetworkParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/** Command-line client. */
@EnableAutoConfiguration
public class Application implements ApplicationRunner {
  private Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static final int ACCOUNT_DEPOSIT_AND_PREMIX = 0;
  private static final int ACCOUNT_POSTMIX = Integer.MAX_VALUE - 1;
  private static final int RUNVPUB_SLEEP_ON_ERROR = 30000;

  private ApplicationArgs appArgs;
  private HdWalletFactory hdWalletFactory;

  public static void main(String... args) {
    SpringApplication.run(Application.class, args);
  }

  private IHttpClient httpClient = new JavaHttpClient();

  @Override
  public void run(ApplicationArguments args) {
    this.appArgs = new ApplicationArgs(args);

    // enable debug logs with --debug
    if (appArgs.isDebug()) {
      LogbackUtils.setLogLevel("com.samourai", Level.DEBUG.toString());
    }

    log.info("------------ whirlpool-client ------------");
    log.info("Running whirlpool-client {}", Arrays.toString(args.getSourceArgs()));
    try {
      NetworkParameters params = appArgs.getNetworkParameters();
      new Context(params); // initialize bitcoinj context
      hdWalletFactory = new HdWalletFactory(params, CliUtils.computeMnemonicCode());

      // instanciate client
      String server = appArgs.getServer();
      WhirlpoolClientConfig config = computeWhirlpoolClientConfig(server, params);
      WhirlpoolClient whirlpoolClient = WhirlpoolClientImpl.newClient(config);

      // fetch pools
      try {
        log.info(" • Retrieving pools...");
        Pools pools = whirlpoolClient.fetchPools();

        String poolId = appArgs.getPoolId();
        if (poolId != null) {
          // if --pool is provided, find pool
          Pool pool = pools.findPoolById(poolId);
          if (pool != null) {
            // pool found
            String seedWords = appArgs.getSeedWords();
            String seedPassphrase = appArgs.getSeedPassphrase();
            int paynymIndex = appArgs.getPaynymIndex();

            if (appArgs.isUtxo()) {
              // go whirlpool with UTXO
              String utxoHash = appArgs.getUtxoHash();
              long utxoIdx = appArgs.getUtxoIdx();
              String utxoKey = appArgs.getUtxoKey();
              long utxoBalance = appArgs.getUtxoBalance();
              final int mixs = appArgs.getMixs();

              new RunMixUtxo()
                  .run(
                      whirlpoolClient,
                      pool,
                      params,
                      utxoHash,
                      utxoIdx,
                      utxoKey,
                      utxoBalance,
                      seedWords,
                      seedPassphrase,
                      paynymIndex,
                      mixs);
            } else {
              Optional<RpcClientService> rpcClientService = computeRpcClientService(appArgs);
              SamouraiApi samouraiApi = new SamouraiApi(config.getHttpClient());
              HD_Wallet bip84w =
                  CliUtils.computeBip84Wallet(
                      appArgs.getSeedPassphrase(), appArgs.getSeedWords(), params, hdWalletFactory);
              Bip84ApiWallet depositAndPremixWallet =
                  new Bip84ApiWallet(bip84w, ACCOUNT_DEPOSIT_AND_PREMIX, samouraiApi);
              Bip84ApiWallet postmixWallet =
                  new Bip84ApiWallet(bip84w, ACCOUNT_POSTMIX, samouraiApi);
              RunTx0 runTx0 =
                  new RunTx0(params, samouraiApi, rpcClientService, depositAndPremixWallet);
              Optional<Integer> tx0Arg = appArgs.getTx0();
              if (tx0Arg.isPresent()) {
                // go tx0
                runTx0.runTx0(pool, tx0Arg.get());
              } else if (appArgs.isAggregatePostmix()) {
                if (!FormatsUtilGeneric.getInstance().isTestNet(params)) {
                  throw new NotifiableException(
                      "AggregatePostmix cannot be run on mainnet for privacy reasons.");
                }

                // go aggregate postmix to premix
                log.info(" • Aggregating postmix wallet to premix");
                new RunAggregateWallet(
                        params,
                        samouraiApi,
                        rpcClientService,
                        postmixWallet,
                        depositAndPremixWallet)
                    .run();

                // consolidate premix
                log.info(" • Consolidating premix wallet");
                new RunAggregateWallet(
                        params,
                        samouraiApi,
                        rpcClientService,
                        depositAndPremixWallet,
                        depositAndPremixWallet)
                    .run();
              } else {
                // go loop wallet
                while (true) {
                  try {
                    RunMixWallet runMixWallet =
                        new RunMixWallet(config, depositAndPremixWallet, postmixWallet);
                    new RunLoopWallet(runTx0, runMixWallet, depositAndPremixWallet).run(pool);
                  } catch (Exception e) {
                    log.error(
                        "RunMixWallet failed, retrying in " + RUNVPUB_SLEEP_ON_ERROR + "ms", e);
                    synchronized (this) {
                      Thread.sleep(RUNVPUB_SLEEP_ON_ERROR);
                    }
                  }
                }
              }
            }
          } else {
            log.error("Pool not found: " + poolId);
          }
        } else {
          // show pools list if --pool is not provided/found
          new RunListPools().run(pools);
          log.info("Tip: use --pool argument to select a pool");
        }
      } catch (NotifiableException e) {
        log.error("==> " + e.getMessage());
      } catch (Exception e) {
        log.error("", e);
      }
    } catch (IllegalArgumentException e) {
      log.info("Invalid arguments: " + e.getMessage());
      log.info("Usage: whirlpool-client " + ApplicationArgs.USAGE);
    }
  }

  private WhirlpoolClientConfig computeWhirlpoolClientConfig(
      String server, NetworkParameters params) {
    IStompClient stompClient = new JavaStompClient();
    WhirlpoolClientConfig config =
        new WhirlpoolClientConfig(httpClient, stompClient, server, params);
    if (appArgs.isTestMode()) {
      config.setTestMode(true);
      if (log.isDebugEnabled()) {
        log.debug("--test-mode: tx0 verifications will be skiped (if server allows it)");
      }
    }
    return config;
  }

  private Optional<RpcClientService> computeRpcClientService(ApplicationArgs appArgs)
      throws Exception {
    String rpcClientUrl = appArgs.getRpcClientUrl();
    if (rpcClientUrl == null) {
      return Optional.empty();
    }
    NetworkParameters params = appArgs.getNetworkParameters();
    boolean isTestnet = FormatsUtilGeneric.getInstance().isTestNet(params);
    RpcClientService rpcClientService = new JSONRpcClientServiceImpl(rpcClientUrl, isTestnet);
    if (!rpcClientService.testConnectivity()) {
      throw new NotifiableException("Unable to connect to rpc-client-url");
    }
    return Optional.of(rpcClientService);
  }
}
