// RVN KawPoW 0.9.4. Port of the project's Java verifier, unsigned CUDA arithmetic.
typedef unsigned int u32;
typedef unsigned long long u64;
__device__ __forceinline__ u32 rol(u32 x,u32 n) { n&=31; return (x<<n)|(x>>((-n)&31)); }
__device__ __forceinline__ u64 rol64(u64 x,u32 n) { return (x<<n)|(x>>((-n)&63)); }
__device__ __forceinline__ u32 fnv(u32 a,u32 b) { return (a^b)*0x01000193u; }
__device__ __forceinline__ u32 bswap(u32 a) { return __byte_perm(a,0,0x0123); }
__constant__ int ROT[25]={0,1,62,28,27,36,44,6,55,20,3,10,43,25,39,41,45,15,21,8,18,2,61,56,14};
__constant__ u64 RC[24]={1ull,0x8082ull,0x800000000000808aull,0x8000000080008000ull,0x808bull,
 0x80000001ull,0x8000000080008081ull,0x8000000000008009ull,0x8aull,0x88ull,0x80008009ull,
 0x8000000aull,0x8000808bull,0x800000000000008bull,0x8000000000008089ull,0x8000000000008003ull,
 0x8000000000008002ull,0x8000000000000080ull,0x800aull,0x800000008000000aull,
 0x8000000080008081ull,0x8000000000008080ull,0x80000001ull,0x8000000080008008ull};
__constant__ u32 DOMAIN[15]={0x72,0x41,0x56,0x45,0x4e,0x43,0x4f,0x49,0x4e,0x4b,0x41,0x57,0x50,0x4f,0x57};
__device__ void keccak800(u32 *a) {
 u32 b[25],c[5],d[5];
 for(int r=0;r<22;r++) {
  for(int x=0;x<5;x++) c[x]=a[x]^a[x+5]^a[x+10]^a[x+15]^a[x+20];
  for(int x=0;x<5;x++) d[x]=c[(x+4)%5]^rol(c[(x+1)%5],1);
  for(int y=0;y<5;y++) for(int x=0;x<5;x++) a[x+5*y]^=d[x];
  for(int y=0;y<5;y++) for(int x=0;x<5;x++) b[y+5*((2*x+3*y)%5)]=rol(a[x+5*y],ROT[x+5*y]);
  for(int y=0;y<5;y++) for(int x=0;x<5;x++) a[x+5*y]=b[x+5*y]^((~b[(x+1)%5+5*y])&b[(x+2)%5+5*y]);
  a[0]^=(u32)RC[r];
 }
}

