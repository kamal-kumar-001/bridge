import io
import json
from pathlib import Path
import socket
import struct
import tempfile
import time
import unittest
import uuid
from bridge.core import Identity, Session, read_frame, json_frame, chunk_frame, safe_name, MAX_FRAME

class Reader:
    def __init__(self, data): self.data = io.BytesIO(data)
    def recv(self, size): return self.data.read(min(size, 2))

def wait(condition):
    end = time.monotonic() + 5
    while not condition():
        if time.monotonic() > end: raise AssertionError('Timed out')
        time.sleep(.01)

class CoreTests(unittest.TestCase):
    def test_fragmented_json(self):
        self.assertEqual(read_frame(Reader(json_frame({'type':'PING'}))), (1, {'type':'PING'}))
    def test_chunk(self):
        tid = str(uuid.uuid4())
        self.assertEqual(read_frame(Reader(chunk_frame(tid, 12, b'hello'))), (2, (tid, 12, b'hello')))
    def test_bad_lengths(self):
        for data in [struct.pack('>I', 0), struct.pack('>I', MAX_FRAME+1), struct.pack('>IB', 2, 2)+b'x']:
            with self.assertRaises(ValueError): read_frame(Reader(data))
    def test_truncated_frame(self):
        with self.assertRaises(EOFError): read_frame(Reader(json_frame({'type':'PING'})[:-1]))
    def test_bad_chunk_size(self):
        data = bytearray(chunk_frame(str(uuid.uuid4()), 0, b'hi')); data[29:33] = struct.pack('>I', 50)
        with self.assertRaises(ValueError): read_frame(Reader(data))
    def test_paths(self):
        for name in ['../secret', '/tmp/a', '..', 'a\\b', 'a:b', 'nul.txt', 'a\x00', 'a.', '']:
            with self.assertRaises(ValueError): safe_name(name)
        self.assertEqual(safe_name('holiday photo.jpg'), 'holiday photo.jpg')
    def test_identity_persistence(self):
        with tempfile.TemporaryDirectory() as path:
            first = Identity(path); first.trust('peer', 'fingerprint')
            second = Identity(path)
            self.assertEqual(first.fingerprint, second.fingerprint)
            self.assertEqual(second.pins, {'peer':'fingerprint'})
            second.revoke('peer'); self.assertEqual(Identity(path).pins, {})
    def pair(self, root, accept=True):
        a, b = socket.socketpair(); events_a=[]; events_b=[]
        one=Session(a, False, Identity(root/'a'), 'A', root/'files-a', lambda *e: events_a.append(e))
        two=Session(b, True, Identity(root/'b'), 'B', root/'files-b', lambda *e: events_b.append(e))
        self.addCleanup(one.close); self.addCleanup(two.close)
        one.start(); two.start(); wait(lambda: one.code is not None and two.code is not None)
        self.assertEqual(one.code, two.code)
        self.assertFalse(one.authenticated); self.assertFalse(two.authenticated)
        if accept:
            one.confirm(True); time.sleep(.02)
            self.assertFalse(one.authenticated)
            two.confirm(True); wait(lambda: one.authenticated and two.authenticated)
        return one,two,events_a,events_b
    def test_tls_pair_and_text(self):
        with tempfile.TemporaryDirectory() as path:
            one,two,ea,eb=self.pair(Path(path))
            one.send_text('Hello from desktop'); wait(lambda: ('text','Hello from desktop') in eb)
    def test_unpaired_data_blocked(self):
        with tempfile.TemporaryDirectory() as path:
            one,two,ea,eb=self.pair(Path(path),False)
            with self.assertRaises(ValueError): one.send_text('private')
            one.send({'type':'CLIPBOARD_TEXT','content':'attack'})
            wait(lambda: two.closed)
            self.assertFalse(any(e[0]=='text' for e in eb))
    def test_file_roundtrip_collision(self):
        with tempfile.TemporaryDirectory() as path:
            root=Path(path); one,two,ea,eb=self.pair(root)
            data=b'local sharing'*12000
            source=root/'sample.dat'; source.write_bytes(data)
            (two.downloads/'sample.dat').write_text('preserve')
            tid=one.send_file(source); wait(lambda: tid in two.offers)
            two.accept_file(tid); wait(lambda:any(e[0]=='saved' for e in eb))
            self.assertEqual((two.downloads/'sample (1).dat').read_bytes(),data)
            self.assertEqual((two.downloads/'sample.dat').read_text(),'preserve')
    def test_changed_pin_rejected(self):
        with tempfile.TemporaryDirectory() as path:
            root=Path(path); one,two,ea,eb=self.pair(root,False)
            one.close(); two.close()
            identity=Identity(root/'a'); identity.trust(Identity(root/'b').device_id,'wrong')
            a,b=socket.socketpair()
            first=Session(a,False,identity,'A',root/'out-a')
            second=Session(b,True,Identity(root/'b'),'B',root/'out-b')
            self.addCleanup(first.close); self.addCleanup(second.close)
            first.start(); second.start(); wait(lambda:first.closed)
            self.assertFalse(first.authenticated)
    def test_invalid_chunk_order(self):
        with tempfile.TemporaryDirectory() as path:
            one,two,ea,eb=self.pair(Path(path))
            with self.assertRaises(ValueError): two.receive_chunk(str(uuid.uuid4()),0,b'bad')

if __name__ == '__main__': unittest.main()
