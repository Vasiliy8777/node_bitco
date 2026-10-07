import struct
import unittest
from decimal import Decimal
from miner import Connection, DIFF1, MAX_TARGET, compact_target, difficulty_target, header_for
from cuda_backend import sha256d


class ProtocolTest(unittest.TestCase):
    def test_exact_difficulty_target(self):
        self.assertEqual(DIFF1, difficulty_target(1))
        self.assertEqual(DIFF1 * 1000, difficulty_target('0.001'))
        self.assertEqual(DIFF1 * 10 // 37, difficulty_target('3.7'))
        self.assertEqual(MAX_TARGET, difficulty_target('1e-30'))
        for value in ['NaN', 'Infinity', 0, -1]:
            with self.assertRaises(ValueError):
                difficulty_target(value)

    def test_compact_target_validation(self):
        self.assertEqual(DIFF1, compact_target('1d00ffff'))
        self.assertEqual(0x7fffff << 232, compact_target('207fffff'))
        for value in ['1d80ffff', 'ff00ffff', '00000000']:
            with self.assertRaises(ValueError):
                compact_target(value)

    def test_wire_header_and_merkle(self):
        previous = bytes(range(32))
        branch = bytes(range(32, 64))
        job = ['job', previous.hex(), '0102', '0506', [branch.hex()], '20000000', '1d00ffff', '65010203', True]
        header = header_for(job, '03', '04')
        self.assertEqual(80, len(header))
        self.assertEqual(struct.pack('<I', 0x20000000), header[:4])
        self.assertEqual(b''.join(previous[i:i+4][::-1] for i in range(0, 32, 4)), header[4:36])
        self.assertEqual(sha256d(sha256d(bytes.fromhex('010203040506')) + branch), header[36:68])
        self.assertEqual(struct.pack('<III', 0x65010203, 0x1d00ffff, 0), header[68:])

    def test_difficulty_applies_to_next_job_and_generations_cancel_old_work(self):
        connection = Connection.__new__(Connection)
        connection.difficulty = Decimal(1)
        connection.generation = 0
        job = ['job', '00'*32, '', '', [], '20000000', '1d00ffff', '65010203', True]
        connection.message({'method': 'mining.notify', 'params': job})
        connection.message({'method': 'mining.set_difficulty', 'params': [Decimal('0.1')]})
        self.assertEqual(1, connection.job_difficulty)
        connection.message({'method': 'mining.notify', 'params': job})
        self.assertEqual(Decimal('0.1'), connection.job_difficulty)
        self.assertEqual(2, connection.generation)
        connection.message({'method': 'mining.set_extranonce', 'params': ['00', 8]})
        self.assertIsNone(connection.job)
        self.assertEqual(3, connection.generation)


if __name__ == '__main__':
    unittest.main()
