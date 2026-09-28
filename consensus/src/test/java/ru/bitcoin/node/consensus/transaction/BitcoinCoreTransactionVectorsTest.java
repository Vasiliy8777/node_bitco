package ru.bitcoin.node.consensus.transaction;

import org.junit.jupiter.api.*;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.protocol.serialization.TransactionParser;
import ru.bitcoin.node.script.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bitcoin Core tx_valid/tx_invalid conformance adapter.
 *
 * Besides the vector's primary flag set this mirrors Core's transaction_tests.cpp
 * compatibility invariants: a valid transaction must stay valid when verification
 * flags are removed, an invalid transaction must stay invalid when flags are added,
 * and the vector-declared excluded/required flags must be maximal/minimal.
 */
class BitcoinCoreTransactionVectorsTest {
    private static final int RANDOM_FLAG_COMBINATIONS = 16;
    private static final List<Integer> FLAGS = declaredFlags();
    private static final int ALL_FLAGS = FLAGS.stream().reduce(0, (a, b) -> a | b);

    @TestFactory Stream<DynamicTest> validTransactions() throws Exception { return vectors("tx_valid", 120, true); }
    @TestFactory Stream<DynamicTest> invalidTransactions() throws Exception { return vectors("tx_invalid", 93, false); }

    private Stream<DynamicTest> vectors(String name, int count, boolean valid) throws Exception {
        List<String> rows;
        try (var reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                getClass().getResourceAsStream("/bitcoin-core-v30/" + name + ".txt")), StandardCharsets.UTF_8))) {
            rows = reader.lines().toList();
        }
        assertEquals(count, rows.size());
        return IntStream.range(0, rows.size()).mapToObj(i -> DynamicTest.dynamicTest(name + " #" + (i + 1),
                () -> verifyVector(rows.get(i), valid, i)));
    }

    private static void verifyVector(String row, boolean valid, int rowIndex) throws Exception {
        var fields = row.split("\\|", -1);
        Transaction tx;
        try {
            tx = TransactionParser.parse(HexFormat.of().parseHex(fields[0]));
        } catch (IllegalArgumentException malformed) {
            assertFalse(valid);
            assertEquals("BADTX", fields[1]);
            return;
        }
        if (fields[1].equals("BADTX")) {
            assertThrows(TransactionValidationException.class, () -> TransactionValidator.validateBasic(tx));
            return;
        }
        TransactionValidator.validateBasic(tx);

        // Bitcoin Core's transaction vector corpus contains valid coinbase
        // transactions. Coinbase has no spendable prevouts, so script-input
        // validation is not applicable after basic transaction validation.
        if (tx.isCoinbase()) {
            assertTrue(valid, "Coinbase vector reached valid path");
            return;
        }

        Map<OutPoint, UtxoEntry> coins = coins(fields);
        int listed = ScriptVerifyFlags.parseNames(fields[1]);

        if (valid) {
            int base = trimFlags(ALL_FLAGS & ~listed);
            assertScripts(tx, coins, base, true, "primary valid flags=" + base);

            // Core: removing any individual verification flag cannot invalidate a valid tx.
            for (int flag : FLAGS) {
                int candidate = trimFlags(base & ~flag);
                assertScripts(tx, coins, candidate, true, "valid/remove flag=" + flag);
            }
            // Core also exercises mixed removals. Deterministic seed makes failures reproducible.
            SplittableRandom random = new SplittableRandom(0x434f52455f545856L ^ rowIndex);
            for (int n = 0; n < RANDOM_FLAG_COMBINATIONS; n++) {
                int mask = randomMask(random);
                assertScripts(tx, coins, trimFlags(base & ~mask), true, "valid/remove mask=" + mask);
            }
            // Vector excluded flags are maximal: enabling any one excluded flag must make it fail.
            for (int flag : FLAGS) {
                if ((listed & flag) != 0) {
                    assertScripts(tx, coins, fillFlags(base | flag), false, "valid/maximal flag=" + flag);
                }
            }
        } else {
            int base = fillFlags(listed);
            assertScripts(tx, coins, base, false, "primary invalid flags=" + base);

            // Core: adding any individual verification flag cannot validate an invalid tx.
            for (int flag : FLAGS) {
                assertScripts(tx, coins, fillFlags(base | flag), false, "invalid/add flag=" + flag);
            }
            SplittableRandom random = new SplittableRandom(0x434f52455f545849L ^ rowIndex);
            for (int n = 0; n < RANDOM_FLAG_COMBINATIONS; n++) {
                int mask = randomMask(random);
                assertScripts(tx, coins, fillFlags(base | mask), false, "invalid/add mask=" + mask);
            }
            // Vector required flags are minimal: removing any one required flag must make it valid.
            for (int flag : FLAGS) {
                if ((listed & flag) != 0) {
                    assertScripts(tx, coins, trimFlags(base & ~flag), true, "invalid/minimal flag=" + flag);
                }
            }
        }
    }

    private static Map<OutPoint, UtxoEntry> coins(String[] fields) throws Exception {
        Map<OutPoint, UtxoEntry> coins = new HashMap<>();
        for (int j = 2; j < fields.length; j++) {
            var coin = fields[j].split(",", -1);
            long index = Long.parseLong(coin[1]);
            coins.put(new OutPoint(Hash256.fromDisplayHex(coin[0]), new UInt32(index & 0xffff_ffffL)),
                    new UtxoEntry(Long.parseLong(coin[2]), BitcoinCoreScriptVectorsTest.assemble(
                            new String(Base64.getDecoder().decode(coin[3]), StandardCharsets.UTF_8)), 1, false));
        }
        return coins;
    }

    private static void assertScripts(Transaction tx, Map<OutPoint, UtxoEntry> coins, int flags,
                                      boolean expected, String context) {
        boolean accepted;
        try {
            InputScriptValidator.validateAll(tx, p -> Optional.ofNullable(coins.get(p)), flags);
            accepted = true;
        } catch (TransactionValidationException | ScriptExecutionException | ScriptParseException expectedFailure) {
            accepted = false;
        }
        assertEquals(expected, accepted, context);
    }

    /** Core TrimFlags: WITNESS requires P2SH; CLEANSTACK requires WITNESS and therefore P2SH. */
    private static int trimFlags(int flags) {
        if (!ScriptVerifyFlags.has(flags, ScriptVerifyFlags.P2SH)) flags &= ~ScriptVerifyFlags.WITNESS;
        if (!ScriptVerifyFlags.has(flags, ScriptVerifyFlags.WITNESS)) flags &= ~ScriptVerifyFlags.CLEANSTACK;
        return flags;
    }

    /** Core FillFlags: CLEANSTACK implies WITNESS, WITNESS implies P2SH. */
    private static int fillFlags(int flags) {
        if (ScriptVerifyFlags.has(flags, ScriptVerifyFlags.CLEANSTACK)) flags |= ScriptVerifyFlags.WITNESS;
        if (ScriptVerifyFlags.has(flags, ScriptVerifyFlags.WITNESS)) flags |= ScriptVerifyFlags.P2SH;
        return flags;
    }

    private static int randomMask(SplittableRandom random) {
        int mask = 0;
        for (int flag : FLAGS) if (random.nextBoolean()) mask |= flag;
        return mask;
    }

    private static List<Integer> declaredFlags() {
        try {
            return Arrays.stream(ScriptVerifyFlags.class.getFields())
                    .filter(f -> f.getType() == int.class)
                    .map(f -> {
                        try { return f.getInt(null); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
                    })
                    .filter(v -> v != 0 && Integer.bitCount(v) == 1)
                    .distinct().sorted().toList();
        } catch (RuntimeException e) {
            throw e;
        }
    }
}
