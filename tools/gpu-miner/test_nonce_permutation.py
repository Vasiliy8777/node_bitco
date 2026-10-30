import unittest
import random
from nonce_permutation import extra_nonce, inverse32, mix32, MASK32


class NoncePermutationTest(unittest.TestCase):
    def test_inverse_and_unsigned_boundaries(self):
        rng = random.Random(12345)
        for key in (0, 0x80000000, MASK32, 0x12345678):
            for counter in (0, 1, MASK32, 0x80000000, 0x7fffffff):
                self.assertEqual(counter, inverse32(mix32(counter + key), key))
        for _ in range(10000):
            key, counter = rng.getrandbits(32), rng.getrandbits(32)
            self.assertEqual(counter, inverse32(mix32(counter + key), key))

    def test_extra_nonce_is_bijective_for_full_small_domains(self):
        for size in (1, 2):
            limit = 1 << (8 * size)
            values = {extra_nonce(i, size, 0xfedcba9876543210) for i in range(limit)}
            self.assertEqual(limit, len(values))
        for size in (1, 4, 8, 16, 32):
            self.assertEqual(size * 2, len(extra_nonce((1 << (size * 8)) - 1, size, 1234)))
            with self.assertRaises(ValueError):
                extra_nonce(1 << (size * 8), size, 1234)
        self.assertEqual('e220a8397b1dcdaf', extra_nonce(0, 8, 0x9e3779b97f4a7c15))


if __name__ == '__main__':
    unittest.main()
