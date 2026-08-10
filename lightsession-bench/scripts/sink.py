"""Aceita tudo e responde 200, contando o que chegou.

Existe para o bench ter um ingest de verdade do outro lado. Sem ele o SDK manda para um
endereco que nao existe, espera timeout, e a medicao vira uma medicao de timeout de rede.
"""
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

count = 0
total = 0


class Sink(BaseHTTPRequestHandler):
    def _handle(self):
        global count, total
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        count += 1
        total += length
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        body = b'{"ok":true}'
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        print(f"{self.command} {self.path} {length}B", flush=True)

    do_POST = _handle
    do_PUT = _handle
    do_GET = _handle

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1])
    server = ThreadingHTTPServer(("127.0.0.1", port), Sink)
    print(f"sink on {port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        print(f"port {port}: {count} requests, {total} bytes", flush=True)
