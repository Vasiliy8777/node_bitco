package ru.bitcoin.node.rvn.chain.assets;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.crypto.hash.Hash160;
import ru.bitcoin.node.crypto.secp256k1.*;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.rvn.chain.*;
import ru.bitcoin.node.rvn.protocol.*;
import ru.bitcoin.node.script.*;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.rvn.chain.RvnBlockBodyChecks.COIN;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetScriptCodec.*;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetMetadataRules.*;

class RvnCanonicalAssetBlockChecksTest {
    private static final PrivateKey KEY = new PrivateKey(BigInteger.ONE);
    private static final byte[] PUB = Secp256k1.publicKey(KEY).compressed();
    private static final byte[] LOCK = P2pkhScript.scriptPubKey(Hash160.hash(PUB));
    private static final Activation ACTIVE = new Activation(true, true, true);
    private static final RvnSpendContext CONTEXT = new RvnSpendContext(1001, 1600000000, 1600000000, false,
            ScriptVerifyFlags.P2SH | ScriptVerifyFlags.DERSIG | ScriptVerifyFlags.WITNESS, h -> 0);
    private static OutPoint point(int id) { byte[] bytes = new byte[32]; bytes[0] = (byte) id; return new OutPoint(new Hash256(bytes), new UInt32(0)); }
    private static TxOut asset(Payload payload) { return new TxOut(0, encodeCanonical(LOCK, payload)); }
    private static TxOut burn(String address, long amount) { return new TxOut(amount * COIN, RvnPayoutAddress.parse(address, RvnNetwork.MAINNET).scriptPubKey()); }
    private static Transaction tx(List<OutPoint> points, List<TxOut> outputs) {
        return new Transaction(2, points.stream().map(p -> new TxIn(p, new byte[0], TxIn.FINAL_SEQUENCE)).toList(), outputs, new UInt32(0));
    }
    private static Transaction sign(Transaction unsigned, int index, byte[] scriptCode) {
        byte[] der = Secp256k1.sign(LegacySignatureHash.calculate(unsigned, index, scriptCode, 1, ScriptDialect.RAVENCOIN), KEY).toDer();
        byte[] signature = Arrays.copyOf(der, der.length + 1); signature[der.length] = 1;
        var inputs = new ArrayList<>(unsigned.inputs()); var original = inputs.get(index);
        inputs.set(index, new TxIn(original.previousOutput(), P2pkhScript.scriptSig(signature, PUB), original.sequence()));
        return new Transaction(unsigned.version(), inputs, unsigned.outputs(), unsigned.lockTime());
    }
    private static Transaction issue() {
        return tx(List.of(point(1)), List.of(burn("RXissueAssetXXXXXXXXXXXXXXXXXhhZGt", 500),
                asset(new Owner("TOKEN!")), asset(new Issue("TOKEN", 10 * COIN, 0, true, null))));
    }
    private static RvnBlock block(List<Transaction> transactions) {
        return RvnBlockTemplateBuilder.build(0x20000000, new byte[32], CONTEXT.height(), 1600000001, 0x207fffff, 1,
                RvnPayoutAddress.parse("RXissueAssetXXXXXXXXXXXXXXXXXhhZGt", RvnNetwork.MAINNET), transactions, 0, true);
    }
    private static RvnCoinView parent() {
        return p -> p.equals(point(1)) ? Optional.of(new RvnCoin(new TxOut(500 * COIN, new byte[]{0x51}), 1, false))
                : (p.equals(point(2)) || p.equals(point(3))) ? Optional.of(new RvnCoin(new TxOut(100 * COIN, new byte[]{0x51}), 1, false))
                : Optional.empty();
    }

    private static RvnBlock coinbaseWith(TxOut output) throws Exception {
        return coinbaseWith(List.of(output));
    }
    private static RvnBlock coinbaseWith(List<TxOut> additions) throws Exception {
        var template = block(List.of());
        var original = template.transactions().getFirst();
        var outputs = new ArrayList<>(original.outputs());
        var payout = outputs.getFirst();
        outputs.set(0, new TxOut(payout.value() - additions.stream().mapToLong(TxOut::value).sum(), payout.scriptPubKey()));
        outputs.addAll(additions);
        var coinbase = new Transaction(original.version(), original.inputs(), outputs, original.lockTime());
        byte[] raw = template.header().serialize();
        System.arraycopy(coinbase.txId().bytes(), 0, raw, 36, 32);
        return new RvnBlock(RvnBlockHeader.parse(raw, RvnActivation.MAINNET_KAWPOW_TIME), List.of(coinbase));
    }

