"""Bridge v2: certificate-pinned TLS, explicit pairing and bounded file transfer.

The verification code binds both certificates and fresh session nonces. TLS's
bootstrap verification callback permits first contact only: application data is
blocked until both users confirm the code or an existing certificate pin matches.
"""
from __future__ import annotations
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import platform
import secrets
import socket
import struct
import threading
import uuid
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID
from OpenSSL import SSL, crypto

PORT = 52431
MAX_FRAME = 1024 * 1024
MAX_FILE = 2 * 1024**3


def exact(conn, size):
    data = bytearray()
    while len(data) < size:
        chunk = conn.recv(size - len(data))
        if not chunk:
            raise EOFError("Peer disconnected")
        data.extend(chunk)
    return bytes(data)


def read_frame(conn):
    length = struct.unpack('>I', exact(conn, 4))[0]
    if not 1 <= length <= MAX_FRAME:
        raise ValueError('Invalid frame length')
    kind = exact(conn, 1)[0]
    payload = exact(conn, length - 1)
    if kind == 1:
        result = json.loads(payload.decode('utf-8'))
        if not isinstance(result, dict) or not isinstance(result.get('type'), str):
            raise ValueError('Invalid control message')
        return kind, result
    if kind == 2:
        if not 29 <= len(payload) <= 28 + 65536:
            raise ValueError('Invalid chunk frame')
        offset, size = struct.unpack('>QI', payload[16:28])
        if size != len(payload) - 28:
            raise ValueError('Chunk size mismatch')
        return kind, (str(uuid.UUID(bytes=payload[:16])), offset, payload[28:])
    raise ValueError('Unsupported frame type')


def json_frame(message):
    payload = json.dumps(message, separators=(',', ':')).encode()
    if len(payload) >= MAX_FRAME:
        raise ValueError('Message too large')
    return struct.pack('>IB', len(payload) + 1, 1) + payload


def chunk_frame(tid, offset, data):
    if not 0 < len(data) <= 65536 or offset < 0:
        raise ValueError('Invalid chunk')
    return struct.pack('>IB16sQI', len(data) + 29, 2, uuid.UUID(tid).bytes, offset, len(data)) + data


def safe_name(name):
    if not isinstance(name, str) or not name or len(name.encode()) > 200:
        raise ValueError('Invalid filename')
    if name in ('.', '..') or any(c in name for c in '/\\:') or any(ord(c) < 32 for c in name):
        raise ValueError('Unsafe filename')
    if name.endswith((' ', '.')) or name.split('.')[0].upper() in {'CON', 'PRN', 'AUX', 'NUL', *(f'COM{i}' for i in range(1,10)), *(f'LPT{i}' for i in range(1,10))}:
        raise ValueError('Reserved filename')
    return name


class Identity:
    def __init__(self, directory):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.cert_path = self.directory / 'identity.pem'
        self.key_path = self.directory / 'identity.key'
        if self.cert_path.exists() != self.key_path.exists():
            raise RuntimeError('Incomplete identity. Restore both identity files or reset pairing explicitly.')
        if not self.cert_path.exists():
            key = ec.generate_private_key(ec.SECP256R1())
            name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, str(uuid.uuid4()))])
            now = dt.datetime.now(dt.timezone.utc)
            cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
                    .public_key(key.public_key()).serial_number(x509.random_serial_number())
                    .not_valid_before(now - dt.timedelta(days=1)).not_valid_after(now + dt.timedelta(days=3650))
                    .sign(key, hashes.SHA256()))
            for path, data in ((self.key_path, key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())),
                               (self.cert_path, cert.public_bytes(serialization.Encoding.PEM))):
                fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                with os.fdopen(fd, 'wb') as handle:
                    handle.write(data)
        self.cert = x509.load_pem_x509_certificate(self.cert_path.read_bytes())
        self.fingerprint = self.cert.fingerprint(hashes.SHA256()).hex()
        self.device_id = self.fingerprint[:32]
        self.pins_path = self.directory / 'trusted.json'
        self.lock = threading.RLock()
        self.pins = json.loads(self.pins_path.read_text()) if self.pins_path.exists() else {}

    def context(self):
        context = SSL.Context(SSL.TLS_METHOD)
        context.set_min_proto_version(SSL.TLS1_2_VERSION)
        context.set_max_proto_version(SSL.TLS1_2_VERSION)
        context.use_certificate_file(str(self.cert_path))
        context.use_privatekey_file(str(self.key_path))
        context.check_privatekey()
        context.set_verify(SSL.VERIFY_PEER | SSL.VERIFY_FAIL_IF_NO_PEER_CERT, lambda *_: True)
        return context

    def save(self):
        temp = self.pins_path.with_suffix('.tmp')
        fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, 'w') as handle:
            json.dump(self.pins, handle)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temp, self.pins_path)

    def trust(self, peer_id, fingerprint):
        with self.lock:
            self.pins[peer_id] = fingerprint
            self.save()

    def revoke(self, peer_id):
        with self.lock:
            self.pins.pop(peer_id, None)
            self.save()


