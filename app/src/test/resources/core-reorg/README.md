# Core v31.1.0 reorganization fixture

Starting from ../core-spend at height 102, Core invalidateblock disconnected the
spending block. generateblock raw(51) [] generated alternative blocks 102 and 103,
explicitly excluding mempool transactions. Raw blocks and hashes are pinned here.
restored-utxo.json and removed-utxo.json record gettxout with include_mempool=false;
empty RPC output in removed-utxo.json means the disconnected spend output is absent.
chain.json records the independent Core tip. The isolated offline Core was stopped.
BitcoinCoreReorgTest imports the original chain and then these blocks through the
normal BlockProcessor. Our node selects the longer branch without invalidateblock.
It checks restoration/removal of affected coins and a clean restart, not a full
UTXO-set digest or abrupt crash recovery.

utxos.txt is the complete scantxoutset start ["raw(51)"] result at height 103,
projected as txid|vout|satoshis|height|script hex. Its 103 entries were checked against
gettxoutsetinfo.txouts before saving; utxos-info.json records that independent total.
BitcoinCoreReorgTest compares every entry and the total stored UTXO count, before
and after restart. All remaining outputs in this particular fixture are coinbases.