    @Test void historicalCoinbaseIssueUsesStatelessLayoutAndDoesNotReplaceExistingMetadata() throws Exception {
        var outputs = new ArrayList<>(issue().outputs());
        outputs.add(0, asset(new Transfer("UNKNOWN", -1, null, 0)));
        var candidate = coinbaseWith(outputs);
        var plan = RvnCanonicalAssetBlockChecks.verifyHistoricalMainnet(candidate, CONTEXT, ACTIVE, true,
                parent(), n -> null, rules(Map.of()), true, false);
        assertEquals(10 * COIN, plan.assets().entries().get("TOKEN").after().orElseThrow().supply());
        var previous = new Metadata("TOKEN", COIN, 8, false, null);
        var duplicate = RvnCanonicalAssetBlockChecks.verifyHistoricalMainnet(candidate, CONTEXT, ACTIVE, true,
                parent(), n -> n.equals("TOKEN") ? previous : null, rules(Map.of()), true, false);
        assertTrue(duplicate.assets().entries().isEmpty());
        var malformed = new ArrayList<>(outputs); malformed.remove(malformed.size() - 2);
        assertThrows(SecurityException.class, () -> RvnCanonicalAssetBlockChecks.verifyHistoricalMainnet(coinbaseWith(malformed),
                CONTEXT, ACTIVE, true, parent(), n -> null, rules(Map.of()), true, false));
    }

    @Test void historicalCoinbaseReissueSkipsPermissionAndMissingMetadataButChecksBurn() throws Exception {
        var outputs = List.of(burn("RXReissueAssetXXXXXXXXXXXXXXVEFAWu", 100),
                asset(new Transfer("TOKEN!", COIN, null, 0)), asset(new Reissue("TOKEN", COIN, 0, true, null)));
        var candidate = coinbaseWith(outputs);
        var previous = new Metadata("TOKEN", COIN, 8, false, null);
        var plan = RvnCanonicalAssetBlockChecks.verifyHistoricalMainnet(candidate, CONTEXT, ACTIVE, true,
                parent(), n -> n.equals("TOKEN") ? previous : null, rules(Map.of()), true, false);
        var updated = plan.assets().entries().get("TOKEN").after().orElseThrow();
        assertEquals(2 * COIN, updated.supply()); assertEquals(0, updated.units()); assertTrue(updated.reissuable());
        var missing = RvnCanonicalAssetBlockChecks.verifyHistoricalMainnet(candidate, CONTEXT, ACTIVE, true,
                parent(), n -> null, rules(Map.of()), true, false);
        assertTrue(missing.assets().entries().isEmpty());
        var malformed = new ArrayList<>(outputs); malformed.set(0, new TxOut(0, new byte[]{0x51}));
        assertThrows(SecurityException.class, () -> RvnCanonicalAssetBlockChecks.verifyHistoricalMainnet(coinbaseWith(malformed),
                CONTEXT, ACTIVE, true, parent(), n -> previous, rules(Map.of()), true, false));
    }

    @Test void nativeReplayCannotAdmitNonCoinbaseAssetsBeforeDeployment() {
        var error = assertThrows(SecurityException.class, () -> RvnNativeTransactionChecks.block(
                block(List.of(issue())), CONTEXT, false, parent()));
        assertTrue(error.getMessage().contains("before activation"));
    }

    @Test void historicalCoinbaseNullRulesKeepStatelessAuthorizationWithoutParentStatusChecks() throws Exception {
        var address = HexFormat.of().formatHex(Hash160.hash(PUB));
        var tag = new RvnAssetRuleKey(RvnAssetRuleKey.Kind.QUALIFIER, "#KYC", address);
        var freeze = new RvnAssetRuleKey(RvnAssetRuleKey.Kind.ADDRESS_FREEZE, "$TOKEN", address);
        var global = new RvnAssetRuleKey(RvnAssetRuleKey.Kind.GLOBAL_FREEZE, "$TOKEN", "");
        var outputs = List.of(new TxOut(COIN / 10, RvnPayoutAddress.parse("RXaddTagBurnXXXXXXXXXXXXXXXXZQm5ya", RvnNetwork.MAINNET).scriptPubKey()),
                asset(new Transfer("#KYC", COIN, null, 0)), asset(new Transfer("TOKEN!", COIN, null, 0)),
                nullRule("#KYC", false, 1), nullRule("$TOKEN", false, 1), nullRule("$TOKEN", true, 1));
        var candidate = coinbaseWith(outputs);
        var plan = RvnCanonicalAssetBlockChecks.verifyHistoricalMainnet(candidate, CONTEXT,
                new Activation(true, false, false, false), false, parent(), n -> null, rules(Map.of()), true, false);
        for (var key : List.of(tag, freeze, global)) assertEquals(Optional.of("1"), plan.assets().rules().get(key).after());
        var redundant = RvnCanonicalAssetBlockChecks.verifyHistoricalMainnet(candidate, CONTEXT, ACTIVE, true,
                parent(), n -> null, rules(Map.of(tag, "1", freeze, "1", global, "1")), true, false);
        assertTrue(redundant.assets().rules().isEmpty());
        var bad = new ArrayList<>(outputs); bad.remove(0);
        assertThrows(SecurityException.class, () -> RvnCanonicalAssetBlockChecks.verifyHistoricalMainnet(coinbaseWith(bad),
                CONTEXT, ACTIVE, true, parent(), n -> null, rules(Map.of()), true, false));
        var badFlag = new ArrayList<>(outputs); badFlag.set(3, nullRule("#KYC", false, 2));
        assertThrows(SecurityException.class, () -> RvnCanonicalAssetBlockChecks.verifyHistoricalMainnet(coinbaseWith(badFlag),
                CONTEXT, ACTIVE, true, parent(), n -> null, rules(Map.of()), true, false));
    }

