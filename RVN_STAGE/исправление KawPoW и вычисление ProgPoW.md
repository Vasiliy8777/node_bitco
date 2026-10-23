# Ravencoin consensus corrections / ProgPoW computation

Based on exact ZIP `node_bitco-main - 2026-10-23T232817.476.zip`.

**Important correctness defects found by comparison with C++ KawPoW reference:**
- dataset nodes need **512** parents, not 256
- dataset node FNV is **FNV-1** (`(a xor b)*prime`), not FNV-1a
- ProgPoW DAG access uses **256-byte** items (4 x 64-byte nodes), not 128-byte items
- ProgPoW math has **11** operations, not 8

This patch fixes those defects and implements the height/3 period RNG, lane initialization,
16 KiB L1 construction and 64-round ProgPoW mix calculation with 11 cache accesses,
18 math operations and 4 DAG words per lane/round.

**Not yet consensus-ready:** final Keccak-f800 seed/final hash, full reference vectors,
header preimage wiring, and PoW router integration remain to be implemented and verified.
Do not enable this engine as consensus verifier until official vectors pass.

Reference: https://github.com/DNS/kawpow/blob/master/lib/kawpow/progpow.cpp
Reference: https://github.com/DNS/kawpow/blob/master/lib/kawpow/kawpow.cpp

Run `mvn -pl rvn-pow -am test` and `mvn -pl rvn-app -am test`.
