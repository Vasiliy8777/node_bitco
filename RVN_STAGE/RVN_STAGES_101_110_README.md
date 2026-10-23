# RVN stages 101–110: KawPoW Ethash-family light cache and DAG node generation

Based on exact contents of `node_bitco-main - 2026-10-23T225523.632.zip`.

Adds:
- Prime-adjusted epoch cache and dataset byte sizes (64-byte cache rows, 128-byte DAG items)
- Keccak-256 epoch seed chain
- Three-round light cache generation (Keccak-512)
- 256-parent FNV-based 64-byte dataset node computation
- 128-byte DAG item from two consecutive dataset nodes
- Fast unit tests with reduced deterministic cache fixtures; full epoch cache is ~16 MiB

**Still incomplete:** KawPoW ProgPoW execution, DAG accesses, final proof
and consensus vector verification. This is not a usable production PoW
validator. X16R still lacks 14 primitives and X16RV2 lacks Tiger192.

Unzip into repository root; run:
`mvn -pl rvn-pow -am test`
`mvn -pl rvn-app -am test`
