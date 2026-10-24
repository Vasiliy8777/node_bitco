package ru.bitcoin.node.rvn.chain.assets;

import java.util.*;
import java.util.function.Function;
import static ru.bitcoin.node.rvn.chain.RvnBlockBodyChecks.COIN;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetScriptCodec.*;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetMetadataRules.*;
import ru.bitcoin.node.rvn.chain.assets.RvnAssetIssueChecks.Output;

/** Pre-bit-10 CheckTransaction / AddCoins path. ConnectBlock deliberately skips
 * CheckTxAssets for coinbase: no conservation, existence or transfer-context checks.
 * Stateless null-data rules and their persistent changes are checked separately
 * by RvnRestrictedBlockState, without contextual qualifier/freeze checks. */
final class RvnHistoricalAssetCoinbaseChecks {
    private RvnHistoricalAssetCoinbaseChecks() {}
    static Map<String, Metadata> verify(List<Output> outputs, List<Payload> payloads,
            Function<String, Metadata> state, Function<byte[], Optional<Decoded>> decoder) {
        for (var payload : payloads) if (payload instanceof Transfer transfer) {
            var type = RvnAssetNames.requireValid(transfer.name());
            if ((type == RvnAssetNames.Type.OWNER || type == RvnAssetNames.Type.UNIQUE) && transfer.amount() != COIN)
                throw new SecurityException("RVN coinbase owner/unique transfer amount");
            if ((type == RvnAssetNames.Type.QUALIFIER || type == RvnAssetNames.Type.SUB_QUALIFIER)
                    && (transfer.amount() < COIN || transfer.amount() > 10 * COIN))
                throw new SecurityException("RVN coinbase qualifier transfer amount");
        }
        // These activation checks are contextual in Core and are skipped for
        // coinbase. The decoder still uses the real historical branch offsets.
        var stateless = new Activation(true, true, true, false);
        var updates = new HashMap<String, Metadata>();
        if (payloads.stream().anyMatch(p -> p instanceof Issue)) {
            var last = payloads.getLast();
            if (!(last instanceof Issue)) throw new SecurityException("RVN coinbase issuance layout");
            var type = RvnAssetNames.requireValid(last.name());
            switch (type) {
                case UNIQUE, CHANNEL -> updates.putAll(RvnTaggedAssetIssueChecks.verifyMainnet(
                        List.of(), outputs, stateless, state, decoder, false));
                case QUALIFIER, SUB_QUALIFIER -> {
                    var value = RvnQualifierIssueChecks.verifyMainnet(List.of(), outputs, stateless, state, decoder, false);
                    updates.put(value.name(), value);
                }
                case ROOT, SUB -> {
                    var value = RvnAssetIssueChecks.verifyMainnet(List.of(), outputs, stateless, state, decoder, false);
                    updates.put(value.name(), value);
                }
                case RESTRICTED -> {
                    var value = RvnRestrictedIssueChecks.verifyMainnet(List.of(), outputs, stateless, state, decoder, false);
                    updates.put(value.name(), value);
                }
                default -> throw new UnsupportedOperationException("Unsupported historical coinbase issuance family");
            }
            // AddNewAsset refuses replacement but its false return is only logged.
            updates.keySet().removeIf(name -> state.apply(name) != null);
        } else if (payloads.stream().anyMatch(p -> p instanceof Reissue)) {
            var value = RvnAssetReissueChecks.verifyMainnet(List.of(), outputs, stateless, state, decoder, false);
            if (value != null) updates.put(value.name(), value);
        } else if (payloads.stream().anyMatch(p -> !(p instanceof Transfer))) {
            throw new SecurityException("RVN malformed asset coinbase transaction");
        }
        return Map.copyOf(updates);
    }
}
