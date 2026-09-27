package ru.bitcoin.node.consensus.deployment;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentManagerTest {
    private record Node(int height, long mtp, int version, Node parent, String key) { }
    private static final DeploymentManager.ChainAccess<Node, String> ACCESS = new DeploymentManager.ChainAccess<>() {
        @Override public long height(Node block) { return block.height(); }
        @Override public String key(Node block) { return block.key(); }
        @Override public Node parent(Node block) { return block.parent(); }
        @Override public long medianTimePast(Node block) { return block.mtp(); }
        @Override public int version(Node block) { return block.version(); }
    };

    @Test void standardBip9TimeoutPrecedesThreshold() {
        Deployment deployment = new Deployment("test", 1, 100, 200, 3, 4);
        var nodes = chain(12, 200, 0x20000002);
        var manager = new DeploymentManager<>(deployment, ACCESS);
        assertEquals(DeploymentState.FAILED, manager.stateForNextBlock(nodes.get(7)));
    }

    @Test void speedyTrialThresholdPrecedesTimeoutAfterStarted() {
        Deployment deployment = new Deployment("test", 1, 100, 200, 3, 4, 0,
                Deployment.TransitionPolicy.SPEEDY_TRIAL);
        List<Node> nodes = new ArrayList<>();
        Node parent = null;
        for (int height = 0; height < 12; height++) {
            long mtp = height < 4 ? 100 : 200;
            int version = height >= 4 && height < 8 ? 0x20000002 : 0x20000000;
            parent = new Node(height, mtp, version, parent, "n" + height);
            nodes.add(parent);
        }
        var manager = new DeploymentManager<>(deployment, ACCESS);
        assertEquals(DeploymentState.LOCKED_IN, manager.stateForNextBlock(nodes.get(7)));
        assertEquals(DeploymentState.ACTIVE, manager.stateForNextBlock(nodes.get(11)));
    }

    @Test void speedyTrialDefinedDoesNotFailMerelyBecauseTimeoutPassed() {
        Deployment deployment = new Deployment("test", 1, 100, 200, 4, 4, 0,
                Deployment.TransitionPolicy.SPEEDY_TRIAL);
        var nodes = chain(4, 250, 0x20000000);
        var manager = new DeploymentManager<>(deployment, ACCESS);
        assertEquals(DeploymentState.STARTED, manager.stateForNextBlock(nodes.get(3)));
    }

    @Test void minimumActivationHeightKeepsDeploymentLockedIn() {
        Deployment deployment = new Deployment("test", 1, 100, 1000, 3, 4, 16,
                Deployment.TransitionPolicy.SPEEDY_TRIAL);
        var nodes = chain(16, 100, 0x20000002);
        var manager = new DeploymentManager<>(deployment, ACCESS);
        assertEquals(DeploymentState.LOCKED_IN, manager.stateForNextBlock(nodes.get(11)));
        assertEquals(DeploymentState.ACTIVE, manager.stateForNextBlock(nodes.get(15)));
    }

    @Test void stateIsBranchAwareAndCacheIsBounded() {
        Deployment deployment = new Deployment("test", 1, 100, 1000, 3, 4);
        var manager = new DeploymentManager<>(deployment, ACCESS, 2);
        var signalling = chain(16, 100, 0x20000002);
        var silent = chainWithPrefix("b", 16, 100, 0x20000000);
        assertEquals(DeploymentState.ACTIVE, manager.stateForNextBlock(signalling.get(15)));
        assertEquals(DeploymentState.STARTED, manager.stateForNextBlock(silent.get(15)));
        assertTrue(manager.cachedStates() <= 2);
    }

    @Test void versionBitsRequiresBip9TopBits() {
        Deployment deployment = new Deployment("test", 2, 0, 1000, 1, 1);
        assertTrue(VersionBits.signals(0x20000004, deployment));
        assertFalse(VersionBits.signals(0x00000004, deployment));
        assertEquals(0x20000004, VersionBits.signal(0, deployment));
    }

    private static List<Node> chain(int count, long mtp, int version) {
        return chainWithPrefix("a", count, mtp, version);
    }

    private static List<Node> chainWithPrefix(String prefix, int count, long mtp, int version) {
        List<Node> nodes = new ArrayList<>();
        Node parent = null;
        for (int height = 0; height < count; height++) {
            parent = new Node(height, mtp, version, parent, prefix + height);
            nodes.add(parent);
        }
        return nodes;
    }
}
