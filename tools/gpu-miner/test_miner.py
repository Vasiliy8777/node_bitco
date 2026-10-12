import struct
import unittest
from unittest.mock import patch
from types import SimpleNamespace
import ctypes
import miner
from decimal import Decimal
from miner import Connection, DIFF1, MAX_TARGET, compact_target, difficulty_target, header_for
from cuda_backend import sha256d, CudaHasher


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


class WorkCacheTest(unittest.TestCase):
    def job(self):
        return ['job', '00'*32, '0102', '0506', [], '20000000', '1c00ffff', '65010203', True]

    def run_mine(self, batch=2097152, events=None):
        connection = SimpleNamespace(authorized=True, extra1='03', extra2_size=4,
            job=self.job(), job_difficulty=Decimal(1), generation=1, pending={}, accepted=0, blocks=0,
            pump=lambda timeout=0: None, send=lambda *args: None, close=lambda: None)
        calls = []
        def search(header, target, nonce, count):
            if len(calls) == 3:
                raise StopIteration('End of controlled mining run')
            calls.append((header, target, nonce, count))
            if events and len(calls) in events:
                events[len(calls)](connection)
            return [], False
        gpu = SimpleNamespace(search=search)
        args = SimpleNamespace(host='unused', port=0, worker='test', max_blocks=0,
                               block_only=False, batch_size=batch)
        with patch.object(miner, 'Connection', return_value=connection), \
             patch.object(miner, 'header_for', wraps=header_for) as headers, \
             patch.object(miner, 'difficulty_target', wraps=difficulty_target) as targets:
            with self.assertRaises(StopIteration):
                miner.mine(gpu, args)
            return calls, headers.call_count, targets.call_count

    def test_unchanged_work_prepares_once_and_continues_nonce(self):
        calls, headers, targets = self.run_mine()
        self.assertEqual((1, 1), (headers, targets))
        self.assertEqual([0, 2097152, 4194304], [c[2] for c in calls])
        self.assertTrue(all(c[0] == header_for(self.job(), '03', '00000000') for c in calls))

    def test_nonce_rollover_changes_coinbase_merkle_and_header(self):
        calls, headers, targets = self.run_mine(batch=1 << 31)
        self.assertEqual((2, 1), (headers, targets))
        self.assertEqual([0, 1 << 31, 0], [c[2] for c in calls])
        self.assertNotEqual(calls[0][0], calls[2][0])
        self.assertEqual(header_for(self.job(), '03', '00000001'), calls[2][0])

    def test_new_time_version_bits_parent_and_merkle_rebuild_work(self):
        for field, value in [(1, '01'*32), (4, ['01'*32]), (5, '20000001'),
                             (6, '1b00ffff'), (7, '65010204')]:
            with self.subTest(field=field):
                def notify(connection):
                    connection.job = list(connection.job)
                    connection.job[field] = value
                    connection.generation += 1
                calls, headers, targets = self.run_mine(events={2: notify})
                self.assertEqual((2, 2), (headers, targets))
                self.assertEqual(0, calls[2][2])
                new_job = self.job()
                new_job[field] = value
                self.assertEqual(header_for(new_job, '03', '00000000'), calls[2][0])

    def test_new_job_id_and_difficulty_reuse_header_but_update_target(self):
        def notify(connection):
            connection.job = list(connection.job)
            connection.job[0] = 'new-wire-id'
            connection.job_difficulty = Decimal(2)
            connection.generation += 1
        calls, headers, targets = self.run_mine(events={2: notify})
        self.assertEqual((1, 2), (headers, targets))
        self.assertEqual(calls[0][0], calls[2][0])
        self.assertEqual(DIFF1 // 2, calls[2][1])
        self.assertNotEqual(0, calls[2][2])

    def test_changed_extranonce_prefix_and_size_rebuild_header(self):
        def notify(connection):
            connection.extra1 = '0304'
            connection.extra2_size = 8
            connection.generation += 1
        calls, headers, targets = self.run_mine(events={2: notify})
        self.assertEqual(2, headers)
        self.assertEqual(header_for(self.job(), '0304', '00'*8), calls[2][0])

    def test_cuda_uploads_target_only_when_it_changes(self):
        gpu = CudaHasher.__new__(CudaHasher)
        header = bytes(80)
        gpu.prepared = header[:76]
        gpu.prepared_target = None
        gpu.target = ctypes.c_uint64(123)
        uploads = []
        gpu._call = lambda name, address, data, size: uploads.append((name, list(data), size))
        gpu._prepare(header, 17)
        gpu._prepare(header, 17)
        gpu._prepare(header)
        gpu._prepare(header, 17)
        self.assertEqual(1, len(uploads))
        self.assertEqual([0]*7+[17], uploads[0][1])
        gpu._prepare(header, 23)
        self.assertEqual(2, len(uploads))
        self.assertEqual([0]*7+[23], uploads[-1][1])


if __name__ == '__main__':
    unittest.main()
