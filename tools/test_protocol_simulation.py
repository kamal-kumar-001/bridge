#!/usr/bin/env python3
"""
Automated Protocol Simulation Test:
Verifies Bridge Protocol v1 framing, handshake, pairing, clipboard,
and file chunk streaming between simulated Android and Desktop nodes using socket pairs.
"""

import os
import sys
import time
import json
import uuid
import socket
import struct
import threading
import tempfile

from desktop_bridge_agent import (
    BridgeFraming,
    FRAME_JSON,
    FRAME_FILE_CHUNK
)

def run_test():
    print("[*] Starting Bridge Protocol v1 Simulation Test...")

    # Create dummy file data for transfer test
    test_data = b"Bridge Cross-Platform Data Stream" * 2048  # ~67 KB
    received_chunks = bytearray()
    test_passed = threading.Event()

    # Use socketpair for sandbox-safe full duplex IPC
    android_sock, desktop_sock = socket.socketpair()

    # 1. Simulated Android Node
    def android_node(conn):
        print("[Android Node] Session initialized")

        # Send HELLO
        hello = {
            "type": "HELLO",
            "version": 1,
            "deviceId": "android-uuid-1234",
            "deviceName": "Pixel 9",
            "deviceType": "PHONE",
            "osType": "ANDROID",
            "supportedFeatures": ["CLIPBOARD", "FILE_TRANSFER"]
        }
        conn.sendall(BridgeFraming.pack_json(hello))

        while True:
            ftype, payload = BridgeFraming.read_frame(conn)
            if ftype is None:
                break
            if ftype == FRAME_JSON:
                mtype = payload.get("type")
                print(f"[Android Node] Received JSON: {mtype}")

                if mtype == "HELLO_ACK":
                    assert payload.get("osType") in ["MACOS", "WINDOWS", "LINUX"]
                    # Request Pairing
                    conn.sendall(BridgeFraming.pack_json({
                        "type": "PAIR_REQUEST",
                        "pin": "482195",
                        "nonce": "test-nonce"
                    }))

                elif mtype == "PAIR_RESPONSE":
                    assert payload.get("accepted") is True
                    print("[Android Node] Pairing confirmed!")
                    # Send clipboard
                    conn.sendall(BridgeFraming.pack_json({
                        "type": "CLIPBOARD_TEXT",
                        "content": "Copied from Phone",
                        "timestamp": int(time.time() * 1000),
                        "sourceDeviceId": "android-uuid-1234",
                        "hash": "hash123"
                    }))

                elif mtype == "FILE_OFFER":
                    tid = payload.get("transferId")
                    # Accept file
                    conn.sendall(BridgeFraming.pack_json({
                        "type": "FILE_ACCEPT",
                        "transferId": tid,
                        "offset": 0
                    }))

                elif mtype == "FILE_COMPLETE":
                    print("[Android Node] File transfer complete!")
                    test_passed.set()
                    break

            elif ftype == FRAME_FILE_CHUNK:
                tid, offset, chunk_data = payload
                received_chunks.extend(chunk_data)

        conn.close()

    # 2. Simulated Desktop Node (macOS / Windows / Linux)
    def desktop_node(conn):
        # Read Android HELLO
        ftype, payload = BridgeFraming.read_frame(conn)
        assert ftype == FRAME_JSON and payload.get("type") == "HELLO"
        assert payload.get("osType") == "ANDROID"
        print(f"[Desktop Node] Received HELLO from {payload.get('deviceName')}")

        # Send HELLO_ACK
        ack = {
            "type": "HELLO_ACK",
            "version": 1,
            "deviceId": "desktop-uuid-5678",
            "deviceName": "MacBook Pro",
            "deviceType": "DESKTOP",
            "osType": "MACOS",
            "isPaired": False
        }
        conn.sendall(BridgeFraming.pack_json(ack))

        # Read PAIR_REQUEST
        ftype, payload = BridgeFraming.read_frame(conn)
        assert ftype == FRAME_JSON and payload.get("type") == "PAIR_REQUEST"
        print(f"[Desktop Node] Received PAIR_REQUEST with PIN: {payload.get('pin')}")
        conn.sendall(BridgeFraming.pack_json({
            "type": "PAIR_RESPONSE",
            "accepted": True
        }))

        # Read CLIPBOARD_TEXT
        ftype, payload = BridgeFraming.read_frame(conn)
        assert ftype == FRAME_JSON and payload.get("type") == "CLIPBOARD_TEXT"
        print(f"[Desktop Node] Received CLIPBOARD_TEXT: \"{payload.get('content')}\"")

        # Send FILE_OFFER
        tid = str(uuid.uuid4())
        fsize = len(test_data)
        conn.sendall(BridgeFraming.pack_json({
            "type": "FILE_OFFER",
            "transferId": tid,
            "fileName": "test_document.dat",
            "fileSize": fsize,
            "mimeType": "application/octet-stream"
        }))

        # Read FILE_ACCEPT
        ftype, payload = BridgeFraming.read_frame(conn)
        assert ftype == FRAME_JSON and payload.get("type") == "FILE_ACCEPT"
        print(f"[Desktop Node] Peer accepted file transfer. Streaming chunks...")

        # Stream chunks in 32KB pieces
        chunk_size = 32768
        sent = 0
        tid_uuid = uuid.UUID(tid)
        while sent < fsize:
            chunk = test_data[sent:sent + chunk_size]
            conn.sendall(BridgeFraming.pack_chunk(tid_uuid, sent, chunk))
            sent += len(chunk)

        # Send FILE_COMPLETE
        conn.sendall(BridgeFraming.pack_json({
            "type": "FILE_COMPLETE",
            "transferId": tid
        }))

        time.sleep(0.3)
        conn.close()

    t_srv = threading.Thread(target=android_node, args=(android_sock,), daemon=True)
    t_cli = threading.Thread(target=desktop_node, args=(desktop_sock,), daemon=True)

    t_srv.start()
    t_cli.start()

    success = test_passed.wait(timeout=10)
    if not success:
        print("[!] Test timed out or failed!")
        sys.exit(1)

    assert len(received_chunks) == len(test_data), f"Size mismatch: {len(received_chunks)} vs {len(test_data)}"
    assert received_chunks == test_data, "Data corruption in binary chunks!"

    print("\n=======================================================")
    print("  [✓] All Bridge Protocol v1 tests passed successfully!")
    print(f"  [✓] Verified: Framing, Handshake, Pairing PIN, Clipboard, and Chunk Streaming ({len(received_chunks):,} bytes)")
    print("=======================================================\n")

if __name__ == "__main__":
    run_test()
