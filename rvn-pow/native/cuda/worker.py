"""Local bounded KawPoW CUDA worker. JSON lines on stdin/stdout, no network access.
The caller supplies a Java-generated light-cache file, never an executable kernel.
"""
import ctypes as c
import ctypes.util
import json
import math
import os
from pathlib import Path
import struct
import sys

MASK = 0xffffffff


def fnv(a, b):
    return ((a ^ b) * 0x01000193) & MASK


class Kiss:
    def __init__(self, z, w, jsr, jcong):
        self.z, self.w, self.jsr, self.jcong = z, w, jsr, jcong

    def next(self):
        self.z = (36969 * (self.z & 65535) + (self.z >> 16)) & MASK
        self.w = (18000 * (self.w & 65535) + (self.w >> 16)) & MASK
        self.jsr ^= (self.jsr << 17) & MASK
        self.jsr ^= self.jsr >> 13
        self.jsr ^= (self.jsr << 5) & MASK
        self.jcong = (69069 * self.jcong + 1234567) & MASK
        return (((((self.z << 16) + self.w) & MASK) ^ self.jcong) + self.jsr) & MASK


def merge(dst, value, selector):
    a = f'm[{dst}]'
    rotation = ((selector >> 16) % 31) + 1
    expression = [f'{a}*33u+({value})', f'({a}^({value}))*33u',
                  f'rol({a},{rotation})^({value})', f'rol({a},{32-rotation})^({value})'][selector % 4]
    return f'{a}={expression};'


def program(height):
    period = height // 3
    z = fnv(0x811c9dc5, period & MASK)
    w = fnv(z, period >> 32)
    jsr = fnv(w, period & MASK)
    rng = Kiss(z, w, jsr, fnv(jsr, period >> 32))
    dst, src = list(range(32)), list(range(32))
    for i in range(32, 1, -1):
        a = rng.next() % i
        dst[i-1], dst[a] = dst[a], dst[i-1]
        b = rng.next() % i
        src[i-1], src[b] = src[b], src[i-1]
    di, si, lines = 0, 0, []
    for i in range(18):
        if i < 11:
            s, d = src[si % 32], dst[di % 32]
            si += 1
            di += 1
            lines.append(merge(d, f'dag[m[{s}]%4096u]', rng.next()))
        source = rng.next() % (32 * 31)
        s1, s2 = source % 32, source // 32
        if s2 >= s1:
            s2 += 1
        a, b = f'm[{s1}]', f'm[{s2}]'
        expressions = [f'{a}+{b}', f'{a}*{b}', f'__umulhi({a},{b})', f'min({a},{b})',
                       f'rol({a},{b})', f'rol({a},32-({b}&31))', f'{a}&{b}', f'{a}|{b}',
                       f'{a}^{b}', f'__clz({a})+__clz({b})', f'__popc({a})+__popc({b})']
        value = expressions[rng.next() % 11]
        d = dst[di % 32]
        di += 1
        lines.append(merge(d, value, rng.next()))
    for i in range(4):
        d = 0 if i == 0 else dst[di % 32]
        if i != 0:
            di += 1
        lines.append(merge(d, f'data[{i}]', rng.next()))
    return '\n'.join(lines)


def sized(epoch, initial, growth, row):
    def prime(n):
        return all(n % i for i in range(2, math.isqrt(n) + 1))
    rows = (initial + epoch * growth) // row - 1
    while not prime(rows):
        rows -= 2
    return rows * row


