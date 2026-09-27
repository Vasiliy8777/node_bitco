package ru.bitcoin.node.mining;

import ru.bitcoin.node.consensus.deployment.DeploymentState;
import ru.bitcoin.node.consensus.deployment.VersionBits;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** BIP9 getblocktemplate projection for a coherent set of deployment states. */
public final class MiningVersionBits {
    private MiningVersionBits() { }

    public record DeploymentView(String name, int bit, DeploymentState state, boolean gbtForce) {
        public DeploymentView {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Deployment name must not be blank");
            if (bit < 0 || bit > 28) throw new IllegalArgumentException("Version-bits bit must be in [0,28]");
            Objects.requireNonNull(state, "state");
        }

        public String gbtName() { return gbtForce ? name : "!" + name; }
    }

    public static int preferredVersion(int baseVersion, List<DeploymentView> deployments) {
        return preferredVersion(baseVersion, deployments, null);
    }

    /**
     * Preferred version for a GBT client. A null supported-rules set means local/Stratum mining
     * and keeps the node's preferred signalling for every pending deployment.
     */
    public static int preferredVersion(
            int baseVersion, List<DeploymentView> deployments, java.util.Set<String> supportedRules) {
        int version = baseVersion;
        for (DeploymentView deployment : List.copyOf(deployments)) {
            if (deployment.state() == DeploymentState.STARTED
                    || deployment.state() == DeploymentState.LOCKED_IN) {
                boolean supported = supportedRules == null || supportedRules.contains(deployment.name());
                if (deployment.gbtForce() || supported) {
                    version = (version & ~VersionBits.TOP_MASK) | VersionBits.TOP_BITS | (1 << deployment.bit());
                }
            }
        }
        return version;
    }

    public static List<String> unsupportedActiveRules(
            List<DeploymentView> deployments, java.util.Set<String> supportedRules) {
        Objects.requireNonNull(supportedRules, "supportedRules");
        var unsupported = new ArrayList<String>();
        for (DeploymentView deployment : List.copyOf(deployments)) {
            if (deployment.state() == DeploymentState.ACTIVE
                    && !deployment.gbtForce()
                    && !supportedRules.contains(deployment.name())) {
                unsupported.add(deployment.name());
            }
        }
        return List.copyOf(unsupported);
    }

    /** Active version-bits rules, in stable deployment order. */
    public static List<String> activeRules(List<DeploymentView> deployments) {
        var rules = new ArrayList<String>();
        for (DeploymentView deployment : List.copyOf(deployments)) {
            if (deployment.state() == DeploymentState.ACTIVE) rules.add(deployment.gbtName());
        }
        return List.copyOf(rules);
    }

    /** STARTED/LOCKED_IN deployments exposed by BIP9 as name -> bit. */
    public static Map<String, Integer> available(List<DeploymentView> deployments) {
        var available = new LinkedHashMap<String, Integer>();
        for (DeploymentView deployment : List.copyOf(deployments)) {
            if (deployment.state() == DeploymentState.STARTED
                    || deployment.state() == DeploymentState.LOCKED_IN) {
                Integer previous = available.put(deployment.gbtName(), deployment.bit());
                if (previous != null) throw new IllegalArgumentException("Duplicate deployment name: " + deployment.gbtName());
            }
        }
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(available));
    }
}