    @Test void historicalRestrictedCoinbaseChecksVerifierSyntaxButSkipsExistenceAndDestination() throws Exception {
        var candidate = coinbaseWith(List.of(burn("RXissueRestrictedXXXXXXXXXXXXzJZ1q", 1500),
                asset(new Transfer("TOKEN!", COIN, null, 0)), nullVerifier("MISSING"),
                asset(new Issue("$TOKEN", COIN, 0, true, null))));
        var plan = RvnCanonicalAssetBlockChecks.verifyHistoricalMainnet(candidate, CONTEXT, ACTIVE, true,
                parent(), n -> null, rules(Map.of()), true, false);
        assertEquals(COIN, plan.assets().entries().get("$TOKEN").after().orElseThrow().supply());
        var verifier = new RvnAssetRuleKey(RvnAssetRuleKey.Kind.VERIFIER, "$TOKEN", "");
        assertEquals(Optional.of("MISSING"), plan.assets().rules().get(verifier).after());
        assertThrows(SecurityException.class, () -> RvnCanonicalAssetBlockChecks.verifyHistoricalMainnet(candidate,
                CONTEXT, ACTIVE, true, parent(), n -> null, rules(Map.of()), true, true));
    }

    @Test void historicalReissuePreservesWrappedSupplyAndNonDivisibleUnits() throws Exception {
        var candidate = coinbaseWith(List.of(burn("RXReissueAssetXXXXXXXXXXXXXXVEFAWu", 100),
                asset(new Transfer("TOKEN!", COIN, null, 0)), asset(new Reissue("TOKEN", 1, 0, true, null))));
        for (long supply : new long[] {COIN, Long.MAX_VALUE}) {
            var previous = new Metadata("TOKEN", supply, 8, false, null);
            var plan = RvnCanonicalAssetBlockChecks.verifyHistoricalMainnet(candidate, CONTEXT, ACTIVE, true,
                    parent(), n -> n.equals("TOKEN") ? previous : null, rules(Map.of()), true, false);
            var value = plan.assets().entries().get("TOKEN").after().orElseThrow();
            assertEquals(supply + 1, value.supply()); assertEquals(0, value.units());
        }
    }

    @Test void preAssetCoinbaseRunsStatelessRulesWithoutInstallingAnAssetRegistry() throws Exception {
        var candidate = coinbaseWith(issue().outputs());
        var changes = RvnNativeTransactionChecks.block(candidate, CONTEXT, false, parent());
        var coinbase = candidate.transactions().getFirst();
        assertTrue(changes.created().containsKey(new OutPoint(coinbase.txId(), new UInt32(coinbase.outputs().size() - 1))));
        var malformed = coinbaseWith(List.of(asset(new Transfer("TOKEN!", 2 * COIN, null, 0))));
        assertThrows(SecurityException.class, () -> RvnNativeTransactionChecks.block(malformed, CONTEXT, false, parent()));
        var opaque = coinbaseWith(new TxOut(0, new byte[]{(byte) 0xc0, 1, 2}));
        assertDoesNotThrow(() -> RvnNativeTransactionChecks.block(opaque, CONTEXT, false, parent()));
    }

    @Test void historicalCoinbasePersistsOpaqueGlobalAndIgnoresOrdinaryAddressNullCommands() throws Exception {
        var candidate = coinbaseWith(List.of(asset(new Transfer("TOKEN!", COIN, null, 0)),
                asset(new Transfer("TOKEN", COIN, null, 0)), nullRule("XTOKEN", true, 1), nullRule("TOKEN", false, 1)));
        var plan = RvnCanonicalAssetBlockChecks.verifyHistoricalMainnet(candidate, CONTEXT, ACTIVE, true,
                parent(), n -> null, rules(Map.of()), true, false);
        assertEquals(1, plan.assets().rules().size());
        assertEquals(Optional.of("1"), plan.assets().rules().get(new RvnAssetRuleKey(RvnAssetRuleKey.Kind.GLOBAL_FREEZE, "XTOKEN", "")).after());
        var missing = coinbaseWith(List.of(asset(new Transfer("OTHER!", COIN, null, 0)), nullRule("XTOKEN", true, 1)));
        assertThrows(SecurityException.class, () -> RvnCanonicalAssetBlockChecks.verifyHistoricalMainnet(missing, CONTEXT, ACTIVE, true,
                parent(), n -> null, rules(Map.of()), true, false));
    }

