"""Small native desktop interface. All network callbacks cross a UI event queue."""
import argparse
import os
from pathlib import Path
import queue
import socket
import threading
import tkinter as tk
from tkinter import ttk, filedialog, messagebox
from .core import Identity, Session, PORT


class App:
    def __init__(self, root, state_dir=None, port=PORT):
        self.root = root
        self.identity = Identity(state_dir or Path.home() / '.bridge')
        self.downloads = Path.home() / 'Downloads' / 'Bridge'
        self.events = queue.Queue()
        self.session = None
        self.port = port
        self.listener = None
        self.discovery = None
        self.closed = False
        root.title('Bridge - Your devices, together')
        root.geometry('820x700'); root.minsize(680, 650)
        style = ttk.Style(root)
        style.theme_use('clam')
        style.configure('TFrame', background='#f5f7fb')
        style.configure('TLabel', background='#f5f7fb', foreground='#1b2741', font=('Helvetica', 11))
        style.configure('Title.TLabel', font=('Helvetica', 26, 'bold'))
        style.configure('TButton', padding=(14, 8))
        body = ttk.Frame(root, padding=28); body.pack(fill='both', expand=True)
        ttk.Label(body, text='Bridge', style='Title.TLabel').pack(anchor='w')
        ttk.Label(body, text='Local sharing. Your devices. No cloud account.').pack(anchor='w', pady=(4, 20))
        self.status = tk.StringVar(value='Starting local listener...')
        ttk.Label(body, textvariable=self.status, wraplength=740).pack(anchor='w', pady=(0, 14))
        row = ttk.Frame(body); row.pack(fill='x')
        self.host = ttk.Entry(row); self.host.pack(side='left', fill='x', expand=True)
        self.host.insert(0, '')
        ttk.Button(row, text='Connect by IP', command=self.connect).pack(side='left', padx=(8, 0))
        ttk.Button(row, text='Disconnect', command=self.disconnect).pack(side='left', padx=8)
        ttk.Label(body, text='Send text or a link').pack(anchor='w', pady=(24, 6))
        self.text = tk.Text(body, height=4, wrap='word', font=('Helvetica', 12), relief='flat', padx=12, pady=12)
        self.text.pack(fill='x')
        actions = ttk.Frame(body); actions.pack(fill='x', pady=10)
        self.send_button = ttk.Button(actions, text='Send text', command=self.send_text, state='disabled'); self.send_button.pack(side='left')
        self.file_button = ttk.Button(actions, text='Choose file...', command=self.send_file, state='disabled'); self.file_button.pack(side='left', padx=8)
        ttk.Button(actions, text='Forget connected device', command=self.revoke).pack(side='right')
        ttk.Label(body, text='Activity').pack(anchor='w', pady=(18, 6))
        self.activity = tk.Text(body, height=9, wrap='word', state='disabled', relief='flat', padx=12, pady=10)
        self.activity.pack(fill='both', expand=True)
        ttk.Label(body, text='Files require acceptance. Received text never replaces your clipboard automatically.', wraplength=740).pack(anchor='w', pady=(12, 0))
        root.protocol('WM_DELETE_WINDOW', self.close)
        root.after(80, self.poll)
        threading.Thread(target=self.listen, daemon=True).start()

    def log(self, text):
        self.activity.configure(state='normal'); self.activity.insert('end', text + '\n'); self.activity.see('end'); self.activity.configure(state='disabled')

    def attach(self, raw, incoming):
        if self.session and not self.session.closed:
            raw.close(); return
        holder = {}
        session = Session(raw, incoming, self.identity, socket.gethostname(), self.downloads,
                          lambda kind, data: self.events.put((holder.get('session'), kind, data)))
        holder['session'] = session
        self.session = session
        session.start()

    def listen(self):
        try:
            self.listener = socket.socket()
            self.listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self.listener.bind(('0.0.0.0', self.port)); self.listener.listen(4)
            self.port = self.listener.getsockname()[1]
            self.events.put((None, 'listening', self.port))
            if self.port:
                from .discovery import Discovery
                self.discovery = Discovery(self.identity, socket.gethostname(), self.port)
                self.discovery.start()
            while not self.closed:
                raw, _ = self.listener.accept()
                self.events.put((None, 'incoming', raw))
        except OSError as error:
            if not self.closed:
                self.events.put((None, 'error', str(error)))

    def connect(self):
        target = self.host.get().strip()
        self.status.set('Connecting...')
        def worker():
            try:
                raw = socket.create_connection((target, self.port), timeout=10)
                raw.settimeout(None)
                self.events.put((None, 'outgoing', raw))
            except Exception as error:
                self.events.put((None, 'error', str(error)))
        threading.Thread(target=worker, daemon=True).start()

    def poll(self):
        while not self.events.empty():
            session, kind, data = self.events.get_nowait()
            if kind == 'listening':
                self.status.set(f'Listening on port {data} - same Wi-Fi or phone hotspot required')
                continue
            if kind in ('incoming', 'outgoing'):
                self.attach(data, kind == 'incoming'); continue
            if session is not None and session is not self.session:
                continue
            if kind == 'pair':
                self.status.set('Compare the code on both devices')
                accepted = messagebox.askyesno('Pair with ' + data['name'], 'Only allow if BOTH devices show:\n\n' + data['code'] + '\n\nDo the codes match?', parent=self.root)
                try:
                    session.confirm(accepted)
                except Exception as error:
                    self.log(str(error))
            elif kind == 'connected':
                self.status.set('Paired with ' + str(data.get('deviceName', 'Device'))[:100])
                self.send_button.configure(state='normal'); self.file_button.configure(state='normal')
                self.log('Encrypted connection ready')
            elif kind == 'offer':
                accepted = messagebox.askyesno('Receive file?', f"{data['fileName']}\n{data['fileSize']:,} bytes\nSave to Downloads/Bridge?", parent=self.root)
                try:
                    session.accept_file(data['transferId'], accepted)
                except Exception as error:
                    self.log(str(error))
            elif kind == 'text':
                self.log('Received text (select and copy):\n' + data)
            elif kind == 'disconnected':
                self.status.set('Disconnected - ready for a new connection')
                self.send_button.configure(state='disabled'); self.file_button.configure(state='disabled')
            else:
                self.log(kind.capitalize() + ': ' + str(data))
        if not self.closed:
            self.root.after(80, self.poll)

    def send_text(self):
        try:
            self.session.send_text(self.text.get('1.0', 'end-1c')); self.log('Text sent')
        except Exception as error:
            self.log(str(error))

    def send_file(self):
        path = filedialog.askopenfilename(parent=self.root)
        if path:
            try:
                self.session.send_file(path); self.log('File offered: ' + Path(path).name)
            except Exception as error:
                self.log(str(error))

    def disconnect(self):
        if self.session:
            self.session.close()

    def revoke(self):
        if self.session and self.session.peer:
            self.identity.revoke(self.session.peer['deviceId']); self.disconnect(); self.log('Device forgotten. Pair again to reconnect.')

    def close(self):
        self.closed = True; self.disconnect()
        if self.listener:
            self.listener.close()
        if self.discovery:
            self.discovery.close()
        self.root.destroy()


def main():
    parser = argparse.ArgumentParser(description='Bridge desktop companion')
    parser.add_argument('--port', type=int, default=PORT)
    parser.add_argument('--state-dir', type=Path)
    args = parser.parse_args()
    root = tk.Tk(); App(root, args.state_dir, args.port); root.mainloop()


if __name__ == '__main__':
    main()
