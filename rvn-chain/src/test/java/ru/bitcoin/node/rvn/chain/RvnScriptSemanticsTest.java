package ru.bitcoin.node.rvn.chain;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.rvn.chain.assets.RvnAssetScriptCodec;
import static org.junit.jupiter.api.Assertions.*;

class RvnScriptSemanticsTest {
    @Test void assetOpcodeConsumesRemainderForSigopsButPushedBytesDoNot() {
        assertEquals(1, RvnScriptSemantics.sigops(new byte[]{(byte)0xac,(byte)0xc0,(byte)0xae,(byte)0xac}, false));
        assertEquals(1, RvnScriptSemantics.sigops(new byte[]{1,(byte)0xc0,(byte)0xac}, false));
        assertEquals(2, RvnScriptSemantics.sigops(new byte[]{0x52,(byte)0xae,(byte)0xc0,(byte)0xac}, true));
        assertEquals(0, RvnScriptSemantics.sigops(new byte[]{0x4c,20,(byte)0xac}, false));
    }
    @Test void nullAndZeroAmountAssetOutputsArePrunedWithoutDeploymentGuessing() {
        byte[] lock = new byte[25]; lock[0] = 0x76; lock[1] = (byte)0xa9; lock[2] = 20; lock[23] = (byte)0x88; lock[24] = (byte)0xac;
        assertTrue(RvnScriptSemantics.unspendable(new byte[]{(byte)0xc0,0x50,1}));
        assertTrue(RvnScriptSemantics.unspendable(RvnAssetScriptCodec.encodeCanonical(lock,
                new RvnAssetScriptCodec.Reissue("TOKEN", 0, -1, true, null))));
        assertFalse(RvnScriptSemantics.unspendable(RvnAssetScriptCodec.encodeCanonical(lock,
                new RvnAssetScriptCodec.Transfer("TOKEN", 100000000, null, 0))));
        assertFalse(RvnScriptSemantics.unspendable(new byte[]{1,(byte)0xc0}));
    }
}