// Runtime instruction stream for header batches. No NVRTC compilation per
// height/3; instructions are generated locally from the claimed height.
__device__ void execute_program(u32 *m,const u32 *l1,u32 *data,const u32 *steps) {
 for(int i=0;i<33;i++) {
  const u32 *s=steps+i*6;
  u32 value=0,a=m[s[2]],b=m[s[3]],sel=s[5];
  if(s[0]==0) value=l1[a%4096u];
  else if(s[0]==2) value=data[s[2]];
  else switch(s[4]) {
   case 0:value=a+b;break; case 1:value=a*b;break;
   case 2:value=__umulhi(a,b);break; case 3:value=min(a,b);break;
   case 4:value=rol(a,b);break; case 5:value=rol(a,32-(b&31));break;
   case 6:value=a&b;break; case 7:value=a|b;break; case 8:value=a^b;break;
   case 9:value=__clz(a)+__clz(b);break; case 10:value=__popc(a)+__popc(b);break;
  }
  u32 d=s[1],r=((sel>>16)%31)+1;
  switch(sel%4) {
   case 0:m[d]=m[d]*33u+value;break; case 1:m[d]=(m[d]^value)*33u;break;
   case 2:m[d]=rol(m[d],r)^value;break; case 3:m[d]=rol(m[d],32-r)^value;break;
  }
 }
}
__device__ void keccak512(u32 *words) {
 u64 a[25]={0},b[25],c[5],d[5];
 for(int i=0;i<8;i++) a[i]=(u64)words[2*i]|((u64)words[2*i+1]<<32);
 a[8]=0x8000000000000001ull;
 for(int r=0;r<24;r++) {
  for(int x=0;x<5;x++) c[x]=a[x]^a[x+5]^a[x+10]^a[x+15]^a[x+20];
  for(int x=0;x<5;x++) d[x]=c[(x+4)%5]^rol64(c[(x+1)%5],1);
  for(int y=0;y<5;y++) for(int x=0;x<5;x++) a[x+5*y]^=d[x];
  for(int y=0;y<5;y++) for(int x=0;x<5;x++) b[y+5*((2*x+3*y)%5)]=rol64(a[x+5*y],ROT[x+5*y]);
  for(int y=0;y<5;y++) for(int x=0;x<5;x++) a[x+5*y]=b[x+5*y]^((~b[(x+1)%5+5*y])&b[(x+2)%5+5*y]);
  a[0]^=RC[r];
 }
 for(int i=0;i<8;i++) { words[2*i]=(u32)a[i]; words[2*i+1]=(u32)(a[i]>>32); }
}
extern "C" __global__ void generate_dag(const u32 *cache,u32 rows,u32 *dag,u32 start,u32 count) {
 u32 offset=blockIdx.x*blockDim.x+threadIdx.x;
 if(offset>=count) return;
 u32 node=start+offset,m[16];
 for(int i=0;i<16;i++) m[i]=cache[(u64)(node%rows)*16+i];
 m[0]^=node; keccak512(m);
 for(u32 p=0;p<512;p++) {
  u32 index=(((node^p)*0x01000193u)^m[p%16])%rows;
  for(int i=0;i<16;i++) m[i]=(m[i]*0x01000193u)^cache[(u64)index*16+i];
 }
 keccak512(m);
 for(int i=0;i<16;i++) dag[(u64)node*16+i]=m[i];
}
__device__ u32 kiss(u32 &z,u32 &w,u32 &jsr,u32 &jcong) {
 z=36969*(z&65535)+(z>>16); w=18000*(w&65535)+(w>>16);
 u32 mwc=(z<<16)+w; jsr^=jsr<<17; jsr^=jsr>>13; jsr^=jsr<<5;
 jcong=69069*jcong+1234567; return (mwc^jcong)+jsr;
}
// PROGRAM is generated deterministically for height/3, never supplied by a peer.
__device__ __forceinline__ void program(u32 *m,const u32 *dag,u32 *data) {
 /*PROGRAM*/
}
__device__ __forceinline__ u32 nonce_mix32(u32 x) {
 x^=x>>16;x*=0x7feb352du;x^=x>>15;x*=0x846ca68bu;return x^(x>>16);
}
extern "C" __global__ void search(const u32 *dag,u32 items,const u32 *header,u64 start,u32 count,
                                  const u32 *target,u32 *output,u32 capacity,u32 key0,u32 key1,u32 permuted) {
 __shared__ u32 l1[4096];
 for(int i=threadIdx.x;i<4096;i+=blockDim.x) l1[i]=dag[i];
 __syncthreads();
 u32 gid=(blockIdx.x*blockDim.x+threadIdx.x)/16,lane=threadIdx.x%16;
 if(gid>=count) return;
 u32 mask=__activemask(); u64 nonce=start+gid; u32 a[25]={0},seed[8],m[32];
 // All 16 cooperative lanes must use the same permuted nonce.
 if(permuted) {
  u32 lo=nonce_mix32((u32)nonce+key0),hi=nonce_mix32((u32)(nonce>>32)^lo^key1);
  nonce=((u64)hi<<32)|lo;
 }
 for(int i=0;i<8;i++) a[i]=header[i];
 a[8]=(u32)nonce; a[9]=(u32)(nonce>>32);
 for(int i=0;i<15;i++) a[10+i]=DOMAIN[i];
 keccak800(a); for(int i=0;i<8;i++) seed[i]=a[i];
 u32 z=fnv(0x811c9dc5u,seed[0]),w=fnv(z,seed[1]),jsr=fnv(w,lane),jcong=fnv(jsr,lane);
 for(int i=0;i<32;i++) m[i]=kiss(z,w,jsr,jcong);
 for(int r=0;r<64;r++) {
  u32 index=__shfl_sync(mask,m[0],r%16,16)%items;
  u64 base=(u64)index*64+((lane^r)%16)*4;
  u32 data[4]; for(int i=0;i<4;i++) data[i]=dag[base+i];
  // The generated program reads the 16 KiB L1 prefix via its first parameter.
  program(m,l1,data);
 }
 u32 h=0x811c9dc5u; for(int i=0;i<32;i++) h=fnv(h,m[i]);
 for(int i=0;i<25;i++) a[i]=0;
 for(int i=0;i<8;i++) a[i]=seed[i];
 for(int i=0;i<8;i++) a[8+i]=fnv(fnv(0x811c9dc5u,__shfl_sync(mask,h,i,16)),__shfl_sync(mask,h,i+8,16));
 u32 mix[8]; for(int i=0;i<8;i++) mix[i]=a[8+i];
 for(int i=0;i<9;i++) a[16+i]=DOMAIN[i];
 keccak800(a);
 bool found=true;
 for(int i=0;i<8;i++) { u32 v=bswap(a[i]); if(v<target[i]) break; if(v>target[i]) {found=false;break;} }
 if(lane==0 && found) {
  u32 slot=atomicAdd(output,1);
  if(slot<capacity) {
   u32 *out=output+1+slot*18; out[0]=(u32)nonce; out[1]=(u32)(nonce>>32);
   for(int i=0;i<8;i++) {out[2+i]=mix[i];out[10+i]=a[i];}
  }
 }
}

