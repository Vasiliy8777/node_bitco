package ru.bitcoin.node.rvn.chain.assets;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.rvn.chain.RvnBlockBodyChecks.COIN;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetScriptCodec.*;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetMetadataRules.*;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetIssueChecks.*;
import ru.bitcoin.node.rvn.chain.RvnPayoutAddress;
import ru.bitcoin.node.rvn.protocol.RvnNetwork;

class RvnAssetIssueChecksTest {
    private static final Activation ACTIVE = new Activation(true, true, true);
    private static byte[] script(String address) { return RvnPayoutAddress.parse(address, RvnNetwork.MAINNET).scriptPubKey(); }
    private static final byte[] LOCK = script("RXissueAssetXXXXXXXXXXXXXXXXXhhZGt");
    private static Output asset(Payload payload) { return new Output(0, encodeCanonical(LOCK, payload)); }
    private static List<Output> issueOutputs(String name) {
        return new ArrayList<>(List.of(new Output((name.contains("/") ? 100 : 500) * COIN,
                script(name.contains("/") ? "RXissueSubAssetXXXXXXXXXXXXXWcwhwL" : "RXissueAssetXXXXXXXXXXXXXXXXXhhZGt")),
                asset(new Owner(name + "!")), asset(new Issue(name, 10 * COIN, 0, true, null))));
    }
    @Test void rootIssueChecksExactBurnPositionAndExistingName() {
        var outputs = issueOutputs("TOKEN");
        assertEquals(10 * COIN, verifyMainnet(List.of(), outputs, ACTIVE, n -> null).supply());
        outputs.set(0, new Output(501 * COIN, LOCK));
        assertThrows(SecurityException.class, () -> verifyMainnet(List.of(), outputs, ACTIVE, n -> null));
        var valid = issueOutputs("TOKEN");
        assertThrows(SecurityException.class, () -> verifyMainnet(List.of(), valid, ACTIVE,
                n -> new Metadata(n, COIN, 0, true, null)));
        Collections.swap(valid, 1, 2);
        assertThrows(SecurityException.class, () -> verifyMainnet(List.of(), valid, ACTIVE, n -> null));
    }
    @Test void subIssueNeedsSpentAndReturnedImmediateParentOwner() {
        var outputs = issueOutputs("TOKEN/SUB");
        var owner = new Owner("TOKEN!");
        java.util.function.Function<String, Metadata> state = n -> n.equals("TOKEN") ? new Metadata(n, COIN, 0, true, null) : null;
        assertThrows(SecurityException.class, () -> verifyMainnet(List.of(owner), outputs, ACTIVE, state));
        outputs.add(1, asset(new Transfer("TOKEN!", COIN, null, 0)));
        assertEquals("TOKEN/SUB", verifyMainnet(List.of(owner), outputs, ACTIVE, state).name());
        assertThrows(SecurityException.class, () -> verifyMainnet(List.of(), outputs, ACTIVE, state));
        outputs.add(1, asset(new Owner("OTHER!")));
        assertThrows(SecurityException.class, () -> verifyMainnet(List.of(owner), outputs, ACTIVE, state));
    }
}