class Session:
    def __init__(self, raw_socket, incoming, identity, name, downloads, emit=lambda *_: None):
        self.raw = raw_socket
        self.identity = identity
        self.name = name
        self.downloads = Path(downloads)
        self.downloads.mkdir(parents=True, exist_ok=True)
        self.emit = emit
        self.conn = SSL.Connection(identity.context(), raw_socket)
        self.conn.set_accept_state() if incoming else self.conn.set_connect_state()
        self.lock = threading.RLock()
        self.local_nonce = secrets.token_hex(16)
        self.peer = None
        self.peer_fingerprint = None
        self.code = None
        self.local_accepted = False
        self.remote_accepted = False
        self.authenticated = False
        self.closed = False
        self.offers = {}
        self.receiving = {}
        self.sending = {}

    def start(self):
        threading.Thread(target=self.run, daemon=True).start()
        return self

    def run(self):
        try:
            self.raw.setblocking(True)
            self.conn.do_handshake()
            certificate = self.conn.get_peer_certificate().to_cryptography()
            now = dt.datetime.now(dt.timezone.utc)
            if not certificate.not_valid_before_utc <= now <= certificate.not_valid_after_utc:
                raise ValueError('Expired peer certificate')
            self.peer_fingerprint = certificate.fingerprint(hashes.SHA256()).hex()
            self.send({'type': 'HELLO', 'version': 2, 'deviceId': self.identity.device_id,
                       'deviceName': self.name, 'deviceType': 'DESKTOP',
                       'osType': {'Darwin': 'MACOS', 'Windows': 'WINDOWS'}.get(platform.system(), 'LINUX'),
                       'nonce': self.local_nonce, 'supportedFeatures': ['CLIPBOARD', 'FILE_TRANSFER']})
            while not self.closed:
                kind, payload = read_frame(self.conn)
                if kind == 2:
                    if not self.authenticated:
                        raise ValueError('Unpaired file data')
                    self.receive_chunk(*payload)
                else:
                    self.handle(payload)
        except Exception as error:
            if not self.closed:
                self.emit('error', str(error))
        finally:
            self.close()

    def send(self, message):
        with self.lock:
            if self.closed:
                raise ConnectionError('Disconnected')
            self.conn.sendall(json_frame(message))

    def handle(self, msg):
        kind = msg.get('type')
        if kind == 'HELLO':
            if self.peer is not None or msg.get('version') != 2:
                raise ValueError('Legacy or duplicate handshake refused')
            nonce = msg.get('nonce', '')
            if len(nonce) != 32 or any(c not in '0123456789abcdef' for c in nonce):
                raise ValueError('Invalid nonce')
            if not isinstance(msg.get('deviceId'), str) or not 1 <= len(msg['deviceId']) <= 128:
                raise ValueError('Invalid identity')
            self.peer = msg
            transcript = '|'.join(sorted([self.identity.fingerprint + ':' + self.local_nonce,
                                          self.peer_fingerprint + ':' + nonce]))
            self.code = f'{int.from_bytes(hashlib.sha256(transcript.encode()).digest()[:4], "big") % 1000000:06d}'
            pin = self.identity.pins.get(msg['deviceId'])
            if pin is not None and pin != self.peer_fingerprint:
                raise ValueError('Peer certificate changed. Revoke and pair again after checking the device.')
            if pin == self.peer_fingerprint:
                self.confirm(True)
            else:
                self.emit('pair', {'name': str(msg.get('deviceName', 'Device'))[:100], 'code': self.code})
        elif kind == 'HELLO_ACK':
            if msg.get('version') != 2:
                raise ValueError('Legacy peer refused')
        elif kind == 'PAIR_RESPONSE':
            if self.peer is None:
                raise ValueError('Pairing before handshake')
            if msg.get('accepted') is not True:
                self.close(); return
            self.remote_accepted = True
            self.finish_pairing()
        elif kind == 'PAIR_REQUEST':
            pass  # Never display a peer-supplied verification code.
        elif kind == 'PING':
            self.send({'type': 'PONG', 'timestamp': msg.get('timestamp', 0)})
        elif kind == 'PONG':
            pass
        elif kind == 'DISCONNECT':
            self.close()
        elif not self.authenticated:
            raise ValueError('Pairing required before data sharing')
        elif kind == 'CLIPBOARD_TEXT':
            text = msg.get('content')
            if not isinstance(text, str) or len(text.encode()) > 256 * 1024 or msg.get('hash') != hashlib.sha256(text.encode()).hexdigest():
                raise ValueError('Invalid shared text')
            self.emit('text', text)  # Explicit Copy action, no automatic clipboard overwrite.
        elif kind == 'FILE_OFFER':
            tid = str(uuid.UUID(msg['transferId']))
            safe_name(msg['fileName'])
            size = msg.get('fileSize')
            if type(size) is not int or not 0 <= size <= MAX_FILE or tid in self.offers or tid in self.receiving or len(self.offers) + len(self.receiving) >= 4:
                raise ValueError('Invalid or excessive file offer')
            self.offers[tid] = msg
            self.emit('offer', msg)
        elif kind == 'FILE_ACCEPT':
            tid = msg['transferId']
            if msg.get('offset', 0) != 0 or tid not in self.sending:
                raise ValueError('Invalid transfer acceptance')
            path = self.sending.pop(tid)
            threading.Thread(target=self.stream, args=(tid, path), daemon=True).start()
        elif kind == 'FILE_COMPLETE':
            tid = msg['transferId']
            item = self.receiving.pop(tid, None)
            if item is None or item['received'] != item['size']:
                if item:
                    item['handle'].close(); item['temp'].unlink(missing_ok=True)
                raise ValueError('Incomplete file')
            item['handle'].close()
            # Exclusive destination reservation means an existing file is never overwritten.
            destination = self.downloads / safe_name(item['name'])
            for index in range(10000):
                candidate = destination if index == 0 else destination.with_name(f'{destination.stem} ({index}){destination.suffix}')
                try:
                    with candidate.open('xb') as output, item['temp'].open('rb') as source:
                        import shutil
                        shutil.copyfileobj(source, output, 65536)
                    item['temp'].unlink()
                    self.emit('saved', str(candidate)); break
                except FileExistsError:
                    continue
            else:
                raise ValueError('Too many filename collisions')
        elif kind in ('FILE_CANCEL', 'FILE_REJECT'):
            self.cancel(msg['transferId'])

    def confirm(self, accepted):
        if not self.peer or not self.code:
            raise ValueError('No pairing request')
        if accepted:
            self.local_accepted = True
            self.finish_pairing()
        self.send({'type': 'PAIR_RESPONSE', 'accepted': bool(accepted)})
        if not accepted:
            self.close(); return
        self.local_accepted = True
        self.finish_pairing()

    def finish_pairing(self):
        if self.local_accepted and self.remote_accepted and not self.authenticated:
            self.identity.trust(self.peer['deviceId'], self.peer_fingerprint)
            self.authenticated = True
            self.emit('connected', self.peer)

    def send_text(self, text):
        if not self.authenticated or len(text.encode()) > 256 * 1024:
            raise ValueError('Pair first or use shorter text')
        self.send({'type': 'CLIPBOARD_TEXT', 'content': text, 'sourceDeviceId': self.identity.device_id,
                   'timestamp': int(dt.datetime.now().timestamp() * 1000), 'hash': hashlib.sha256(text.encode()).hexdigest()})

    def send_file(self, path):
        if not self.authenticated:
            raise ValueError('Pair first')
        path = Path(path).resolve(strict=True)
        if not path.is_file() or path.stat().st_size > MAX_FILE:
            raise ValueError('Select a file up to 2 GiB')
        if len(self.sending) >= 4:
            raise ValueError('Too many outgoing offers')
        tid = str(uuid.uuid4())
        self.sending[tid] = path
        self.send({'type': 'FILE_OFFER', 'transferId': tid, 'fileName': safe_name(path.name),
                   'fileSize': path.stat().st_size, 'mimeType': 'application/octet-stream'})
        return tid

    def accept_file(self, tid, accepted=True):
        if not self.authenticated:
            raise ValueError('Pair first')
        offer = self.offers.pop(tid)
        if not accepted:
            self.send({'type': 'FILE_REJECT', 'transferId': tid}); return
        import tempfile
        handle = tempfile.NamedTemporaryFile(mode='w+b', prefix='.bridge-', suffix='.part', dir=self.downloads, delete=False)
        self.receiving[tid] = {'handle': handle, 'temp': Path(handle.name), 'size': offer['fileSize'], 'name': offer['fileName'], 'received': 0}
        self.send({'type': 'FILE_ACCEPT', 'transferId': tid, 'offset': 0})

    def receive_chunk(self, tid, offset, data):
        item = self.receiving.get(tid)
        if item is None or offset != item['received'] or offset + len(data) > item['size']:
            raise ValueError('Invalid or out-of-order file data')
        item['handle'].write(data)
        item['received'] += len(data)

    def stream(self, tid, path):
        try:
            offset = 0
            with path.open('rb') as handle:
                while chunk := handle.read(65536):
                    with self.lock:
                        self.conn.sendall(chunk_frame(tid, offset, chunk))
                    offset += len(chunk)
            self.send({'type': 'FILE_COMPLETE', 'transferId': tid})
            self.emit('sent', path.name)
        except Exception as error:
            self.emit('error', str(error)); self.close()

    def cancel(self, tid):
        self.offers.pop(tid, None)
        self.sending.pop(tid, None)
        item = self.receiving.pop(tid, None)
        if item:
            item['handle'].close(); item['temp'].unlink(missing_ok=True)

    def close(self):
        if self.closed:
            return
        self.closed = True
        self.authenticated = False
        try:
            self.raw.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.raw.close()
        for tid in list(self.receiving):
            self.cancel(tid)
        self.emit('disconnected', None)
