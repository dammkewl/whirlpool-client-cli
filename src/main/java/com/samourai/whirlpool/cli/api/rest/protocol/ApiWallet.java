package com.samourai.whirlpool.cli.api.rest.protocol;

import com.samourai.whirlpool.client.wallet.beans.WhirlpoolUtxo;
import java.util.Collection;
import java.util.stream.Collectors;

public class ApiWallet {
  private Collection<ApiUtxo> utxos;
  private long balance;

  public ApiWallet(Collection<WhirlpoolUtxo> whirlpoolUtxos) {
    this.utxos =
        whirlpoolUtxos
            .stream()
            .map(whirlpoolUtxo -> new ApiUtxo(whirlpoolUtxo))
            .collect(Collectors.toList());
    this.balance =
        whirlpoolUtxos.stream().mapToLong(whirlpoolUtxo -> whirlpoolUtxo.getUtxo().value).sum();
  }

  public Collection<ApiUtxo> getUtxos() {
    return utxos;
  }

  public long getBalance() {
    return balance;
  }
}