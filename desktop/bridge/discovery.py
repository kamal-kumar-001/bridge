"""Discovery advertises presence only. Certificates and pairing establish identity."""
import json
import socket
import threading
from zeroconf import Zeroconf, ServiceInfo


class Discovery:
    def __init__(self, identity, name, port):
        self.identity, self.name, self.port = identity, name, port
        self.stop_event = threading.Event()
        self.zeroconf = None
        self.service = None

    def start(self):
        # mDNS is optional on networks that block multicast. Manual IP remains available.
        try:
            addresses = [socket.inet_aton(address) for address in socket.gethostbyname_ex(socket.gethostname())[2]
                         if not address.startswith('127.')]
            self.zeroconf = Zeroconf()
            self.service = ServiceInfo('_bridge._tcp.local.', f'Bridge-{self.identity.device_id[:12]}._bridge._tcp.local.',
                                       addresses=addresses, port=self.port,
                                       properties={'id': self.identity.device_id, 'name': self.name, 'os': {'Darwin': 'MACOS', 'Windows': 'WINDOWS'}.get(__import__('platform').system(), 'LINUX'), 'proto': '2'})
            self.zeroconf.register_service(self.service)
        except Exception:
            self.close_mdns()
        threading.Thread(target=self.beacon, daemon=True).start()

    def beacon(self):
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp:
            udp.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            data = json.dumps({'proto': 'bridge', 'id': self.identity.device_id, 'name': self.name,
                               'os': {'Darwin': 'MACOS', 'Windows': 'WINDOWS'}.get(__import__('platform').system(), 'LINUX'), 'port': self.port, 'version': 2}).encode()
            while not self.stop_event.is_set():
                try:
                    udp.sendto(data, ('255.255.255.255', self.port))
                except OSError:
                    pass
                self.stop_event.wait(10)

    def close_mdns(self):
        if self.zeroconf:
            if self.service:
                self.zeroconf.unregister_service(self.service)
            self.zeroconf.close()
            self.zeroconf = None

    def close(self):
        self.stop_event.set()
        self.close_mdns()
