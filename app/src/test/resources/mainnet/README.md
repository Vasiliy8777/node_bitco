# Mainnet blocks 1–3

Raw Bitcoin wire blocks retrieved from Blockstream Esplora on 2026-09-16:
https://blockstream.info/api/block-height/1 (and heights 2, 3), then
https://blockstream.info/api/block/{hash}/raw
`blocks.txt` pins the height and block hash. Tests run offline and verify the hashes.
These are public blockchain records. They exercise real block import and clean
restart, not crash recovery, historical activation boundaries or comparison with
a separately running Bitcoin Core instance.