    @Test void historicalCoinbaseTransfersSkipContextualAccountingOnlyBeforeBitTen() throws Exception {
        for (long amount : new long[] {COIN, -1, Long.MAX_VALUE}) {
            var candidate = coinbaseWith(asset(new Transfer("UNKNOWN", amount, null, 0)));
            var plan = RvnCanonicalAssetBlockChecks.verifyHistoricalMainnet(candidate, CONTEXT, ACTIVE, true,
                    parent(), n -> null, rules(Map.of()), true, false);
            assertTrue(plan.assets().entries().isEmpty());
            var coinbase = candidate.transactions().getFirst();
            assertTrue(plan.coins().created().containsKey(new OutPoint(coinbase.txId(), new UInt32(coinbase.outputs().size() - 1))));
            assertThrows(SecurityException.class, () -> RvnCanonicalAssetBlockChecks.verifyHistoricalMainnet(candidate,
                    CONTEXT, ACTIVE, true, parent(), n -> null, rules(Map.of()), true, true));
        }
        var badOwner = coinbaseWith(asset(new Transfer("TOKEN!", 2 * COIN, null, 0)));
        assertThrows(SecurityException.class, () -> RvnCanonicalAssetBlockChecks.verifyHistoricalMainnet(badOwner,
                CONTEXT, ACTIVE, true, parent(), n -> null, rules(Map.of()), true, false));
        var nativeValue = coinbaseWith(new TxOut(1, encodeCanonical(LOCK, new Transfer("TOKEN", COIN, null, 0))));
        assertThrows(SecurityException.class, () -> RvnCanonicalAssetBlockChecks.verifyHistoricalMainnet(nativeValue,
                CONTEXT, ACTIVE, true, parent(), n -> null, rules(Map.of()), true, false));
    }

    @Test void historicalAdmissionSpendsOriginalNoncanonicalEnvelopeWithOpaqueCodeSeparator() {
        var original = issue();
        var outputs = new ArrayList<>(original.outputs());
        byte[] historical = outputs.get(2).scriptPubKey();
        historical[26] = (byte) 0xff;
        historical[historical.length - 1] = (byte) 0xab;
        outputs.set(2, new TxOut(0, historical));
        var issuance = tx(List.of(point(1)), outputs);
        var transfer = sign(tx(List.of(new OutPoint(issuance.txId(), new UInt32(2))),
                List.of(asset(new Transfer("TOKEN", 10 * COIN, null, 0)))), 0, historical);
        var candidate = block(List.of(issuance, transfer));
        var plan = RvnCanonicalAssetBlockChecks.verifyHistoricalMainnet(candidate, CONTEXT, ACTIVE, true,
                parent(), n -> null, rules(Map.of()), true);
        assertEquals(10 * COIN, plan.assets().entries().get("TOKEN").after().orElseThrow().supply());
        assertTrue(plan.coins().created().containsKey(new OutPoint(transfer.txId(), new UInt32(0))));
        assertThrows(RuntimeException.class, () -> RvnCanonicalAssetBlockChecks.verifyMainnet(candidate,
                CONTEXT, ACTIVE, true, parent(), n -> null));
        var badSignature = sign(tx(List.of(new OutPoint(issuance.txId(), new UInt32(2))), transfer.outputs()), 0, LOCK);
        assertThrows(SecurityException.class, () -> RvnCanonicalAssetBlockChecks.verifyHistoricalMainnet(
                block(List.of(issuance, badSignature)), CONTEXT, ACTIVE, true, parent(), n -> null, rules(Map.of()), true));
    }

    @Test void signedIssueTransferAndReissueInOneBlockProduceNetAtomicPlans() {
        var issue = issue();
        var transfer = sign(tx(List.of(new OutPoint(issue.txId(), new UInt32(2))),
                List.of(asset(new Transfer("TOKEN", 4 * COIN, null, 0)), asset(new Transfer("TOKEN", 6 * COIN, null, 0)))),
                0, issue.outputs().get(2).scriptPubKey());
        var reissue = sign(tx(List.of(new OutPoint(issue.txId(), new UInt32(1)), point(2)),
                List.of(burn("RXReissueAssetXXXXXXXXXXXXXXVEFAWu", 100), asset(new Transfer("TOKEN!", COIN, null, 0)),
                        asset(new Reissue("TOKEN", COIN, -1, true, null)))), 0, issue.outputs().get(1).scriptPubKey());
        var plan = RvnCanonicalAssetBlockChecks.verifyMainnet(block(List.of(issue, transfer, reissue)), CONTEXT, ACTIVE, true, parent(), n -> null);
        assertEquals(11 * COIN, plan.assets().entries().get("TOKEN").after().orElseThrow().supply());
        assertTrue(plan.assets().entries().get("TOKEN").before().isEmpty());
        assertFalse(plan.coins().created().containsKey(new OutPoint(issue.txId(), new UInt32(2))));
        assertTrue(plan.coins().created().containsKey(new OutPoint(transfer.txId(), new UInt32(0))));
    }

