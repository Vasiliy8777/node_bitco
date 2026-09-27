package ru.bitcoin.node.crypto.hash;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MuHash3072StateTest {
    @Test
    void serializedStateRestoresAccumulatorExactly() {
        var original = new MuHash3072().insert(new byte[]{1, 2, 3}).insert(new byte[]{4, 5}).remove(new byte[]{9});
        byte[] state = original.serializeState();
        assertEquals(MuHash3072.SERIALIZED_STATE_SIZE, state.length);
        var restored = MuHash3072.fromSerializedState(state);
        assertEquals(original.finalizeHash(), restored.finalizeHash());
        original.insert(new byte[]{7});
        restored.insert(new byte[]{7});
        assertEquals(original.finalizeHash(), restored.finalizeHash());
    }

    @Test
    void rejectsMalformedState() {
        assertThrows(IllegalArgumentException.class, () -> MuHash3072.fromSerializedState(new byte[10]));
        assertThrows(IllegalArgumentException.class,
                () -> MuHash3072.fromSerializedState(new byte[MuHash3072.SERIALIZED_STATE_SIZE]));
    }
}
