package ru.bitcoin.node.stratum.job;

import java.time.Duration;
import java.util.*;

/** Bounded job history. Changes to the parent invalidate every previous job. */
public final class MiningJobManager {
    private final Map<String, MiningJob> jobs = new LinkedHashMap<>();
    private final String epoch = new ExtraNonceManager().next();
    private long sequence;
    private MiningJob latest;

    public synchronized MiningJob update(MiningWork work) {
        if (latest != null && latest.work().revision() == work.revision()
                && latest.work().block().header().equals(work.block().header())) return latest;
        if (latest == null || !latest.work().block().header().previousBlockHash().equals(work.block().header().previousBlockHash()))
            jobs.clear();
        latest = new MiningJob(epoch + Long.toHexString(++sequence), work);
        jobs.put(latest.id(), latest);
        while (jobs.size() > 8) jobs.remove(jobs.keySet().iterator().next());
        return latest;
    }

    public synchronized Optional<MiningJob> find(String id) {
        var job = jobs.get(id);
        if (job == null || System.nanoTime() - job.createdNanos() > Duration.ofMinutes(2).toNanos()) return Optional.empty();
        return Optional.of(job);
    }

    public synchronized void clear() { jobs.clear(); latest = null; }
}
