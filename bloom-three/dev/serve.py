"""Dev-only static server for the bloom-three harness. Serves scratch/imarets on 127.0.0.1 with the
MIME types ES modules need (Windows registries often map .js / .mjs to text/plain).

    python bloom-three/dev/serve.py [port]      # from scratch/imarets, default port 8937
    open http://127.0.0.1:8937/bloom-three/dev/index.html?renderer=webgl

Stop it with Ctrl+C when done. Never deployed, never part of the site bundle.
"""
import http.server
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))


class Handler(http.server.SimpleHTTPRequestHandler):
    extensions_map = {
        **http.server.SimpleHTTPRequestHandler.extensions_map,
        ".js": "text/javascript",
        ".mjs": "text/javascript",
        ".map": "application/json",
        ".css": "text/css",
        ".html": "text/html",
    }

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=ROOT, **kwargs)

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8937
    with http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler) as httpd:
        print(f"bloom-three dev harness: http://127.0.0.1:{port}/bloom-three/dev/index.html?renderer=webgl")
        httpd.serve_forever()
