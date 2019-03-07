package com.samourai.whirlpool.cli.wallet;

import com.samourai.wallet.client.Bip84ApiWallet;
import com.samourai.whirlpool.cli.config.CliConfig;
import com.samourai.whirlpool.cli.run.CliStatusOrchestrator;
import com.samourai.whirlpool.cli.services.CliWalletService;
import com.samourai.whirlpool.cli.services.WalletAggregateService;
import com.samourai.whirlpool.cli.utils.CliUtils;
import com.samourai.whirlpool.client.exception.EmptyWalletException;
import com.samourai.whirlpool.client.exception.NotifiableException;
import com.samourai.whirlpool.client.wallet.WhirlpoolWallet;
import com.samourai.whirlpool.client.wallet.beans.WhirlpoolAccount;
import com.samourai.whirlpool.client.wallet.beans.WhirlpoolUtxo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CliWallet extends WhirlpoolWallet {
  private static final Logger log = LoggerFactory.getLogger(CliWallet.class);
  private static final int CLI_STATUS_DELAY = 10000;

  private CliConfig cliConfig;
  private WalletAggregateService walletAggregateService;
  private CliStatusOrchestrator cliStatusOrchestrator;

  public CliWallet(
      WhirlpoolWallet whirlpoolWallet,
      CliConfig cliConfig,
      WalletAggregateService walletAggregateService,
      CliWalletService cliWalletService) {
    super(whirlpoolWallet);
    this.cliConfig = cliConfig;
    this.walletAggregateService = walletAggregateService;

    // log status
    this.cliStatusOrchestrator =
        new CliStatusOrchestrator(CLI_STATUS_DELAY, cliWalletService, cliConfig);
  }

  @Override
  public void start() {
    super.start();
    this.cliStatusOrchestrator.start();
  }

  @Override
  public void stop() {
    super.stop();
    this.cliStatusOrchestrator.stop();
  }

  @Override
  public void onEmptyWalletException(EmptyWalletException e) {
    try {
      autoRefill(e);
    } catch (Exception ee) {
      log.error("", ee);

      // default log
      super.onEmptyWalletException(e);
    }
  }

  private void autoRefill(EmptyWalletException e) throws Exception {
    long requiredBalance = e.getBalanceRequired();
    Bip84ApiWallet depositWallet = getWalletDeposit();
    Bip84ApiWallet premixWallet = getWalletPremix();
    Bip84ApiWallet postmixWallet = getWalletPostmix();

    // check total balance
    long depositBalance = depositWallet.fetchBalance();
    long premixBalance = premixWallet.fetchBalance();
    long postmixBalance = postmixWallet.fetchBalance();
    long totalBalance = depositBalance + premixBalance + postmixBalance;
    if (log.isDebugEnabled()) {
      log.debug("depositBalance=" + depositBalance);
      log.debug("premixBalance=" + premixBalance);
      log.debug("postmixBalance=" + postmixBalance);
      log.debug("totalBalance=" + totalBalance);
    }

    long missingBalance = totalBalance - requiredBalance;
    if (log.isDebugEnabled()) {
      log.debug("requiredBalance=" + requiredBalance + " => missingBalance=" + missingBalance);
    }
    if (totalBalance < requiredBalance) {
      throw new EmptyWalletException("Insufficient balance to continue", missingBalance);
    }

    String depositAddress = getDepositAddress(false);
    String message = e.getMessageDeposit(depositAddress);
    if (!cliConfig.getMix().isAutoAggregatePostmix()) {
      CliUtils.waitUserAction(message);
      return;
    }

    // stop wallet for auto-aggregate
    boolean wasStarted = isStarted();
    if (wasStarted) {
      if (log.isDebugEnabled()) {
        log.debug("Stopping wallet for auto-aggregate-postmix.");
      }
      stop();
    }

    // auto aggregate postmix
    log.info(" o AutoAggregatePostmix: depositWallet wallet is empty => aggregating");
    Exception aggregateException = null;
    try {
      walletAggregateService.consolidateTestnet(this);
    } catch(Exception ee) {
      // resume wallet before throwing exception (to retry later)
      aggregateException = ee;
    }

    clearCache();

    // resume wallet
    if (wasStarted) {
      if (log.isDebugEnabled()) {
        log.debug("Restarting wallet after auto-aggregate-postmix.");
      }
      start();
    }

    if (aggregateException != null) {
      throw aggregateException;
    }
  }

  @Override
  public void mixQueue(WhirlpoolUtxo whirlpoolUtxo) throws NotifiableException {
    if (WhirlpoolAccount.POSTMIX.equals(whirlpoolUtxo.getAccount())
        && cliConfig.getMix().isDisablePostmix()) {
      whirlpoolUtxo.setMessage("Not queued: postmix mixing is disabled by configuration");
      if (log.isDebugEnabled()) {
        log.debug("Not queued: postmix mixing is disabled by configuration: " + whirlpoolUtxo);
      }
      return;
    }
    super.mixQueue(whirlpoolUtxo);
  }

  @Override
  public Bip84ApiWallet getWalletDeposit() {
    return super.getWalletDeposit();
  }

  @Override
  public Bip84ApiWallet getWalletPremix() {
    return super.getWalletPremix();
  }

  @Override
  public Bip84ApiWallet getWalletPostmix() {
    return super.getWalletPostmix();
  }
}
