package ru.bitcoin.node.mempool.policy;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.script.ScriptVerifyFlags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StandardScriptVerifyFlagsTest {

    @Test
    void mandatoryFlagsMustContainCurrentConsensusBaseline() {

        int flags =
                StandardScriptVerifyFlags.MANDATORY;

        assertTrue(
                ScriptVerifyFlags.has(
                        flags,
                        ScriptVerifyFlags.P2SH
                )
        );

        assertTrue(
                ScriptVerifyFlags.has(
                        flags,
                        ScriptVerifyFlags.DERSIG
                )
        );

        assertTrue(
                ScriptVerifyFlags.has(
                        flags,
                        ScriptVerifyFlags.NULLDUMMY
                )
        );

        assertTrue(
                ScriptVerifyFlags.has(
                        flags,
                        ScriptVerifyFlags.CHECKLOCKTIMEVERIFY
                )
        );

        assertTrue(
                ScriptVerifyFlags.has(
                        flags,
                        ScriptVerifyFlags.CHECKSEQUENCEVERIFY
                )
        );

        assertTrue(
                ScriptVerifyFlags.has(
                        flags,
                        ScriptVerifyFlags.WITNESS
                )
        );
    }

    @Test
    void standardMustContainAllAdditionalPolicyFlags() {

        int flags =
                StandardScriptVerifyFlags.STANDARD;

        assertTrue(
                ScriptVerifyFlags.has(
                        flags,
                        ScriptVerifyFlags.STRICTENC
                )
        );

        assertTrue(
                ScriptVerifyFlags.has(
                        flags,
                        ScriptVerifyFlags.MINIMALDATA
                )
        );

        assertTrue(
                ScriptVerifyFlags.has(
                        flags,
                        ScriptVerifyFlags.DISCOURAGE_UPGRADABLE_NOPS
                )
        );

        assertTrue(
                ScriptVerifyFlags.has(
                        flags,
                        ScriptVerifyFlags.CLEANSTACK
                )
        );

        assertTrue(
                ScriptVerifyFlags.has(
                        flags,
                        ScriptVerifyFlags.MINIMALIF
                )
        );

        assertTrue(
                ScriptVerifyFlags.has(
                        flags,
                        ScriptVerifyFlags.NULLFAIL
                )
        );

        assertTrue(
                ScriptVerifyFlags.has(
                        flags,
                        ScriptVerifyFlags.LOW_S
                )
        );

        assertTrue(
                ScriptVerifyFlags.has(
                        flags,
                        ScriptVerifyFlags.DISCOURAGE_UPGRADABLE_WITNESS_PROGRAM
                )
        );

        assertTrue(
                ScriptVerifyFlags.has(
                        flags,
                        ScriptVerifyFlags.WITNESS_PUBKEYTYPE
                )
        );

        assertTrue(
                ScriptVerifyFlags.has(
                        flags,
                        ScriptVerifyFlags.CONST_SCRIPTCODE
                )
        );
    }

    @Test
    void mandatoryMustNotContainPolicyOnlyFlags() {

        int flags =
                StandardScriptVerifyFlags.MANDATORY;

        assertFalse(
                ScriptVerifyFlags.has(
                        flags,
                        ScriptVerifyFlags.MINIMALDATA
                )
        );

        assertFalse(
                ScriptVerifyFlags.has(
                        flags,
                        ScriptVerifyFlags.CLEANSTACK
                )
        );

        assertFalse(
                ScriptVerifyFlags.has(
                        flags,
                        ScriptVerifyFlags.NULLFAIL
                )
        );

        assertFalse(
                ScriptVerifyFlags.has(
                        flags,
                        ScriptVerifyFlags.LOW_S
                )
        );

        assertFalse(
                ScriptVerifyFlags.has(
                        flags,
                        ScriptVerifyFlags.CONST_SCRIPTCODE
                )
        );
    }

    @Test
    void standardMustEqualMandatoryPlusPolicyFlags() {

        assertEquals(
                StandardScriptVerifyFlags.MANDATORY
                        | StandardScriptVerifyFlags.STANDARD_NOT_MANDATORY,
                StandardScriptVerifyFlags.STANDARD
        );
    }

    @Test
    void mandatoryAndAdditionalPolicyFlagsMustNotOverlapUnexpectedly() {

        assertEquals(
                0,
                StandardScriptVerifyFlags.MANDATORY
                        & StandardScriptVerifyFlags.STANDARD_NOT_MANDATORY
        );
    }
}