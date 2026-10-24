package ru.bitcoin.node.rvn.app.chain;

import java.io.IOException;
import ru.bitcoin.node.rvn.storage.*;
import ru.bitcoin.node.rvn.chain.assets.RvnAssetMetadataRules.Activation;

/** Pinned mainnet RIP5 / transfer-size / value / coinbase activation state.
 * Derived per branch; never sticky global flags or guessed heights. */
public final class RvnAssetDeploymentContext {
    private final RvnAssetsDeployment messaging = new RvnAssetsDeployment(2016, 1714, 1578920400L, 1610542800L, 7);
    private final RvnAssetsDeployment transfer = new RvnAssetsDeployment(2016, 1714, 1588788000L, 1620324000L, 8);
    private final RvnAssetsDeployment value = new RvnAssetsDeployment(2016, 1411, 1593453600L, 1624989600L, 9);
    private final RvnAssetsDeployment coinbase = new RvnAssetsDeployment(2016, 1411, 1597341600L, 1628877600L, 10);
    public record Context(Activation activation, boolean transferSizeFix, boolean enforcedReissueValues, boolean coinbaseAssetCheck) {}
    public Context forNext(RvnStoredHeader parent, RvnHeaderLookup branch) throws IOException {
        boolean rip5 = messaging.activeForNext(parent, branch);
        var values = value.stateForNext(parent, branch);
        return new Context(new Activation(true, rip5, rip5), transfer.activeForNext(parent, branch),
                values == RvnAssetsDeployment.State.LOCKED_IN || values == RvnAssetsDeployment.State.ACTIVE,
                coinbase.activeForNext(parent, branch));
    }
}