    @Test void preEnforcementReissueUsesHistoricalNativeValueAccountingThroughoutBlockChecks() {
        var issuance = issue();
        var changed = new TxOut(COIN, encodeCanonical(LOCK, new Reissue("TOKEN", COIN, -1, true, null)));
        var reissue = sign(tx(List.of(new OutPoint(issuance.txId(), new UInt32(1)), point(2)),
                List.of(burn("RXReissueAssetXXXXXXXXXXXXXXVEFAWu", 100), asset(new Transfer("TOKEN!", COIN, null, 0)), changed)),
                0, issuance.outputs().get(1).scriptPubKey());
        var candidate = block(List.of(issuance, reissue));
        var plan = RvnCanonicalAssetBlockChecks.verifyMainnet(candidate, CONTEXT, ACTIVE, false, parent(), n -> null);
        assertEquals(0, plan.coins().fees());
        assertEquals(COIN, plan.coins().created().get(new OutPoint(reissue.txId(), new UInt32(2))).output().value());
        assertEquals(11 * COIN, plan.assets().entries().get("TOKEN").after().orElseThrow().supply());
        assertThrows(SecurityException.class, () -> RvnCanonicalAssetBlockChecks.verifyMainnet(candidate,
                CONTEXT, ACTIVE, true, parent(), n -> null));
    }

    @Test void issueAndOwnerMustHaveSameDestinationInBothEnvelopeModes() {
        var original = issue(); var outputs = new ArrayList<>(original.outputs());
        byte[] changed = outputs.get(1).scriptPubKey(); changed[3] ^= 1;
        outputs.set(1, new TxOut(0, changed)); var bad = block(List.of(tx(List.of(point(1)), outputs)));
        assertThrows(SecurityException.class, () -> RvnCanonicalAssetBlockChecks.verifyMainnet(bad,
                CONTEXT, ACTIVE, true, parent(), n -> null));
        assertThrows(SecurityException.class, () -> RvnCanonicalAssetBlockChecks.verifyHistoricalMainnet(bad,
                CONTEXT, ACTIVE, true, parent(), n -> null, rules(Map.of()), true));
        outputs = new ArrayList<>(original.outputs());
        byte[] owner = outputs.get(1).scriptPubKey(); owner[0] = 0;
        byte[] issued = outputs.get(2).scriptPubKey(); issued[0] = 0x51;
        outputs.set(1, new TxOut(0, owner)); outputs.set(2, new TxOut(0, issued));
        var valid = block(List.of(tx(List.of(point(1)), outputs)));
        assertEquals(10 * COIN, RvnCanonicalAssetBlockChecks.verifyHistoricalMainnet(valid, CONTEXT, ACTIVE,
                true, parent(), n -> null, rules(Map.of()), true).assets().entries().get("TOKEN").after().orElseThrow().supply());
    }

    @Test void signaturesCommitOriginalAssetSuffixAndTokenInflationIsRejected() {
        var issue = issue();
        var unsigned = tx(List.of(new OutPoint(issue.txId(), new UInt32(2))), List.of(asset(new Transfer("TOKEN", 10 * COIN, null, 0))));
        var strippedSignature = sign(unsigned, 0, LOCK);
        assertThrows(SecurityException.class, () -> RvnCanonicalAssetBlockChecks.verifyMainnet(block(List.of(issue, strippedSignature)), CONTEXT, ACTIVE, true, parent(), n -> null));
        var inflated = sign(tx(List.of(new OutPoint(issue.txId(), new UInt32(2))), List.of(asset(new Transfer("TOKEN", 11 * COIN, null, 0)))),
                0, issue.outputs().get(2).scriptPubKey());
        assertThrows(SecurityException.class, () -> RvnCanonicalAssetBlockChecks.verifyMainnet(block(List.of(issue, inflated)), CONTEXT, ACTIVE, true, parent(), n -> null));
    }

    @Test void unsupportedAssetScriptsAndFamiliesFailClosed() {
        var malformed = tx(List.of(point(1)), List.of(new TxOut(500 * COIN, new byte[]{0x51, (byte) 0xc0, 0})));
        assertThrows(UnsupportedOperationException.class, () -> RvnCanonicalAssetBlockChecks.verifyMainnet(block(List.of(malformed)), CONTEXT, ACTIVE, true, parent(), n -> null));
        // OP_RVN_ASSET inside a pushed value is ordinary data, not an asset opcode.
        var data = tx(List.of(point(1)), List.of(new TxOut(500 * COIN, new byte[]{1, (byte) 0xc0})));
        assertTrue(RvnCanonicalAssetBlockChecks.verifyMainnet(block(List.of(data)), CONTEXT, ACTIVE, true, parent(), n -> null).assets().entries().isEmpty());
    }

