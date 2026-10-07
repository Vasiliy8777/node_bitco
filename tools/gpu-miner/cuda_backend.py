"""CUDA Driver API + NVRTC, using only Python's standard library."""
import ctypes as c
import ctypes.util
import hashlib
import os
from pathlib import Path
import struct

MASK = 0xffffffff
INITIAL = (0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19)
K = tuple(int(word,16) for word in """
428a2f98 71374491 b5c0fbcf e9b5dba5 3956c25b 59f111f1 923f82a4 ab1c5ed5
d807aa98 12835b01 243185be 550c7dc3 72be5d74 80deb1fe 9bdc06a7 c19bf174
e49b69c1 efbe4786 0fc19dc6 240ca1cc 2de92c6f 4a7484aa 5cb0a9dc 76f988da
983e5152 a831c66d b00327c8 bf597fc7 c6e00bf3 d5a79147 06ca6351 14292967
27b70a85 2e1b2138 4d2c6dfc 53380d13 650a7354 766a0abb 81c2c92e 92722c85
a2bfe8a1 a81a664b c24b8b70 c76c51a3 d192e819 d6990624 f40e3585 106aa070
19a4c116 1e376c08 2748774c 34b0bcb5 391c0cb3 4ed8aa4a 5b9cca4f 682e6ff3
748f82ee 78a5636f 84c87814 8cc70208 90befffa a4506ceb bef9a3f7 c67178f2
""".split())


def sha256d(data):
    return hashlib.sha256(hashlib.sha256(data).digest()).digest()


def midstate(block):
    if len(block) != 64:
        raise ValueError("Midstate requires exactly 64 bytes")
    def ror(x,n): return ((x >> n) | (x << (32-n))) & MASK
    w=list(struct.unpack('>16I',block))
    for i in range(16,64):
        x,y=w[i-15],w[i-2]
        w.append((w[i-16]+(ror(x,7)^ror(x,18)^(x>>3))+w[i-7]+(ror(y,17)^ror(y,19)^(y>>10)))&MASK)
    a,b,cc,d,e,f,g,h=INITIAL
    for i in range(64):
        t1=(h+(ror(e,6)^ror(e,11)^ror(e,25))+((e&f)^((~e)&g))+K[i]+w[i])&MASK
        t2=((ror(a,2)^ror(a,13)^ror(a,22))+((a&b)^(a&cc)^(b&cc)))&MASK
        a,b,cc,d,e,f,g,h=(t1+t2)&MASK,a,b,cc,(d+t1)&MASK,e,f,g
    return tuple((x+y)&MASK for x,y in zip(INITIAL,(a,b,cc,d,e,f,g,h)))


def _bind(lib,name,types):
    function=getattr(lib,name)
    function.argtypes=types
    function.restype=c.c_int
    return function


def _check(code,operation):
    if code:
        raise RuntimeError(f"{operation} failed (CUDA/NVRTC error {code})")


