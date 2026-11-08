# Core RPC metadata

`core32-wait-methods.json` contains the three implemented chain-wait methods'
OpenRPC records and help strings exported from Bitcoin Core 32.1 (version
320100). Source: Bitcoin Core `src/rpc/blockchain.cpp`, distributed under its
[MIT license](https://github.com/bitcoin/bitcoin/blob/v32.1/COPYING).
The license notice is included in `bitcoin-core-COPYING`.

`BitcoinCore32CompatibilityTest` compares these records and help texts against
the native reference binary, in addition to checking the actual RPC results.
Other method schemas are not represented by this file.
