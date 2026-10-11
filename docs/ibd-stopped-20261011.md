# IBD stopped worker, 2026-10-11

At 09:04–09:10 Bangkok time PID 25404 remained alive, but synchronization had
stopped completely. Last PERF/progress messages are at 06:04:51, height 775188.
Live getblockchaininfo confirms blocks=775188, headers=970838 and IBD=true.
Two independent thread dumps contain neither bitcoin-node-lifecycle nor
ibd-download-completions. Peer reconnection and idle Stratum/GPU timers remain.
K: reports zero disk activity; this is no longer the earlier HDD read slowdown.

JVM counters report 534 full collections, 194.72 seconds total full-GC pauses.
Last full-GC timestamps align approximately with the time progress stopped.
Before stopping, processing and maintenance costs escalated and about 991 parsed
blocks were buffered. The Java heap is limited to 4 GiB, separate from the
4 GiB native RocksDB cache and the million-entry Java UTXO cache. After the
worker stopped and its buffers were released, heap usage fell to ~630 MiB.

Memory exhaustion followed by termination of the lifecycle worker is the leading
hypothesis, not a confirmed OutOfMemoryError: the production log has no exception
stack, and the current run console is not exposed by available tools. The node
is not in an active IDE debugger session, so read-only JVM thread/GC counters
were used rather than restarting it under a debugger during diagnosis.

NodeLifecycleService.start and NodeLifecycleRunner.run catch IOException and
RuntimeException, but not Error. An OutOfMemoryError can escape without invoking
the lifecycle failure state/logging, leaving the process and peer pool alive.
This matches the observed absent worker and missing FAILED log, although another
uncaught Error cannot be excluded without its stderr stack.

Recommended recovery: reduce the parsed-block download window from the hardcoded
1024 in NodeConfiguration.blockSyncCoordinator to a configurable 256 initially;
bound buffered decoded blocks by estimated memory as well as count for a durable
solution. Retain Java heap headroom for validation and coin caches. Add fatal
worker-error reporting and a reliable FAILED state; do not automatically retry
fatal VM errors indefinitely. Capture OOM diagnostics on the next run, then
gracefully restart and verify active height advancement, buffer size and GC.
A restart alone may temporarily restore progress while repeating the same risk.
Moving chainstate to SSD addresses disk latency but does not directly fix a dead
worker or an excessive heap-resident block buffer.

No production edits or restart were made for this analysis.
Thread dumps: target/ibd-stopped-20261011-threads.txt and the second dump with
the -threads-2 suffix.