class CudaHasher:
    CAPACITY=128

    def __init__(self,device_index=0):
        self.module=c.c_void_p()
        self.context=c.c_void_p()
        self.allocations=[]
        self.dll_directory=None
        self.prepared=None
        if os.name=='nt':
            roots=[Path(os.environ.get('CUDA_PATH','C:/Program Files/NVIDIA GPU Computing Toolkit/CUDA/v12.2'))]
            matches=list(roots[0].glob('bin/nvrtc64*.dll'))
            if not matches: raise RuntimeError("NVRTC not found; set CUDA_PATH to your CUDA Toolkit directory")
            self.dll_directory=os.add_dll_directory(str(matches[0].parent))
            self.nvrtc=c.CDLL(str(matches[0]))
            self.driver=c.WinDLL(str(Path(os.environ['SystemRoot'])/'System32/nvcuda.dll'))
        else:
            self.nvrtc=c.CDLL(ctypes.util.find_library('nvrtc') or 'libnvrtc.so')
            self.driver=c.CDLL(ctypes.util.find_library('cuda') or 'libcuda.so.1')
        p=c.c_void_p; u=c.c_uint; z=c.c_size_t; devptr=c.c_uint64
        signatures={
            'cuInit':[u], 'cuDeviceGet':[c.POINTER(c.c_int),c.c_int],
            'cuDeviceGetName':[p,c.c_int,c.c_int],
            'cuDeviceGetAttribute':[c.POINTER(c.c_int),c.c_int,c.c_int],
            'cuDevicePrimaryCtxRetain':[c.POINTER(p),c.c_int], 'cuDevicePrimaryCtxRelease_v2':[c.c_int],
            'cuCtxSetCurrent':[p], 'cuCtxSynchronize':[],
            'cuModuleLoadData':[c.POINTER(p),p], 'cuModuleUnload':[p],
            'cuModuleGetFunction':[c.POINTER(p),p,c.c_char_p],
            'cuMemAlloc_v2':[c.POINTER(devptr),z], 'cuMemFree_v2':[devptr],
            'cuMemcpyHtoD_v2':[devptr,p,z], 'cuMemcpyDtoH_v2':[p,devptr,z],
            'cuMemsetD32_v2':[devptr,u,z],
            'cuLaunchKernel':[p,u,u,u,u,u,u,u,p,c.POINTER(p),c.POINTER(p)]}
        for name,types in signatures.items(): _bind(self.driver,name,types)
        for name,types in {
            'nvrtcCreateProgram':[c.POINTER(p),c.c_char_p,c.c_char_p,c.c_int,p,p],
            'nvrtcCompileProgram':[p,c.c_int,c.POINTER(c.c_char_p)],
            'nvrtcGetProgramLogSize':[p,c.POINTER(z)], 'nvrtcGetProgramLog':[p,p],
            'nvrtcGetPTXSize':[p,c.POINTER(z)], 'nvrtcGetPTX':[p,p],
            'nvrtcDestroyProgram':[c.POINTER(p)]}.items(): _bind(self.nvrtc,name,types)
        try:
            self._call('cuInit',0)
            self.device=c.c_int()
            self._call('cuDeviceGet',c.byref(self.device),device_index)
            self._call('cuDevicePrimaryCtxRetain',c.byref(self.context),self.device)
            self._call('cuCtxSetCurrent',self.context)
            name=c.create_string_buffer(256)
            self._call('cuDeviceGetName',name,len(name),self.device)
            self.name=name.value.decode()
            major,minor=c.c_int(),c.c_int()
            self._call('cuDeviceGetAttribute',c.byref(major),75,self.device)
            self._call('cuDeviceGetAttribute',c.byref(minor),76,self.device)
            self.architecture=f'compute_{major.value}{minor.value}'
            ptx=self._compile(Path(__file__).with_name('sha256d.cu').read_bytes())
            self._call('cuModuleLoadData',c.byref(self.module),ptx)
            self.search_function=c.c_void_p(); self.hash_function=c.c_void_p()
            self._call('cuModuleGetFunction',c.byref(self.search_function),self.module,b'search')
            self._call('cuModuleGetFunction',c.byref(self.hash_function),self.module,b'hash_headers')
            self.data=self._allocate(44); self.target=self._allocate(32)
            self.results=self._allocate(4*(self.CAPACITY+1))
        except BaseException:
            self.close()
            raise

    def _call(self,name,*args):
        _check(getattr(self.driver,name)(*args),name)

    def _compile(self,source):
        program=c.c_void_p()
        _check(self.nvrtc.nvrtcCreateProgram(c.byref(program),source,b'sha256d.cu',0,None,None),'nvrtcCreateProgram')
        try:
            options=(c.c_char_p*2)(f'--gpu-architecture={self.architecture}'.encode(),b'--std=c++11')
            status=self.nvrtc.nvrtcCompileProgram(program,len(options),options)
            if status:
                size=c.c_size_t()
                self.nvrtc.nvrtcGetProgramLogSize(program,c.byref(size))
                log=c.create_string_buffer(max(1,size.value))
                self.nvrtc.nvrtcGetProgramLog(program,log)
                raise RuntimeError(f'NVRTC compilation failed: {log.value.decode(errors="replace")}')
            size=c.c_size_t()
            _check(self.nvrtc.nvrtcGetPTXSize(program,c.byref(size)),'nvrtcGetPTXSize')
            ptx=c.create_string_buffer(size.value)
            _check(self.nvrtc.nvrtcGetPTX(program,ptx),'nvrtcGetPTX')
            return ptx
        finally:
            self.nvrtc.nvrtcDestroyProgram(c.byref(program))

    def _allocate(self,size):
        pointer=c.c_uint64()
        self._call('cuMemAlloc_v2',c.byref(pointer),size)
        self.allocations.append(pointer)
        return pointer

    def _prepare(self,header,target=None):
        if len(header)!=80: raise ValueError('Bitcoin header must be 80 bytes')
        prefix=header[:76]
        if prefix!=self.prepared:
            words=midstate(header[:64])+struct.unpack('>3I',header[64:76])
            data=(c.c_uint32*11)(*words)
            self._call('cuMemcpyHtoD_v2',self.data,data,c.sizeof(data))
            self.prepared=prefix
        if target is not None:
            if not 0<=target<1<<256: raise ValueError('Invalid target')
            words=(c.c_uint32*8)(*struct.unpack('>8I',target.to_bytes(32,'big')))
            self._call('cuMemcpyHtoD_v2',self.target,words,32)

    @staticmethod
    def _range(start,count):
        if not 0<=start<=MASK or not 1<=count<=MASK or start+count>1<<32:
            raise ValueError('Nonce range exceeds uint32')

    def _launch(self,function,count,args):
        arguments=(c.c_void_p*len(args))(*(c.cast(c.pointer(arg),c.c_void_p) for arg in args))
        self._call('cuLaunchKernel',function,(count+127)//128,1,1,128,1,1,0,None,arguments,None)
        self._call('cuCtxSynchronize')

    def search(self,header,target,start,count):
        self._range(start,count); self._prepare(header,target)
        self._call('cuMemsetD32_v2',self.results,0,1)
        self._launch(self.search_function,count,[self.data,c.c_uint(start),c.c_uint(count),self.target,self.results,c.c_uint(self.CAPACITY)])
        output=(c.c_uint32*(self.CAPACITY+1))()
        self._call('cuMemcpyDtoH_v2',output,self.results,c.sizeof(output))
        return list(output[1:1+min(output[0],self.CAPACITY)]),output[0]>self.CAPACITY

    def hashes(self,header,start,count):
        self._range(start,count)
        if count>4096: raise ValueError('Diagnostic hash batch is bounded to 4096')
        self._prepare(header)
        output=(c.c_uint32*(count*8))()
        pointer=self._allocate(c.sizeof(output))
        try:
            self._launch(self.hash_function,count,[self.data,c.c_uint(start),c.c_uint(count),pointer])
            self._call('cuMemcpyDtoH_v2',output,pointer,c.sizeof(output))
            return [struct.pack('>8I',*output[i*8:(i+1)*8]) for i in range(count)]
        finally:
            self._call('cuMemFree_v2',pointer); self.allocations.remove(pointer)

    def close(self):
        if self.context.value:
            self.driver.cuCtxSetCurrent(self.context)
            for pointer in reversed(self.allocations): self.driver.cuMemFree_v2(pointer)
            self.allocations.clear()
            if self.module.value: self.driver.cuModuleUnload(self.module); self.module=c.c_void_p()
            self.driver.cuDevicePrimaryCtxRelease_v2(self.device)
            self.context=c.c_void_p()
        if self.dll_directory is not None:
            self.dll_directory.close(); self.dll_directory=None

    def __enter__(self): return self
    def __exit__(self,*_): self.close()
