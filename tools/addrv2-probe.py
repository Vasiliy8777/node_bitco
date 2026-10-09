"""Bounded mainnet address-only probe: no block requests, no persistent node database."""
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import socket
import struct
import time

OUT = Path('target/addrv2-probe-20261008')
MAGIC = bytes.fromhex('f9beb4d9')

def digest(data): return hashlib.sha256(hashlib.sha256(data).digest()).digest()
def read(sock, count):
    data = b''
    while len(data) < count:
        chunk = sock.recv(count-len(data))
        if not chunk: raise EOFError('peer closed')
        data += chunk
    return data
def send(sock, command, data=b''):
    sock.sendall(MAGIC + command.encode().ljust(12, b'\0') + struct.pack('<I',len(data)) + digest(data)[:4] + data)

def probe(host):
    record = {'host':host, 'messages':[], 'captures':[]}
    try:
        with socket.create_connection((host,8333),timeout=5) as sock:
            sock.settimeout(5)
            address = struct.pack('<Q',0) + bytes(16) + struct.pack('>H',8333)
            agent=b'/java-node:address-probe/'
            version = struct.pack('<iQq',70016,0,int(time.time())) + address*2 + os.urandom(8) + bytes([len(agent)]) + agent + struct.pack('<iB',0,0)
            send(sock,'version',version)
            deadline=time.monotonic()+20
            while time.monotonic()<deadline:
                header=read(sock,24)
                if header[:4]!=MAGIC: raise ValueError('wrong magic')
                length=struct.unpack('<I',header[16:20])[0]
                if length>1048576: raise ValueError('oversized message')
                payload=read(sock,length)
                if digest(payload)[:4]!=header[20:24]: raise ValueError('bad checksum')
                command=header[4:16].rstrip(b'\0').decode()
                record['messages'].append(command)
                if command=='version':
                    send(sock,'sendaddrv2'); send(sock,'verack')
                elif command=='verack': send(sock,'getaddr')
                elif command=='ping': send(sock,'pong',payload)
                elif command=='addrv2':
                    file=OUT/(host.replace(':','_')+'-'+str(len(record['captures']))+'.bin')
                    file.write_bytes(payload)
                    record['captures'].append(str(file))
                    break
    except Exception as error: record['error']=str(error)
    return record

if __name__=='__main__':
    OUT.mkdir(parents=True,exist_ok=True)
    hosts=['101.58.112.209','51.7.125.62','185.100.87.52','201.176.218.223']
    try:
        hosts += [item[4][0] for item in socket.getaddrinfo('seed.bitcoin.sipa.be',8333,socket.AF_INET,socket.SOCK_STREAM)][:8]
    except OSError: pass
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        records=list(pool.map(probe,list(dict.fromkeys(hosts))[:12]))
    (OUT/'manifest.json').write_text(json.dumps(records,indent=2))
    print(json.dumps({'peers':len(records),'captured':sum(len(r['captures']) for r in records),'errors':sum('error' in r for r in records)}))
