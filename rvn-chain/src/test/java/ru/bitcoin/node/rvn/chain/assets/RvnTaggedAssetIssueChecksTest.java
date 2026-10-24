package ru.bitcoin.node.rvn.chain.assets;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Function;
import ru.bitcoin.node.rvn.chain.RvnPayoutAddress;
import ru.bitcoin.node.rvn.protocol.RvnNetwork;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.rvn.chain.RvnBlockBodyChecks.COIN;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetScriptCodec.*;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetMetadataRules.*;
import static ru.bitcoin.node.rvn.chain.assets.RvnTaggedAssetIssueChecks.verifyMainnet;
import ru.bitcoin.node.rvn.chain.assets.RvnAssetIssueChecks.Output;

class RvnTaggedAssetIssueChecksTest {
    private static final Activation ACTIVE = new Activation(true, true, true);
    private static final byte[] LOCK = script("RXissueAssetXXXXXXXXXXXXXXXXXhhZGt");
    private static final Function<String, Metadata> STATE = n -> n.equals("TOKEN") || n.equals("TOKEN/SUB")
            ? new Metadata(n, COIN, 0, true, null) : null;
    private static byte[] script(String address) { return RvnPayoutAddress.parse(address, RvnNetwork.MAINNET).scriptPubKey(); }
    private static Output asset(Payload p) { return new Output(0, encodeCanonical(LOCK, p)); }
    private static Issue issue(String name) { return new Issue(name, COIN, 0, false, null); }
    private static List<Output> outputs(String parent, String... tags) {
        var outputs = new ArrayList<Output>();
        boolean unique = tags[0].startsWith("#");
        outputs.add(new Output((unique ? 5L * tags.length : 100L) * COIN,
                script(unique ? "RXissueUniqueAssetXXXXXXXXXXWEAe58" : "RXissueMsgChanneLAssetXXXXXXSjHvAY")));
        outputs.add(asset(new Transfer(parent + "!", COIN, null, 0)));
        for (String tag : tags) outputs.add(asset(issue(parent + tag)));
        return outputs;
    }

    @Test void uniqueBatchAndSubassetParentAreAuthorizedWithoutCreatingOwnerTokens() {
        var result = verifyMainnet(List.of(new Owner("TOKEN!")), outputs("TOKEN", "#One", "#Two"), ACTIVE, STATE);
        assertEquals(Set.of("TOKEN#One", "TOKEN#Two"), result.keySet());
        assertEquals(COIN, result.get("TOKEN#One").supply()); assertFalse(result.get("TOKEN#One").reissuable());
        assertEquals(Set.of("TOKEN/SUB#One"), verifyMainnet(List.of(new Owner("TOKEN/SUB!")), outputs("TOKEN/SUB", "#One"), ACTIVE, STATE).keySet());
    }

    @Test void duplicateMixedParentExtraMintAndWrongBurnAreRejected() {
        var spent = List.of(new Owner("TOKEN!"));
        assertThrows(SecurityException.class, () -> verifyMainnet(spent, outputs("TOKEN", "#One", "#One"), ACTIVE, STATE));
        var mixed = outputs("TOKEN", "#One", "#Two"); mixed.set(2, asset(issue("OTHER#One")));
        assertThrows(SecurityException.class, () -> verifyMainnet(spent, mixed, ACTIVE, STATE));
        var wrongBurn = outputs("TOKEN", "#One", "#Two");
        wrongBurn.set(0, new Output(5 * COIN, script("RXissueUniqueAssetXXXXXXXXXXWEAe58")));
        assertThrows(SecurityException.class, () -> verifyMainnet(spent, wrongBurn, ACTIVE, STATE));
        var extra = outputs("TOKEN", "#One"); extra.add(1, asset(new Owner("TOKEN#One!")));
        assertThrows(SecurityException.class, () -> verifyMainnet(spent, extra, ACTIVE, STATE));
        assertThrows(SecurityException.class, () -> verifyMainnet(List.of(), outputs("TOKEN", "#One"), ACTIVE, STATE));
        assertThrows(SecurityException.class, () -> verifyMainnet(spent, outputs("TOKEN", "#One"), ACTIVE,
                n -> n.equals("TOKEN#One") ? new Metadata(n, COIN, 0, false, null) : STATE.apply(n)));
    }

    @Test void channelNeedsActivationParentReturnAndSingleIssuance() {
        var spent = List.of(new Owner("TOKEN!"));
        assertEquals(Set.of("TOKEN~NEWS"), verifyMainnet(spent, outputs("TOKEN", "~NEWS"), ACTIVE, STATE).keySet());
        assertThrows(SecurityException.class, () -> verifyMainnet(spent, outputs("TOKEN", "~NEWS"), new Activation(true, false, false), STATE));
        assertThrows(SecurityException.class, () -> verifyMainnet(spent, outputs("TOKEN", "~NEWS", "~OTHER"), ACTIVE, STATE));
        var badParent = outputs("TOKEN", "~NEWS"); badParent.set(1, asset(new Transfer("TOKEN/SUB!", COIN, null, 0)));
        assertThrows(SecurityException.class, () -> verifyMainnet(spent, badParent, ACTIVE, STATE));
    }

    @Test void uniqueAndChannelTransfersEnforceOneTokenAndBranchActivation() {
        for (String name : List.of("TOKEN#One", "TOKEN~NEWS")) {
            Function<String, Metadata> state = n -> new Metadata(n, COIN, 0, false, null);
            var valid = List.of(new Transfer(name, COIN, null, 0));
            assertEquals(Map.of(name, COIN), RvnAssetTransferChecks.verify(valid, valid, state, ACTIVE));
            var invalid = List.of(new Transfer(name, 2 * COIN, null, 0));
            assertThrows(SecurityException.class, () -> RvnAssetTransferChecks.verify(invalid, invalid, state, ACTIVE));
            if (name.contains("~")) assertThrows(SecurityException.class, () -> RvnAssetTransferChecks.verify(valid, valid, state,
                    new Activation(true, false, false)));
        }
    }
}
