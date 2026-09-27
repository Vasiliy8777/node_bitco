package ru.bitcoin.node.mining;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.consensus.deployment.DeploymentState;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MiningVersionBitsTest {
    @Test
    void projectsEveryBip9StateIntoTemplateSemantics() {
        var defined = new MiningVersionBits.DeploymentView("defined", 1, DeploymentState.DEFINED, true);
        var started = new MiningVersionBits.DeploymentView("started", 2, DeploymentState.STARTED, true);
        var locked = new MiningVersionBits.DeploymentView("locked", 3, DeploymentState.LOCKED_IN, false);
        var active = new MiningVersionBits.DeploymentView("active", 4, DeploymentState.ACTIVE, true);
        var failed = new MiningVersionBits.DeploymentView("failed", 5, DeploymentState.FAILED, true);
        var deployments = List.of(defined, started, locked, active, failed);

        assertEquals(0x2000000c, MiningVersionBits.preferredVersion(0x20000000, deployments));
        assertEquals(List.of("active"), MiningVersionBits.activeRules(deployments));
        assertEquals(Map.of("started", 2, "!locked", 3), MiningVersionBits.available(deployments));
    }

    @Test
    void activeMandatoryUnderstandingRuleUsesBangPrefix() {
        var deployment = new MiningVersionBits.DeploymentView("structural", 7, DeploymentState.ACTIVE, false);
        assertEquals(List.of("!structural"), MiningVersionBits.activeRules(List.of(deployment)));
        assertEquals(Map.of(), MiningVersionBits.available(List.of(deployment)));
        assertEquals(0x20000000, MiningVersionBits.preferredVersion(0x20000000, List.of(deployment)));
        assertEquals(List.of("structural"),
                MiningVersionBits.unsupportedActiveRules(List.of(deployment), java.util.Set.of()));
        assertEquals(List.of(),
                MiningVersionBits.unsupportedActiveRules(List.of(deployment), java.util.Set.of("structural")));
    }

    @Test
    void nonForcedPendingBitIsClearedForClientThatDidNotDeclareSupport() {
        var deployment = new MiningVersionBits.DeploymentView("pending", 6, DeploymentState.STARTED, false);
        assertEquals(0x20000000, MiningVersionBits.preferredVersion(
                0x20000000, List.of(deployment), java.util.Set.of()));
        assertEquals(0x20000040, MiningVersionBits.preferredVersion(
                0x20000000, List.of(deployment), java.util.Set.of("pending")));
    }
}
