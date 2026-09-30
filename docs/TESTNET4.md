# Testnet4 / BIP94

Select Spring profile `testnet4` (`--spring.profiles.active=testnet4`). The profile
uses a separate `./data/testnet4` directory, P2P port 48333 and RPC port 48332.
`BITCOIN_DATA_DIRECTORY` overrides the directory. Existing `testnet` still means
Testnet3; its database cannot be reused for Testnet4.

Network magic, genesis, buried activation heights, Taproot activation, DNS seeds,
minimum chainwork, assumevalid and the height-90000 AssumeUTXO anchor are pinned to
[Bitcoin Core v30.0 chain parameters](https://github.com/bitcoin/bitcoin/blob/v30.0/src/kernel/chainparams.cpp).
The genesis serialization is tested against the complete
[BIP94 vector](https://github.com/bitcoin/bips/blob/master/bip-0094.mediawiki).

BIP94 uses the first block's difficulty when retargeting and requires the first
block of a period to have `nTime >= parent.nTime - 600`, in addition to MTP.
The 20-minute minimum-difficulty exception remains strict (`> 1200` seconds) and
does not apply at retarget boundaries. Header validation and block proposal
validation share these checks. Mining templates, GBT `mintime` and Stratum use
the same lower bound. Stratum disables time rolling on networks where it could
change required difficulty, including Testnet4.

Tests cover heights 2015/2016/2017, the -601/-600 boundary, MTP, retarget base bits,
minimum-difficulty recovery, unchanged Mainnet/Testnet3 rules, Taproot, startup
and database network isolation. A cheap-PoW shortened-interval fixture also tests
template creation, proposal, block admission, RPC and Stratum at a boundary.
With `-Dbitcoin.core.binary=...`, `BitcoinCoreMiningRoundTripTest` compares the
full genesis with an isolated Core Testnet4 process, without public-network access.

These checks close the implementation gap; they do not establish a full public
Testnet4 history replay or sustained ASIC operation.
