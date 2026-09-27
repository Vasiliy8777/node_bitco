package ru.bitcoin.node.consensus.deployment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Branch-aware BIP9/Speedy-Trial state machine.
 *
 * <p>The returned state is the state that applies to the block after {@code parent}.
 * Cache entries are keyed by the final block of a completed signalling period, so
 * reorganizations on another branch cannot reuse a state from an unrelated ancestry.</p>
 */
public final class DeploymentManager<T, K> {
    private static final int DEFAULT_CACHE_LIMIT = 4096;

    public interface ChainAccess<T, K> {
        long height(T block);
        K key(T block);
        T parent(T block);
        long medianTimePast(T block);
        int version(T block);
    }

    private final Deployment deployment;
    private final ChainAccess<T, K> chain;
    private final int cacheLimit;
    private final LinkedHashMap<K, DeploymentState> cache = new LinkedHashMap<>();

    public DeploymentManager(Deployment deployment, ChainAccess<T, K> chain) {
        this(deployment, chain, DEFAULT_CACHE_LIMIT);
    }

    public DeploymentManager(Deployment deployment, ChainAccess<T, K> chain, int cacheLimit) {
        this.deployment = Objects.requireNonNull(deployment, "deployment");
        this.chain = Objects.requireNonNull(chain, "chain");
        if (cacheLimit <= 0) throw new IllegalArgumentException("cacheLimit must be positive");
        this.cacheLimit = cacheLimit;
    }

    public synchronized DeploymentState stateForNextBlock(T parent) {
        Objects.requireNonNull(parent, "parent");
        T boundary = periodBoundary(parent);
        if (boundary == null) return DeploymentState.DEFINED;

        DeploymentState cached = cache.get(chain.key(boundary));
        if (cached != null) return cached;

        List<T> pending = new ArrayList<>();
        T cursor = boundary;
        DeploymentState state = DeploymentState.DEFINED;
        while (cursor != null) {
            cached = cache.get(chain.key(cursor));
            if (cached != null) {
                state = cached;
                break;
            }
            pending.add(cursor);
            if (chain.height(cursor) + 1 < deployment.window()) break;
            cursor = ancestor(cursor, deployment.window());
        }

        for (int i = pending.size() - 1; i >= 0; i--) {
            T completedPeriod = pending.get(i);
            state = transition(state, completedPeriod);
            putCache(chain.key(completedPeriod), state);
        }
        return state;
    }

    public boolean shouldSignal(T parent) {
        DeploymentState state = stateForNextBlock(parent);
        return state == DeploymentState.STARTED || state == DeploymentState.LOCKED_IN;
    }

    public int versionForNextBlock(T parent, int baseVersion) {
        return shouldSignal(parent) ? VersionBits.signal(baseVersion, deployment) : baseVersion;
    }

    private DeploymentState transition(DeploymentState previous, T boundary) {
        long mtp = chain.medianTimePast(boundary);
        return switch (previous) {
            case DEFINED -> {
                if (deployment.transitionPolicy() == Deployment.TransitionPolicy.BIP9
                        && mtp >= deployment.timeout()) yield DeploymentState.FAILED;
                if (mtp >= deployment.startTime()) yield DeploymentState.STARTED;
                yield DeploymentState.DEFINED;
            }
            case STARTED -> startedTransition(boundary, mtp);
            case LOCKED_IN -> chain.height(boundary) + 1 >= deployment.minActivationHeight()
                    ? DeploymentState.ACTIVE : DeploymentState.LOCKED_IN;
            case ACTIVE -> DeploymentState.ACTIVE;
            case FAILED -> DeploymentState.FAILED;
        };
    }

    private DeploymentState startedTransition(T boundary, long mtp) {
        if (deployment.transitionPolicy() == Deployment.TransitionPolicy.BIP9
                && mtp >= deployment.timeout()) return DeploymentState.FAILED;
        if (signalCount(boundary) >= deployment.threshold()) return DeploymentState.LOCKED_IN;
        if (mtp >= deployment.timeout()) return DeploymentState.FAILED;
        return DeploymentState.STARTED;
    }

    private int signalCount(T boundary) {
        int count = 0;
        T current = boundary;
        for (int i = 0; i < deployment.window(); i++) {
            if (current == null) throw new IllegalStateException("Missing deployment signalling ancestor");
            if (VersionBits.signals(chain.version(current), deployment)) count++;
            if (i + 1 < deployment.window()) current = chain.parent(current);
        }
        return count;
    }

    private T periodBoundary(T parent) {
        if (chain.height(parent) + 1 < deployment.window()) return null;
        long offset = (chain.height(parent) + 1) % deployment.window();
        T current = parent;
        for (long i = 0; i < offset; i++) current = requiredParent(current);
        return current;
    }

    private T ancestor(T block, int distance) {
        T current = block;
        for (int i = 0; i < distance; i++) {
            if (chain.height(current) == 0) return null;
            current = requiredParent(current);
        }
        return current;
    }

    private T requiredParent(T block) {
        T parent = chain.parent(block);
        if (parent == null) throw new IllegalStateException("Missing ancestor while evaluating deployment state");
        if (chain.height(parent) != chain.height(block) - 1)
            throw new IllegalStateException("Invalid ancestry while evaluating deployment state");
        return parent;
    }

    private void putCache(K key, DeploymentState state) {
        cache.put(key, state);
        while (cache.size() > cacheLimit) {
            var iterator = cache.entrySet().iterator();
            iterator.next();
            iterator.remove();
        }
    }

    synchronized int cachedStates() { return cache.size(); }
}
