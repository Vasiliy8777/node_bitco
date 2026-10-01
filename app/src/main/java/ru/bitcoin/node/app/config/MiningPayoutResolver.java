package ru.bitcoin.node.app.config;

import ru.bitcoin.node.protocol.address.SegwitAddressDecoder;
import ru.bitcoin.node.protocol.network.NetworkParameters;

import java.util.HexFormat;
import java.util.Objects;

final class MiningPayoutResolver {
    private MiningPayoutResolver() {}

    static byte[] resolve(String payoutAddress, String payoutScript, NetworkParameters parameters) {
        Objects.requireNonNull(parameters, "parameters");
        if (payoutAddress != null && !payoutAddress.isBlank()) {
            return SegwitAddressDecoder.toScriptPubKey(payoutAddress, parameters);
        }
        if (payoutScript == null || payoutScript.isBlank()) {
            throw new IllegalArgumentException("Configure bitcoin.mining.payout-address or bitcoin.mining.payout-script");
        }
        try {
            byte[] script = HexFormat.of().parseHex(payoutScript.trim());
            if (script.length == 0 || script.length > 10_000) throw new IllegalArgumentException("Invalid payout script length");
            return script;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("bitcoin.mining.payout-script must be valid hexadecimal scriptPubKey", e);
        }
    }
}