    @Test void subassetMayUseParentIssuedEarlierInSameBlock() {
        var issue = issue();
        var sub = sign(tx(List.of(new OutPoint(issue.txId(), new UInt32(1)), point(2)), List.of(
                burn("RXissueSubAssetXXXXXXXXXXXXXWcwhwL", 100), asset(new Transfer("TOKEN!", COIN, null, 0)),
                asset(new Owner("TOKEN/SUB!")), asset(new Issue("TOKEN/SUB", COIN, 0, true, null)))),
                0, issue.outputs().get(1).scriptPubKey());
        var plan = RvnCanonicalAssetBlockChecks.verifyMainnet(block(List.of(issue, sub)), CONTEXT, ACTIVE, true, parent(), n -> null);
        assertEquals(Set.of("TOKEN", "TOKEN/SUB"), plan.assets().entries().keySet());
        assertEquals(COIN, plan.assets().entries().get("TOKEN/SUB").after().orElseThrow().supply());
    }

    @Test void chainedReissuesOfSameAssetAreRejectedBeforeStatePublication() {
        var issue = issue();
        var reissue = sign(tx(List.of(new OutPoint(issue.txId(), new UInt32(1)), point(2)),
                List.of(burn("RXReissueAssetXXXXXXXXXXXXXXVEFAWu", 100), asset(new Transfer("TOKEN!", COIN, null, 0)),
                        asset(new Reissue("TOKEN", COIN, -1, true, null)))), 0, issue.outputs().get(1).scriptPubKey());
        var repeated = sign(tx(List.of(new OutPoint(reissue.txId(), new UInt32(1)), point(3)), reissue.outputs()),
                0, reissue.outputs().get(1).scriptPubKey());
        assertThrows(SecurityException.class, () -> RvnCanonicalAssetBlockChecks.verifyMainnet(block(List.of(issue, reissue, repeated)),
                CONTEXT, ACTIVE, true, parent(), n -> null));
    }

    @Test void signedUniqueBatchTransferAndChannelUseParentIssuedWithinBlock() {
        var issue = issue();
        var unique = sign(tx(List.of(new OutPoint(issue.txId(), new UInt32(1)), point(2)), List.of(
                burn("RXissueUniqueAssetXXXXXXXXXXWEAe58", 10), new TxOut(90 * COIN, new byte[]{0x51}),
                asset(new Transfer("TOKEN!", COIN, null, 0)), asset(new Issue("TOKEN#One", COIN, 0, false, null)),
                asset(new Issue("TOKEN#Two", COIN, 0, false, null)))), 0, issue.outputs().get(1).scriptPubKey());
        var transfer = sign(tx(List.of(new OutPoint(unique.txId(), new UInt32(3))),
                List.of(asset(new Transfer("TOKEN#One", COIN, null, 0)))), 0, unique.outputs().get(3).scriptPubKey());
        var channel = sign(tx(List.of(new OutPoint(unique.txId(), new UInt32(2)), point(3)), List.of(
                burn("RXissueMsgChanneLAssetXXXXXXSjHvAY", 100), asset(new Transfer("TOKEN!", COIN, null, 0)),
                asset(new Issue("TOKEN~NEWS", COIN, 0, false, null)))), 0, unique.outputs().get(2).scriptPubKey());
        var candidate = block(List.of(issue, unique, transfer, channel));
        var plan = RvnCanonicalAssetBlockChecks.verifyMainnet(candidate, CONTEXT, ACTIVE, true, parent(), n -> null);
        assertEquals(Set.of("TOKEN", "TOKEN#One", "TOKEN#Two", "TOKEN~NEWS"), plan.assets().entries().keySet());
        assertFalse(plan.coins().created().containsKey(new OutPoint(unique.txId(), new UInt32(3))));
        assertTrue(plan.coins().created().containsKey(new OutPoint(transfer.txId(), new UInt32(0))));
        assertThrows(SecurityException.class, () -> RvnCanonicalAssetBlockChecks.verifyMainnet(candidate, CONTEXT,
                new Activation(true, false, false), true, parent(), n -> null));
    }