extern "C" __global__ void verify_headers(const u32 *dag,u32 items,const u32 *jobs,
                                          const u32 *programs,u32 count,u32 *output) {
 __shared__ u32 l1[4096];
 for(int i=threadIdx.x;i<4096;i+=blockDim.x) l1[i]=dag[i];
 __syncthreads();
 u32 gid=(blockIdx.x*blockDim.x+threadIdx.x)/16,lane=threadIdx.x%16;
 if(gid>=count) return;
 const u32 *job=jobs+gid*11,*steps=programs+job[10]*33*6;
 u32 mask=__activemask(),a[25]={0},seed[8],m[32];
 for(int i=0;i<8;i++) a[i]=job[i];
 a[8]=job[8];a[9]=job[9];
 for(int i=0;i<15;i++) a[10+i]=DOMAIN[i];
 keccak800(a);for(int i=0;i<8;i++) seed[i]=a[i];
 u32 z=fnv(0x811c9dc5u,seed[0]),w=fnv(z,seed[1]),jsr=fnv(w,lane),jcong=fnv(jsr,lane);
 for(int i=0;i<32;i++) m[i]=kiss(z,w,jsr,jcong);
 for(int r=0;r<64;r++) {
  u32 index=__shfl_sync(mask,m[0],r%16,16)%items;
  u64 base=(u64)index*64+((lane^r)%16)*4;
  u32 data[4];for(int i=0;i<4;i++) data[i]=dag[base+i];
  execute_program(m,l1,data,steps);
 }
 u32 h=0x811c9dc5u;for(int i=0;i<32;i++) h=fnv(h,m[i]);
 for(int i=0;i<25;i++) a[i]=0;
 for(int i=0;i<8;i++) a[i]=seed[i];
 for(int i=0;i<8;i++) a[8+i]=fnv(fnv(0x811c9dc5u,__shfl_sync(mask,h,i,16)),__shfl_sync(mask,h,i+8,16));
 u32 mix[8];for(int i=0;i<8;i++) mix[i]=a[8+i];
 for(int i=0;i<9;i++) a[16+i]=DOMAIN[i];
 keccak800(a);
 if(lane==0) for(int i=0;i<8;i++) {output[gid*16+i]=mix[i];output[gid*16+8+i]=a[i];}
}
