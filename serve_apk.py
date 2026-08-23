#!/usr/bin/env python3
"""Serve the debug APK for downloading over the local network."""

from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import socket

ROOT = Path(__file__).resolve().parent
APK = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"


class Handler(SimpleHTTPRequestHandler):
    def do_GET(self):
        if self.path in ("/", "/index.html"):
            if not APK.exists():
                self.send_error(404, "APK not found. Run: gradle :app:assembleDebug")
                return

            body = f"""<!doctype html>
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>JONIS 30</title>
<style>
body{{font-family:system-ui,sans-serif;max-width:480px;margin:15vh auto;padding:24px;background:#111;color:#fff}}
a{{display:block;background:#d7ff45;color:#111;padding:18px;border-radius:14px;text-align:center;font-weight:800;text-decoration:none}}
p{{color:#aaa}}
</style>
<h1>JONIS 30</h1><p>Download the birthday arcade APK.</p>
<a href="/app-debug.apk">Download APK</a>
""".encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        if self.path == "/app-debug.apk":
            if not APK.exists():
                self.send_error(404, "APK not found. Run: gradle :app:assembleDebug")
                return
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Length", str(APK.stat().st_size))
            self.send_header("Content-Disposition", 'attachment; filename="jonis-30.apk"')
            self.end_headers()
            with APK.open("rb") as file:
                while chunk := file.read(1024 * 1024):
                    self.wfile.write(chunk)
            return

        self.send_error(404)


def local_ip():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("8.8.8.8", 80))
        return sock.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        sock.close()


if __name__ == "__main__":
    port = 8000
    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    print(f"Open this on your phone: http://{local_ip()}:{port}")
    print("Press Ctrl+C to stop the server.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nServer stopped.")
    finally:
        server.server_close()
