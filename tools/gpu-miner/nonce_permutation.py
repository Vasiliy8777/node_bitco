"""Bijective scheduling permutations, not cryptographic random generators."""
MASK32 = (1 << 32) - 1


def mix32(x):
    x &= MASK32
    x ^= x >> 16
    x = x * 0x7feb352d & MASK32
    x ^= x >> 15
    x = x * 0x846ca68b & MASK32
    return x ^ (x >> 16)


def inverse32(nonce, key):
    x = nonce & MASK32
    x ^= x >> 16
    x = x * 0x43021123 & MASK32
    x ^= (x >> 15) ^ (x >> 30)
    x = x * 0x1d69e2a5 & MASK32
    return ((x ^ (x >> 16)) - key) & MASK32


def extra_nonce(counter, size, key):
    """Permutation of exactly the negotiated 8..256-bit space; never truncate a wider permutation."""
    if not 1 <= size <= 32 or not 0 <= counter < 1 << (8 * size):
        raise ValueError('ExtraNonce counter/size bounds')
    bits = 8 * size
    mask = (1 << bits) - 1
    x = (counter + key) & mask
    # The default 64-bit case is the SplitMix64 finalizer, applied to a counter.
    shifts = (30, 27, 31) if bits == 64 else (max(1, bits * 30 // 64), max(1, bits * 27 // 64), max(1, bits * 31 // 64))
    x ^= x >> shifts[0]
    x = x * 0xbf58476d1ce4e5b9 & mask
    x ^= x >> shifts[1]
    x = x * 0x94d049bb133111eb & mask
    x ^= x >> shifts[2]
    return x.to_bytes(size, 'big').hex()
