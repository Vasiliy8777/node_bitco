package ru.bitcoin.node.consensus.transaction;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the external Bitcoin Core differential certification gate against accidental weakening.
 *
 * The live gate is intentionally split into a reusable RPC/state library and the scenario runner,
 * so the contract validates their composed contents rather than requiring every RPC method name
 * to be duplicated in the runner itself.
 */
class ConsensusDifferentialStageContractTest {

    @Test
    void liveCertificationContainsAllRequiredConsensusGates() throws Exception {
        String library = readTool("consensus-differential-lib.ps1");
        String certification = readTool("consensus-certify-core31.ps1");
        String composedGate = library + "\n" + certification;

        for (String required : new String[]{
                "Assert-Core31",
                "getnetworkinfo",
                "getblockchaininfo",
                "getblockhash",
                "getblockheader",
                "getblock",
                "gettxoutsetinfo",
                "hash_serialized_3",
                "submitblock",
                "MutationCount",
                "invalidateblock",
                "reconsiderblock",
                "Assert-StateEqual",
                "finally"
        }) {
            assertTrue(
                    composedGate.contains(required),
                    "Missing live certification gate: " + required
            );
        }

        // The scenario runner must actually compose the shared library and exercise state comparison.
        assertTrue(certification.contains("consensus-differential-lib.ps1"));
        assertTrue(certification.contains("Get-NodeState"));
        assertTrue(certification.contains("Assert-StateEqual"));

        String stageGate = readTool("consensus-stage-gate.ps1");
        assertTrue(stageGate.contains("clean test"));
        assertTrue(stageGate.contains("consensus-certify-core31.ps1"));
    }

    private static String readTool(String name) throws IOException {
        Path moduleRelative = Path.of("..", "tools", name);
        Path reactorRelative = Path.of("tools", name);
        Path path = Files.exists(moduleRelative) ? moduleRelative : reactorRelative;
        assertTrue(
                Files.isRegularFile(path),
                "Missing consensus differential tool: " + path.toAbsolutePath()
        );
        return Files.readString(path);
    }
}