    private static TxOut nullVerifier(String expression) {
        var out = new java.io.ByteArrayOutputStream(); out.write(0xc0); out.write(0x50);
        out.write(expression.length()+1); out.write(expression.length()); out.writeBytes(expression.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return new TxOut(0, out.toByteArray());
    }
    private static TxOut nullRule(String name, boolean global, int flag) {
        var out = new java.io.ByteArrayOutputStream(); out.write(0xc0);
        if (global) { out.write(0x50); out.write(0x50); }
        else { out.write(20); out.writeBytes(Hash160.hash(PUB)); }
        out.write(name.length()+2); out.write(name.length()); out.writeBytes(name.getBytes(java.nio.charset.StandardCharsets.US_ASCII)); out.write(flag);
        return new TxOut(0, out.toByteArray());
    }
    private static RvnAssetRulesView rules(Map<RvnAssetRuleKey, String> state) {
        var stable = Map.copyOf(state);
        return new RvnAssetRulesView() {
            public Optional<String> findRule(RvnAssetRuleKey key) { return Optional.ofNullable(stable.get(key)); }
            public boolean hasQualifier(String name, String address) {
                return stable.keySet().stream().anyMatch(k -> k.kind() == RvnAssetRuleKey.Kind.QUALIFIER && k.addressHash().equals(address)
                        && (k.name().equals(name) || k.name().startsWith(name + "/")));
            }
        };
    }
    private static Metadata metadata(String name) {
        return new Metadata(name, 10 * COIN, 0, !name.startsWith("#"), null);
    }

    @Test void signedQualifierAndSubqualifierIssuanceReturnParentQualifier() {
        RvnCoinView funds = p -> p.equals(point(7)) ? Optional.of(new RvnCoin(new TxOut(1000 * COIN, new byte[]{0x51}), 1, false)) : parent().find(p);
        var root = tx(List.of(point(7)), List.of(burn("RXissueQuaLifierXXXXXXXXXXXXUgEDbC", 1000),
                asset(new Issue("#KYC", 2 * COIN, 0, false, null))));
        var child = sign(tx(List.of(new OutPoint(root.txId(), new UInt32(1)), point(2)), List.of(
                burn("RXissueSubQuaLifierXXXXXXXXXVTzvv5", 100), asset(new Transfer("#KYC", 2 * COIN, null, 0)),
                asset(new Issue("#KYC/#ID", COIN, 0, false, null)))), 0, root.outputs().get(1).scriptPubKey());
        var candidate = block(List.of(root, child));
        var plan = RvnCanonicalAssetBlockChecks.verifyMainnet(candidate, CONTEXT, ACTIVE, true, funds, n -> null);
        assertEquals(Set.of("#KYC", "#KYC/#ID"), plan.assets().entries().keySet());
        assertThrows(SecurityException.class, () -> RvnCanonicalAssetBlockChecks.verifyMainnet(candidate, CONTEXT,
                new Activation(true, true, false), true, funds, n -> null));
    }

    @Test void restrictedIssueAndZeroAmountReissueRequireVerifierAndPruneEmptyCoin() {
        var owner = asset(new Owner("TOKEN!"));
        RvnCoinView view = p -> p.equals(point(4)) ? Optional.of(new RvnCoin(new TxOut(1500 * COIN, new byte[]{0x51}), 1, false))
                : p.equals(point(5)) ? Optional.of(new RvnCoin(owner, 1, false)) : parent().find(p);
        java.util.function.Function<String, Metadata> registry = n -> Set.of("TOKEN", "#KYC").contains(n) ? metadata(n) : null;
        String address = HexFormat.of().formatHex(Hash160.hash(PUB));
        var parentRules = rules(Map.of(new RvnAssetRuleKey(RvnAssetRuleKey.Kind.QUALIFIER, "#KYC", address), "1"));
        var issue = sign(tx(List.of(point(4), point(5)), List.of(burn("RXissueRestrictedXXXXXXXXXXXXzJZ1q", 1500),
                asset(new Transfer("TOKEN!", COIN, null, 0)), nullVerifier("KYC"), asset(new Issue("$TOKEN", 10 * COIN, 0, true, null)))),
                1, owner.scriptPubKey());
        var reissue = sign(tx(List.of(new OutPoint(issue.txId(), new UInt32(1)), point(2)), List.of(
                burn("RXReissueAssetXXXXXXXXXXXXXXVEFAWu", 100), asset(new Transfer("TOKEN!", COIN, null, 0)),
                nullVerifier("true"), asset(new Reissue("$TOKEN", 0, -1, true, null)))), 0, issue.outputs().get(1).scriptPubKey());
        var plan = RvnCanonicalAssetBlockChecks.verifyMainnet(block(List.of(issue, reissue)), CONTEXT, ACTIVE, true, view, registry, parentRules);
        var verifier = new RvnAssetRuleKey(RvnAssetRuleKey.Kind.VERIFIER, "$TOKEN", "");
        assertEquals(Optional.of("true"), plan.assets().rules().get(verifier).after());
        assertEquals(10 * COIN, plan.assets().entries().get("$TOKEN").after().orElseThrow().supply());
        assertFalse(plan.coins().created().containsKey(new OutPoint(reissue.txId(), new UInt32(3))));
        assertThrows(SecurityException.class, () -> RvnCanonicalAssetBlockChecks.verifyMainnet(block(List.of(issue)), CONTEXT, ACTIVE, true,
                view, registry, rules(Map.of())));
    }

    @Test void restrictedTransferAndFreezeCheckStableParentRegardlessOfTransactionOrder() {
        var owner = asset(new Owner("TOKEN!")); var restricted = asset(new Transfer("$TOKEN", COIN, null, 0));
        RvnCoinView view = p -> p.equals(point(5)) ? Optional.of(new RvnCoin(owner, 1, false))
                : p.equals(point(6)) ? Optional.of(new RvnCoin(restricted, 1, false)) : parent().find(p);
        java.util.function.Function<String, Metadata> registry = n -> Set.of("TOKEN", "$TOKEN").contains(n) ? metadata(n) : null;
        var verifier = new RvnAssetRuleKey(RvnAssetRuleKey.Kind.VERIFIER, "$TOKEN", "");
        var global = new RvnAssetRuleKey(RvnAssetRuleKey.Kind.GLOBAL_FREEZE, "$TOKEN", "");
        var transfer = sign(tx(List.of(point(6)), List.of(restricted)), 0, restricted.scriptPubKey());
        var freeze = sign(tx(List.of(point(5)), List.of(asset(new Transfer("TOKEN!", COIN, null, 0)), nullRule("$TOKEN", true, 1))),
                0, owner.scriptPubKey());
        for (var transactions : List.of(List.of(transfer, freeze), List.of(freeze, transfer))) {
            var plan = RvnCanonicalAssetBlockChecks.verifyMainnet(block(transactions), CONTEXT, ACTIVE, true, view, registry, rules(Map.of(verifier, "true")));
            assertEquals(Optional.of("1"), plan.assets().rules().get(global).after());
        }
        assertThrows(SecurityException.class, () -> RvnCanonicalAssetBlockChecks.verifyMainnet(block(List.of(transfer)), CONTEXT, ACTIVE, true,
                view, registry, rules(Map.of(verifier, "true", global, "1"))));
        var sourceFreeze = new RvnAssetRuleKey(RvnAssetRuleKey.Kind.ADDRESS_FREEZE, "$TOKEN", HexFormat.of().formatHex(Hash160.hash(PUB)));
        assertThrows(SecurityException.class, () -> RvnCanonicalAssetBlockChecks.verifyMainnet(block(List.of(transfer)), CONTEXT, ACTIVE, true,
                view, registry, rules(Map.of(verifier, "true", sourceFreeze, "1"))));
    }

    @Test void qualifierTagsRequireTokenReturnAndBurnAndCannotAuthorizeTransfersInSameBlock() {
        var qualifier = asset(new Transfer("#KYC", COIN, null, 0));
        var restricted = asset(new Transfer("$TOKEN", COIN, null, 0));
        RvnCoinView view = p -> p.equals(point(8)) ? Optional.of(new RvnCoin(qualifier, 1, false))
                : p.equals(point(9)) ? Optional.of(new RvnCoin(new TxOut(COIN / 10, new byte[]{0x51}), 1, false))
                : p.equals(point(6)) ? Optional.of(new RvnCoin(restricted, 1, false)) : parent().find(p);
        java.util.function.Function<String, Metadata> registry = n -> Set.of("#KYC", "$TOKEN").contains(n) ? metadata(n) : null;
        var burn = new TxOut(COIN / 10, RvnPayoutAddress.parse("RXaddTagBurnXXXXXXXXXXXXXXXXZQm5ya", RvnNetwork.MAINNET).scriptPubKey());
        var tag = sign(tx(List.of(point(8), point(9)), List.of(burn, qualifier, nullRule("#KYC", false, 1))), 0, qualifier.scriptPubKey());
        var verifier = new RvnAssetRuleKey(RvnAssetRuleKey.Kind.VERIFIER, "$TOKEN", "");
        var snapshot = rules(Map.of(verifier, "KYC"));
        var plan = RvnCanonicalAssetBlockChecks.verifyMainnet(block(List.of(tag)), CONTEXT, ACTIVE, true, view, registry, snapshot);
        var assigned = new RvnAssetRuleKey(RvnAssetRuleKey.Kind.QUALIFIER, "#KYC", HexFormat.of().formatHex(Hash160.hash(PUB)));
        assertEquals(Optional.of("1"), plan.assets().rules().get(assigned).after());
        assertFalse(plan.coins().created().containsKey(new OutPoint(tag.txId(), new UInt32(2))));
        var transfer = sign(tx(List.of(point(6)), List.of(restricted)), 0, restricted.scriptPubKey());
        for (var order : List.of(List.of(tag, transfer), List.of(transfer, tag)))
            assertThrows(SecurityException.class, () -> RvnCanonicalAssetBlockChecks.verifyMainnet(block(order), CONTEXT, ACTIVE, true, view, registry, snapshot));
        var bad = sign(tx(List.of(point(8), point(9)), List.of(new TxOut(COIN / 10, new byte[]{0x51}), qualifier, nullRule("#KYC", false, 1))),
                0, qualifier.scriptPubKey());
        assertThrows(SecurityException.class, () -> RvnCanonicalAssetBlockChecks.verifyMainnet(block(List.of(bad)), CONTEXT, ACTIVE, true, view, registry, snapshot));
    }
}
