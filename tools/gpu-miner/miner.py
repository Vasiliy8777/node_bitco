"""Local solo Stratum V1 CUDA miner. Python standard library + NVIDIA driver/NVRTC."""
import argparse
import json
import os
import select
import socket
import struct
import time
from decimal import Decimal
from cuda_backend import CudaHasher, sha256d

DIFF1 = 0xffff << 208
MAX_TARGET = (1 << 256) - 1


def difficulty_target(difficulty):
    value = Decimal(str(difficulty))
    if not value.is_finite() or value <= 0:
        raise ValueError('Invalid share difficulty')
    numerator, denominator = value.as_integer_ratio()
    return min(MAX_TARGET, DIFF1 * denominator // numerator)


def compact_target(bits):
    value = int(bits, 16)
    size, word = value >> 24, value & 0x7fffff
    if value & 0x800000 or word == 0:
        raise ValueError('Invalid network target')
    target = word >> (8 * (3 - size)) if size <= 3 else word << (8 * (size - 3))
    if not 0 < target <= MAX_TARGET:
        raise ValueError('Network target overflow')
    return target


def header_for(job, extra1, extra2):
    if len(job) != 9:
        raise ValueError('Invalid mining.notify')
    coinbase = bytes.fromhex(job[2] + extra1 + extra2 + job[3])
    root = sha256d(coinbase)
    for branch in job[4]:
        sibling = bytes.fromhex(branch)
        if len(sibling) != 32:
            raise ValueError('Invalid merkle branch')
        root = sha256d(root + sibling)
    previous = bytes.fromhex(job[1])
    if len(previous) != 32:
        raise ValueError('Invalid previous block hash')
    previous = b''.join(previous[i:i+4][::-1] for i in range(0, 32, 4))
    return struct.pack('<I', int(job[5], 16)) + previous + root + struct.pack('<III', int(job[7], 16), int(job[6], 16), 0)


class Connection:
    def __init__(self, host, port):
        self.socket = socket.create_connection((host, port), timeout=10)
        self.buffer = b''
        self.sequence = 0
        self.pending = {}
        self.extra1 = None
        self.extra2_size = None
        self.authorized = False
        self.job = None
        self.difficulty = Decimal(1)
        self.job_difficulty = self.difficulty
        self.generation = 0
        self.accepted = 0
        self.blocks = 0
        self.last_send = time.monotonic()
        self.last_receive = self.last_send

    def send(self, method, params, metadata=None):
        self.sequence += 1
        self.pending[self.sequence] = (method, metadata)
        self.socket.sendall((json.dumps({'id': self.sequence, 'method': method, 'params': params})+'\n').encode())
        self.last_send = time.monotonic()

    def pump(self, timeout=0):
        if select.select([self.socket], [], [], timeout)[0]:
            chunk = self.socket.recv(65536)
            if not chunk:
                raise ConnectionError('Stratum disconnected')
            self.last_receive = time.monotonic()
            self.buffer += chunk
            if len(self.buffer) > 1048576:
                raise ValueError('Stratum message exceeds limit')
            while b'\n' in self.buffer:
                line, self.buffer = self.buffer.split(b'\n', 1)
                if line:
                    self.message(json.loads(line, parse_float=Decimal))
        if time.monotonic() - self.last_send > 30:
            self.send('mining.extranonce.subscribe', [])
        if time.monotonic() - self.last_receive > 90:
            raise ConnectionError('Stratum stopped responding')

    def message(self, message):
        method = message.get('method')
        params = message.get('params', [])
        if method == 'mining.set_difficulty':
            difficulty_target(params[0])
            self.difficulty = Decimal(str(params[0]))
        elif method == 'mining.notify':
            compact_target(params[6])
            self.job = params
            self.job_difficulty = self.difficulty
            self.generation += 1
        elif method == 'mining.set_extranonce':
            self.extra1, self.extra2_size = params
            self.job = None
            self.generation += 1
        elif method is None and message.get('id') in self.pending:
            request, metadata = self.pending.pop(message['id'])
            result = message.get('result')
            if request == 'mining.subscribe':
                if message.get('error') or not result:
                    raise ValueError('Subscription rejected')
                self.extra1, self.extra2_size = result[1:3]
                bytes.fromhex(self.extra1)
                if not 1 <= self.extra2_size <= 32:
                    raise ValueError('Invalid extranonce size')
            elif request == 'mining.authorize':
                if result is not True:
                    raise PermissionError('Stratum authorization rejected; check worker/password')
                self.authorized = True
                print('Stratum authorized; waiting for a synchronized mining template', flush=True)
            elif request == 'mining.submit':
                if result is True:
                    self.accepted += 1
                    self.blocks += bool(metadata)
                    print('Accepted block' if metadata else 'Accepted share', flush=True)
                else:
                    print('Rejected solution: ' + str(message.get('error')), flush=True)

    def close(self):
        self.socket.close()


def self_test(gpu):
    for header, start in [(bytes(80), 0), (os.urandom(80), 0xffffff00)]:
        actual = gpu.hashes(header, start, 256)
        expected = [sha256d(header[:76] + struct.pack('<I', start+i)) for i in range(256)]
        if actual != expected:
            raise RuntimeError('CUDA SHA-256d differs from CPU')
        target = int.from_bytes(expected[117], 'little')
        found, overflow = gpu.search(header, target, start, 128)
        wanted = {start+i for i, digest in enumerate(expected[:128]) if int.from_bytes(digest, 'little') <= target}
        if overflow or set(found) != wanted:
            raise RuntimeError('CUDA target comparison differs from CPU')
    genesis = bytes.fromhex('01000000' + '00'*32 +
        '3ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4a' +
        '29ab5f49ffff001d1dac2b7c')
    digest = gpu.hashes(genesis, 0x7c2bac1d, 1)[0]
    if digest[::-1].hex() != '000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f':
        raise RuntimeError('CUDA genesis hash mismatch')
    found, overflow = gpu.search(genesis, MAX_TARGET, 0, 256)
    if not overflow or len(set(found)) != gpu.CAPACITY or any(n >= 256 for n in found):
        raise RuntimeError('CUDA result overflow handling failed')
    if gpu.search(genesis, 0, 0, 256) != ([], False):
        raise RuntimeError('CUDA zero target comparison failed')
    for start, count in [(0xffffffff, 2), (0, 0), (-1, 1)]:
        try:
            gpu.search(genesis, MAX_TARGET, start, count)
        except ValueError:
            continue
        raise RuntimeError('CUDA accepted an invalid nonce range')
    print('CUDA self-test passed: 513 hashes including genesis, endian/target, nonce boundary and result overflow', flush=True)


def mine(gpu, args):
    retry = 1
    while True:
        connection = None
        try:
            connection = Connection(args.host, args.port)
            connection.send('mining.subscribe', ['bitcoin-node-cuda/1.0'])
            connection.send('mining.authorize', [args.worker, os.environ.get('BITCOIN_STRATUM_PASSWORD', '')])
            generation, extra2, nonce = -1, 0, 0
            material = None
            batch = args.batch_size
            hashes, started, reported = 0, time.monotonic(), time.monotonic()
            while True:
                connection.pump(0)
                if args.max_blocks and connection.blocks >= args.max_blocks:
                    return
                if not (connection.authorized and connection.extra1 is not None and connection.job):
                    connection.pump(1)
                    if time.monotonic() - reported >= 30:
                        print('Waiting for synchronized mining work; GPU idle', flush=True)
                        reported = time.monotonic()
                    continue
                if generation != connection.generation:
                    next_material = json.dumps(connection.job[1:8]) + connection.extra1
                    if next_material != material:
                        extra2, nonce, material = 0, 0, next_material
                    if generation == -1:
                        started = reported = time.monotonic()
                    generation = connection.generation
                    retry = 1
                job = connection.job
                extra = extra2.to_bytes(connection.extra2_size, 'big').hex()
                header = header_for(job, connection.extra1, extra)
                network_target = compact_target(job[6])
                target = network_target if args.block_only else max(network_target, difficulty_target(connection.job_difficulty))
                count = min(batch, (1 << 32) - nonce)
                found, overflow = gpu.search(header, target, nonce, count)
                hashes += count
                connection.pump(0)
                if generation != connection.generation:
                    continue
                if overflow:
                    batch = max(1, count // 2)
                    continue
                for solution in found:
                    digest = sha256d(header[:76] + struct.pack('<I', solution))
                    value = int.from_bytes(digest, 'little')
                    if value > target:
                        raise RuntimeError('GPU returned an invalid solution')
                    if len(connection.pending) >= 256:
                        connection.pump(1)
                        break
                    connection.send('mining.submit', [args.worker, job[0], extra, job[7], f'{solution:08x}'], value <= network_target)
                    if args.max_blocks and value <= network_target:
                        deadline = time.monotonic() + 15
                        while connection.pending and time.monotonic() < deadline:
                            connection.pump(0.1)
                            if connection.blocks >= args.max_blocks:
                                return
                        break
                nonce += count
                if nonce == 1 << 32:
                    nonce = 0
                    extra2 += 1
                    if extra2 == 1 << (8 * connection.extra2_size):
                        raise ConnectionError('Extranonce exhausted; reconnecting')
                now = time.monotonic()
                if now - reported >= 10:
                    print(f'{hashes/(now-started)/1e6:.2f} MH/s; accepted={connection.accepted}; blocks={connection.blocks}', flush=True)
                    reported = now
        except (ConnectionError, OSError) as error:
            print(f'{error}; reconnect in {retry}s', flush=True)
            time.sleep(retry)
            retry = min(30, retry * 2)
        finally:
            if connection:
                connection.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=3333)
    parser.add_argument('--worker', default='miner.gpu01')
    parser.add_argument('--device', type=int, default=0)
    parser.add_argument('--batch-size', type=int, default=262144)
    parser.add_argument('--self-test', action='store_true')
    parser.add_argument('--benchmark-seconds', type=int, default=0)
    parser.add_argument('--block-only', action='store_true')
    parser.add_argument('--max-blocks', type=int, default=0)
    args = parser.parse_args()
    if not 1 <= args.batch_size <= 1048576 or args.max_blocks < 0:
        parser.error('batch-size must be 1..1048576; max-blocks must be nonnegative')
    with CudaHasher(args.device) as gpu:
        print(f'CUDA device: {gpu.name} ({gpu.architecture})', flush=True)
        if args.self_test:
            self_test(gpu)
        elif args.benchmark_seconds:
            if not 1 <= args.benchmark_seconds <= 60:
                parser.error('benchmark-seconds must be 1..60')
            header = bytes(80)
            hashes, start, nonce = 0, time.monotonic(), 0
            while time.monotonic() - start < args.benchmark_seconds:
                count = min(args.batch_size, (1 << 32) - nonce)
                gpu.search(header, 0, nonce, count)
                hashes += count
                nonce = (nonce + count) % (1 << 32)
            print(f'CUDA benchmark: {hashes/(time.monotonic()-start)/1e6:.2f} MH/s', flush=True)
        else:
            mine(gpu, args)


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        pass
