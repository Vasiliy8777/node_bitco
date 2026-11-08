package ru.bitcoin.node.p2p.sync;

/** Bitcoin Core 32.1 SendMessages initial-headers policy (microseconds/seconds). */
public final class HeadersSyncTimeoutPolicy {
    public static final long RECENT_SECONDS = 24 * 60 * 60;
    public static final long BASE_MICROS = 15 * 60 * 1_000_000L;
    public enum Action { NONE, DISCONNECT, RESET_NOBAN }
    private HeadersSyncTimeoutPolicy() {}

    public static long initialDeadline(long nowMicros, long nowSeconds, long bestHeaderSeconds, long spacingSeconds) {
        if (spacingSeconds <= 0) throw new IllegalArgumentException("Nonpositive target spacing");
        // Scale before dividing: Core retains the fractional-header microseconds.
        return Math.addExact(nowMicros, Math.addExact(BASE_MICROS,
                Math.multiplyExact(1000L, Math.subtractExact(nowSeconds, bestHeaderSeconds)) / spacingSeconds));
    }
    public static boolean preferred(boolean inbound, boolean noBan, boolean addressFetch, boolean canServeBlocks) {
        return (!inbound || noBan) && !addressFetch && canServeBlocks;
    }
    public static boolean mayStart(boolean canServeBlocks, boolean loadingBlocks, boolean preferred,
                                    boolean addressFetch, int preferredCount, boolean blocksInFlight,
                                    int syncStarted, long nowSeconds, long bestHeaderSeconds) {
        boolean source = mayFetch(canServeBlocks, preferred, addressFetch, preferredCount, blocksInFlight);
        return canServeBlocks && !loadingBlocks && ((syncStarted == 0 && source)
                || bestHeaderSeconds > nowSeconds - RECENT_SECONDS);
    }
    public static boolean mayFetch(boolean canServeBlocks, boolean preferred, boolean addressFetch,
                                   int preferredCount, boolean blocksInFlight) {
        return preferred || (canServeBlocks && !addressFetch && (preferredCount == 0 || !blocksInFlight));
    }

    public static final class State {
        private long deadline;
        private long budget;
        private boolean started;
        public void start(long nowMicros, long nowSeconds, long bestHeaderSeconds, long spacingSeconds) {
            deadline = initialDeadline(nowMicros, nowSeconds, bestHeaderSeconds, spacingSeconds);
            budget = deadline - nowMicros; started = true;
        }
        public boolean started() { return started; }
        public long deadlineMicros() { return deadline; }
        public long budgetMicros() { return budget; }
        public void reset() { started = false; deadline = 0; }
        public void observe(long nowSeconds, long bestHeaderSeconds) {
            if (started && bestHeaderSeconds > nowSeconds - RECENT_SECONDS) deadline = Long.MAX_VALUE;
        }
        public Action evaluate(long nowMicros, long nowSeconds, long bestHeaderSeconds, int syncStarted,
                               int preferredCount, boolean selfPreferred, boolean noBan) {
            observe(nowSeconds, bestHeaderSeconds);
            if (!started || deadline == Long.MAX_VALUE || nowMicros <= deadline || syncStarted != 1
                    || preferredCount - (selfPreferred ? 1 : 0) < 1) return Action.NONE;
            if (noBan) { reset(); return Action.RESET_NOBAN; }
            return Action.DISCONNECT;
        }
    }
}
