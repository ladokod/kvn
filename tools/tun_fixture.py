#!/usr/bin/env python3
"""Disposable localhost Naive/H2 + TLS assets + DoH fixture. Never uses the user's server.
Only the fixed ready marker is printed; credentials/key/config live in a mode-0700 temp dir.
"""
import argparse, base64, hashlib, http.server, ipaddress, json, os, secrets, signal
import socket, ssl, struct, subprocess, threading, time, zlib
from pathlib import Path
from collections import Counter
from urllib.parse import urlsplit, parse_qs

PAYLOAD = bytes(range(256)) * 8192  # 2 MiB, deterministic integrity check
def png_chunk(kind, data):
    return struct.pack('!I', len(data)) + kind + data + struct.pack('!I', zlib.crc32(kind + data) & 0xffffffff)

PNG = (b'\x89PNG\r\n\x1a\n' + png_chunk(b'IHDR', struct.pack('!IIBBBBB', 1, 1, 8, 2, 0, 0, 0)) +
       png_chunk(b'IDAT', zlib.compress(b'\x00\x12\x34\x56')) + png_chunk(b'IEND', b''))


COUNTERS = Counter()
COUNTER_LOCK = threading.Lock()

def count(event):
    with COUNTER_LOCK: COUNTERS[event] += 1

class Server(http.server.ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 64

class Server6(Server):
    address_family = socket.AF_INET6
    def server_bind(self):
        self.socket.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 1)
        super().server_bind()

class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def log_message(self, *_): pass
    def respond(self, body, mime='application/octet-stream', length=None):
        self.send_response(200)
        self.send_header('Content-Type', mime)
        self.send_header('Content-Length', str(len(body) if length is None else length))
        self.send_header('Connection', 'close')
        self.end_headers()
        try: self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError, ssl.SSLError): pass
        self.close_connection = True
    def do_POST(self):
        if urlsplit(self.path).path != '/dns-query':
            self.send_error(404); return
        data = self.rfile.read(min(65535, int(self.headers.get('Content-Length', '0'))))
        self.dns(data)
    def dns(self, data):
        try:
            offset, labels = 12, []
            while data[offset]:
                n = data[offset]; offset += 1
                labels.append(data[offset:offset+n].decode('ascii')); offset += n
            offset += 1
            qtype, qclass = struct.unpack('!HH', data[offset:offset+4]); end = offset + 4
            name = '.'.join(labels).lower()
            count("dns.query_type_" + str(qtype))
            known = name in ('html.fixture', 'assets.fixture', 'parallel.fixture', 'v4.fixture', 'v6.fixture')
            address = None
            if known and qclass == 1:
                if qtype == 1 and name != 'v6.fixture': address = '198.18.0.77'
                if qtype == 28 and name != 'v4.fixture': address = '2001:db8::77'
            answer = b''
            if address:
                count("dns.answer_v6" if ":" in address else "dns.answer_v4")
                packed = ipaddress.ip_address(address).packed
                answer = b'\xc0\x0c' + struct.pack('!HHIH', qtype, 1, 60, len(packed)) + packed
            header = data[:2] + struct.pack('!HHHHH', 0x8180, 1, int(bool(answer)), 0, 0)
            self.respond(header + data[12:end] + answer, 'application/dns-message')
        except (IndexError, ValueError, struct.error, UnicodeError): self.send_error(400)
    def do_GET(self):
        path = urlsplit(self.path).path
        if path in ("/page.html", "/site.css", "/pixel.png", "/large", "/parallel", "/truncated"): count("http" + path.replace("/", "_"))
        if path == '/dns-query':
            encoded = parse_qs(urlsplit(self.path).query).get('dns', [''])[0]
            try: self.dns(base64.urlsafe_b64decode(encoded + '=' * (-len(encoded) % 4)))
            except ValueError: self.send_error(400)
        elif path == '/page.html':
            port = self.server.server_port
            self.respond((f'<html><link rel="stylesheet" href="https://assets.fixture:{port}/site.css">'
                          f'<img src="https://assets.fixture:{port}/pixel.png">fixture</html>').encode(), 'text/html')
        elif path == '/site.css': self.respond(b'body { color: #123456; }', 'text/css')
        elif path == '/pixel.png': self.respond(PNG, 'image/png')
        elif path == '/large': self.respond(PAYLOAD)
        elif path == '/parallel': self.respond(PAYLOAD[:262144])
        elif path == '/truncated': self.respond(PAYLOAD[:512], length=65536)
        else: self.send_error(404)