class Cuda:
    def __init__(self, device):
        self.context, self.module = c.c_void_p(), c.c_void_p()
        self.allocations, self.dll_directory = [], None
        self.epoch, self.period = None, None
        if os.name == 'nt':
            root = Path(os.environ.get('CUDA_PATH', 'C:/Program Files/NVIDIA GPU Computing Toolkit/CUDA/v12.2'))
            libraries = sorted(root.glob('bin/nvrtc64*.dll'))
            if not libraries:
                raise RuntimeError('NVRTC not found; set CUDA_PATH')
            self.dll_directory = os.add_dll_directory(str(libraries[0].parent))
            self.nvrtc = c.CDLL(str(libraries[0]))
            self.driver = c.WinDLL(str(Path(os.environ['SystemRoot']) / 'System32/nvcuda.dll'))
        else:
            self.nvrtc = c.CDLL(ctypes.util.find_library('nvrtc') or 'libnvrtc.so')
            self.driver = c.CDLL(ctypes.util.find_library('cuda') or 'libcuda.so.1')
        p, u, size, ptr = c.c_void_p, c.c_uint, c.c_size_t, c.c_uint64
        signatures = {
            'cuInit': [u], 'cuDeviceGet': [c.POINTER(c.c_int), c.c_int],
            'cuDeviceGetName': [p, c.c_int, c.c_int],
            'cuDeviceGetAttribute': [c.POINTER(c.c_int), c.c_int, c.c_int],
            'cuDevicePrimaryCtxRetain': [c.POINTER(p), c.c_int], 'cuDevicePrimaryCtxRelease_v2': [c.c_int],
            'cuCtxSetCurrent': [p], 'cuCtxSynchronize': [],
            'cuModuleLoadData': [c.POINTER(p), p], 'cuModuleUnload': [p],
            'cuModuleGetFunction': [c.POINTER(p), p, c.c_char_p],
            'cuMemGetInfo_v2': [c.POINTER(size), c.POINTER(size)],
            'cuMemAlloc_v2': [c.POINTER(ptr), size], 'cuMemFree_v2': [ptr],
            'cuMemcpyHtoD_v2': [ptr, p, size], 'cuMemcpyDtoH_v2': [p, ptr, size],
            'cuMemsetD32_v2': [ptr, u, size],
            'cuLaunchKernel': [p, u, u, u, u, u, u, u, p, c.POINTER(p), c.POINTER(p)]}
        for name, args in signatures.items():
            function = getattr(self.driver, name)
            function.argtypes, function.restype = args, c.c_int
        for name, args in {
            'nvrtcCreateProgram': [c.POINTER(p), c.c_char_p, c.c_char_p, c.c_int, p, p],
            'nvrtcCompileProgram': [p, c.c_int, c.POINTER(c.c_char_p)],
            'nvrtcGetProgramLogSize': [p, c.POINTER(size)], 'nvrtcGetProgramLog': [p, p],
            'nvrtcGetPTXSize': [p, c.POINTER(size)], 'nvrtcGetPTX': [p, p],
            'nvrtcDestroyProgram': [c.POINTER(p)]}.items():
            function = getattr(self.nvrtc, name)
            function.argtypes, function.restype = args, c.c_int
        try:
            self.call('cuInit', 0)
            self.device = c.c_int()
            self.call('cuDeviceGet', c.byref(self.device), device)
            self.call('cuDevicePrimaryCtxRetain', c.byref(self.context), self.device)
            self.call('cuCtxSetCurrent', self.context)
            name = c.create_string_buffer(256)
            self.call('cuDeviceGetName', name, len(name), self.device)
            self.name = name.value.decode()
            major, minor = c.c_int(), c.c_int()
            self.call('cuDeviceGetAttribute', c.byref(major), 75, self.device)
            self.call('cuDeviceGetAttribute', c.byref(minor), 76, self.device)
            self.arch = f'compute_{major.value}{minor.value}'
        except BaseException:
            self.close()
            raise

    @staticmethod
    def check(status, operation):
        if status:
            raise RuntimeError(f'{operation}: CUDA/NVRTC error {status}')

    def call(self, name, *args):
        self.check(getattr(self.driver, name)(*args), name)

    def allocate(self, size):
        ptr = c.c_uint64()
        self.call('cuMemAlloc_v2', c.byref(ptr), size)
        self.allocations.append(ptr)
        return ptr

    def compile(self, height):
        if self.period == height // 3:
            return
        source = Path(__file__).with_name('kawpow.cu').read_text().replace('/*PROGRAM*/', program(height)).encode()
        handle = c.c_void_p()
        self.check(self.nvrtc.nvrtcCreateProgram(c.byref(handle), source, b'kawpow.cu', 0, None, None), 'create')
        try:
            options = (c.c_char_p * 2)(f'--gpu-architecture={self.arch}'.encode(), b'--std=c++11')
            status = self.nvrtc.nvrtcCompileProgram(handle, len(options), options)
            if status:
                size = c.c_size_t()
                self.nvrtc.nvrtcGetProgramLogSize(handle, c.byref(size))
                log = c.create_string_buffer(max(1, size.value))
                self.nvrtc.nvrtcGetProgramLog(handle, log)
                raise RuntimeError(log.value.decode(errors='replace'))
            size = c.c_size_t()
            self.check(self.nvrtc.nvrtcGetPTXSize(handle, c.byref(size)), 'ptx size')
            ptx = c.create_string_buffer(size.value)
            self.check(self.nvrtc.nvrtcGetPTX(handle, ptx), 'ptx')
            if self.module.value:
                self.call('cuModuleUnload', self.module)
                self.module = c.c_void_p()
            self.call('cuModuleLoadData', c.byref(self.module), ptx)
            self.generate, self.search_function = c.c_void_p(), c.c_void_p()
            self.call('cuModuleGetFunction', c.byref(self.generate), self.module, b'generate_dag')
            self.call('cuModuleGetFunction', c.byref(self.search_function), self.module, b'search')
            self.period = height // 3
        finally:
            self.nvrtc.nvrtcDestroyProgram(c.byref(handle))

    def launch(self, function, threads, args):
        arguments = (c.c_void_p * len(args))(*(c.cast(c.pointer(arg), c.c_void_p) for arg in args))
        self.call('cuLaunchKernel', function, (threads + 127) // 128, 1, 1, 128, 1, 1, 0, None, arguments, None)
        self.call('cuCtxSynchronize')

    def prepare(self, height, cache_path, reserve):
        if not 0 <= height <= 0x7fffffff or reserve < 1 << 28:
            raise ValueError('Invalid height/VRAM reserve')
        epoch = height // 7500
        self.compile(height)
        if self.epoch == epoch:
            return
        for ptr in reversed(self.allocations):
            self.call('cuMemFree_v2', ptr)
        self.allocations.clear()
        self.epoch = None
        cache_bytes = sized(epoch, 1 << 24, 1 << 17, 64)
        dag_bytes = sized(epoch, 1 << 30, 1 << 23, 128) // 256 * 256
        free, total = c.c_size_t(), c.c_size_t()
        self.call('cuMemGetInfo_v2', c.byref(free), c.byref(total))
        if dag_bytes + cache_bytes + reserve + 65536 > free.value:
            raise MemoryError('DAG/light-cache/reserve do not fit free VRAM; no host-memory spill')
        path = Path(cache_path)
        if path.stat().st_size != cache_bytes:
            raise ValueError('Light-cache size does not match epoch')
        cache_data = path.read_bytes()
        cache = self.allocate(cache_bytes)
        self.call('cuMemcpyHtoD_v2', cache, c.c_char_p(cache_data), cache_bytes)
        self.dag = self.allocate(dag_bytes)
        self.items = dag_bytes // 256
        # Short synchronous launches avoid one unbounded Windows watchdog kernel.
        for start in range(0, self.items * 4, 8192):
            count = min(8192, self.items * 4 - start)
            self.launch(self.generate, count, [cache, c.c_uint(cache_bytes // 64), self.dag, c.c_uint(start), c.c_uint(count)])
        self.call('cuMemFree_v2', cache)
        self.allocations.remove(cache)
        self.header, self.target, self.output = self.allocate(32), self.allocate(32), self.allocate(4 * (1 + 128 * 18))
        self.epoch = epoch

    def search(self, height, header_hex, start, count, target_hex):
        if self.epoch != height // 7500:
            raise ValueError('Epoch not prepared')
        if not 0 <= start < 1 << 64 or not 1 <= count <= 65536 or start + count > 1 << 64:
            raise ValueError('Invalid bounded uint64 nonce range')
        header, target = bytes.fromhex(header_hex), bytes.fromhex(target_hex)
        if len(header) != 32 or len(target) != 32:
            raise ValueError('Header/target must be 32 bytes')
        self.compile(height)
        self.call('cuMemcpyHtoD_v2', self.header, c.c_char_p(header), 32)
        target_words = (c.c_uint32 * 8)(*struct.unpack('>8I', target))
        self.call('cuMemcpyHtoD_v2', self.target, target_words, 32)
        self.call('cuMemsetD32_v2', self.output, 0, 1)
        self.launch(self.search_function, count * 16, [self.dag, c.c_uint(self.items), self.header, c.c_uint64(start),
                    c.c_uint(count), self.target, self.output, c.c_uint(128)])
        result = (c.c_uint32 * (1 + 128 * 18))()
        self.call('cuMemcpyDtoH_v2', result, self.output, c.sizeof(result))
        found = []
        for i in range(min(128, result[0])):
            words = result[1+i*18:1+(i+1)*18]
            found.append({'nonce': str(words[0] | (words[1] << 32)), 'mix': struct.pack('<8I', *words[2:10]).hex(),
                          'hash': struct.pack('<8I', *words[10:18]).hex()})
        return {'candidates': found, 'overflow': result[0] > 128, 'tested': count}

    def close(self):
        if self.context.value:
            self.driver.cuCtxSetCurrent(self.context)
            for ptr in reversed(self.allocations):
                self.driver.cuMemFree_v2(ptr)
            self.allocations.clear()
            if self.module.value:
                self.driver.cuModuleUnload(self.module)
            self.driver.cuDevicePrimaryCtxRelease_v2(self.device)
            self.context = c.c_void_p()
        if self.dll_directory is not None:
            self.dll_directory.close()
            self.dll_directory = None


def binary_main():
    source, sink, gpu = sys.stdin.buffer, sys.stdout.buffer, None
    def read(size):
        data = source.read(size)
        if len(data) != size:
            raise EOFError('Truncated CUDA request')
        return data
    try:
        while True:
            command = source.read(1)
            if not command or command == b'\x00':
                break
            if command == b'\x01':
                height, device, reserve, length = struct.unpack('>iiQI', read(20))
                if length > 8192:
                    raise ValueError('Cache path too long')
                path = read(length).decode('utf-8')
                if gpu is None:
                    gpu = Cuda(device)
                gpu.prepare(height, path, reserve)
                sink.write(struct.pack('>iQ', 0, gpu.items * 256))
            elif command == b'\x02' and gpu is not None:
                height, start, count = struct.unpack('>iQI', read(16))
                header, target = read(32).hex(), read(32).hex()
                result = gpu.search(height, header, start, count, target)
                sink.write(struct.pack('>i?I', 0, result['overflow'], len(result['candidates'])))
                for candidate in result['candidates']:
                    sink.write(struct.pack('>Q', int(candidate['nonce'])))
                    sink.write(bytes.fromhex(candidate['mix']) + bytes.fromhex(candidate['hash']))
            else:
                raise ValueError('Unknown binary worker operation')
            sink.flush()
    except Exception as error:
        message = str(error).encode('utf-8')[:8192]
        sink.write(struct.pack('>iI', -1, len(message)) + message)
        sink.flush()
        return 1
    finally:
        if gpu is not None:
            gpu.close()
    return 0


def main():
    gpu = None
    try:
        for line in sys.stdin:
            if len(line) > 16384:
                raise ValueError('Worker request too long')
            request = json.loads(line)
            op = request['op']
            if op == 'close':
                break
            if op == 'init':
                if gpu is None:
                    gpu = Cuda(int(request.get('device', 0)))
                gpu.prepare(int(request['height']), request['cache'], int(request.get('reserve', 1 << 30)))
                result = {'ready': True, 'device': gpu.name, 'epoch': gpu.epoch, 'dagBytes': gpu.items * 256}
            elif op == 'search' and gpu is not None:
                result = gpu.search(int(request['height']), request['header'], int(request['start']), int(request['count']), request['target'])
            else:
                raise ValueError('Unknown operation or uninitialized worker')
            print(json.dumps(result, separators=(',', ':')), flush=True)
    except Exception as error:
        print(json.dumps({'error': str(error)}), flush=True)
        return 1
    finally:
        if gpu is not None:
            gpu.close()
    return 0


if __name__ == '__main__':
    sys.exit(binary_main() if '--binary' in sys.argv else main())
