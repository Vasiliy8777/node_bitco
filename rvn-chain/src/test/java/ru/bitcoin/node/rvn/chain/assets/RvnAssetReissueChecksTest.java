package ru.bitcoin.node.rvn.chain.assets;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.rvn.chain.RvnBlockBodyChecks.COIN;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetScriptCodec.*;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetMetadataRules.*;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetReissueChecks.verifyMainnet;
import ru.bitcoin.node.rvn.chain.assets.RvnAssetIssueChecks.Output;
import ru.bitcoin.node.rvn.chain.RvnPayoutAddress;
import ru.bitcoin.node.rvn.protocol.RvnNetwork;

class RvnAssetReissueChecksTest {
    private static final Activation ACTIVE = new Activation(true, true, true);
    private static final Metadata ORIGINAL = new Metadata("TOKEN", 10 * COIN, 0, true, null);
    private static final byte[] LOCK = RvnPayoutAddress.parse("RXReissueAssetXXXXXXXXXXXXXXVEFAWu", RvnNetwork.MAINNET).scriptPubKey();
    private static Output asset(Payload p) { return new Output(0, encodeCanonical(LOCK, p)); }
    private static List<Output> outputs(long amount) {
        return new ArrayList<>(List.of(new Output(100 * COIN, LOCK),
                asset(new Transfer("TOKEN!", COIN, null, 0)), asset(new Reissue("TOKEN", amount, 8, false, null))));
    }
    @Test void authorizedReissueMintsOnlyDeclaredAmountAndReturnsOwner() {
        var result = verifyMainnet(List.of(new Owner("TOKEN!")), outputs(COIN), ACTIVE, n -> ORIGINAL);
        assertEquals(11 * COIN, result.supply()); assertEquals(8, result.units()); assertFalse(result.reissuable());
        assertEquals(10 * COIN, ORIGINAL.supply()); assertTrue(ORIGINAL.reissuable());
        assertEquals(10 * COIN, verifyMainnet(List.of(new Owner("TOKEN!")), outputs(0), ACTIVE, n -> ORIGINAL).supply());
    }
    @Test void burnOwnerAndMetadataCannotBeBypassed() {
        var spent = List.of(new Owner("TOKEN!"));
        var wrongBurn = outputs(COIN); wrongBurn.set(0, new Output(101 * COIN, LOCK));
        assertThrows(SecurityException.class, () -> verifyMainnet(spent, wrongBurn, ACTIVE, n -> ORIGINAL));
        assertThrows(SecurityException.class, () -> verifyMainnet(List.of(), outputs(COIN), ACTIVE, n -> ORIGINAL));
        var missingOwner = outputs(COIN); missingOwner.set(1, new Output(0, LOCK));
        assertThrows(SecurityException.class, () -> verifyMainnet(spent, missingOwner, ACTIVE, n -> ORIGINAL));
        assertThrows(SecurityException.class, () -> verifyMainnet(spent, outputs(COIN), ACTIVE, n -> null));
        assertThrows(SecurityException.class, () -> verifyMainnet(spent, outputs(1), ACTIVE, n -> ORIGINAL));
        var extra = outputs(COIN); extra.add(1, asset(new Transfer("TOKEN", COIN, null, 0)));
        assertThrows(SecurityException.class, () -> verifyMainnet(spent, extra, ACTIVE, n -> ORIGINAL));
    }
}