def free_port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0)); return s.getsockname()[1]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--binary', required=True)
    parser.add_argument('--directory', required=True)
    args = parser.parse_args()
    root = Path(args.directory); root.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.umask(0o077)
    cert, key = root/'ca.pem', root/'key.pem'
    names = ','.join('DNS:'+n for n in ('proxy.fixture','doh.fixture','html.fixture','assets.fixture','parallel.fixture','v4.fixture','v6.fixture'))
    subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-days','1',
        '-keyout',str(key),'-out',str(cert),'-subj','/CN=proxy.fixture',
        '-addext','basicConstraints=critical,CA:TRUE',
        '-addext','subjectAltName='+names+',IP:198.18.0.77,IP:2001:db8::77'],
        check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); context.load_cert_chain(cert, key)
    http4 = Server(('127.0.0.1', 0), Handler)
    http6 = Server6(('::1', http4.server_port), Handler)
    doh = Server(('127.0.0.1', 0), Handler)
    for server in (http4, http6, doh):
        server.socket = context.wrap_socket(server.socket, server_side=True)
        threading.Thread(target=server.serve_forever, daemon=True).start()
    user, password = secrets.token_hex(12), secrets.token_hex(24)
    proxy_port = free_port()
    config = {'log': {'disabled': True}, 'inbounds': [{'type':'naive','tag':'fixture-naive',
        'listen':'127.0.0.1','listen_port':proxy_port,'network':'tcp',
        'users':[{'username':user,'password':password}],
        'tls':{'enabled':True,'certificate_path':str(cert),'key_path':str(key),'alpn':['h2']}}],
        'outbounds':[{'type':'direct','tag':'fixture-direct'}],
        'route':{'final':'fixture-direct','rules':[
            {'domain_suffix':['.fixture'],'action':'route-options','override_address':'127.0.0.1'},
            {'ip_cidr':['198.18.0.0/15'],'action':'route-options','override_address':'127.0.0.1'},
            {'ip_cidr':['2001:db8::/32'],'action':'route-options','override_address':'::1'}]}}
    config_path = root/'server.json'; config_path.write_text(json.dumps(config))
    process = subprocess.Popen([args.binary,'run','-c',str(config_path)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    def stop(*_):
        process.terminate()
        raise SystemExit(0)
    signal.signal(signal.SIGTERM, stop); signal.signal(signal.SIGINT, stop)
    try:
        for _ in range(100):
            if process.poll() is not None: raise RuntimeError('fixture proxy failed')
            try:
                with socket.create_connection(('127.0.0.1', proxy_port), timeout=.1): break
            except OSError: time.sleep(.1)
        else: raise RuntimeError('fixture proxy did not start')
        check = ssl.create_default_context(cafile=str(cert)); check.set_alpn_protocols(['h2'])
        with check.wrap_socket(socket.create_connection(('127.0.0.1', proxy_port)), server_hostname='proxy.fixture') as connection:
            if connection.selected_alpn_protocol() != 'h2': raise RuntimeError('fixture h2 ALPN check failed')
        metadata = {'proxy_port':proxy_port, 'content_port':http4.server_port, 'doh_port':doh.server_port,
            'username':user,'password':password,'ca':cert.read_text(),
            'large_sha256':hashlib.sha256(PAYLOAD).hexdigest(), 'png_sha256':hashlib.sha256(PNG).hexdigest()}
        (root/'fixture.json').write_text(json.dumps(metadata))
        print('FIXTURE.ready', flush=True)
        process.wait()
    finally:
        with COUNTER_LOCK: (root/"metrics.json").write_text(json.dumps(dict(COUNTERS)))
        if process.poll() is None: process.terminate(); process.wait(timeout=5)

if __name__ == '__main__': main()
