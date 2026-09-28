# Mempool exactness stage

This stage closes the Bitcoin Core v31 mempool-policy compatibility work already built across the mempool, storage, app RPC and relay layers.

The gate covers the full reactor plus contracts for cluster count/size limits and cluster linearization, feerate-diagram RBF, incremental relay fee, TRUC v3 topology and sibling eviction, ephemeral dust, rolling minimum fee, package CPFP and constrained package RBF, non-mutating testmempoolaccept semantics, prioritisetransaction modified fees, unbroadcast tracking, graph/cluster RPCs, and persistent entries/fee deltas.

The closing correction separates Core's two package validation modes: multi-transaction testmempoolaccept is a non-replacing probe, while submitpackage can use constrained package RBF. It also restores the Core v31 testmempoolaccept argument surface and computes effective package feerate from modified fees.
