#!/usr/bin/env python3
"""Run with a Python environment containing mitmproxy==12.2.3; requires Helm.

python deploy/helm/mock-fleet/tests/mitmproxy-smoke.py
Exercises the rendered addon/config with real HTTP and HTTPS CONNECT clients.
"""
import http.client
import json
import os
from pathlib import Path
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from mitmproxy.certs import CertStore
from ruamel.yaml import YAML

CHART = Path(__file__).resolve().parents[1]
yaml = YAML(typ="safe")


class Backend(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode()
        data = json.dumps({"host": self.headers["Host"], "path": self.path, "body": body}).encode()
        self.send_response(200)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


backend = ThreadingHTTPServer(("127.0.0.1", 0), Backend)
threading.Thread(target=backend.serve_forever, daemon=True).start()
try:
    with tempfile.TemporaryDirectory() as directory:
        home = Path(directory)
        CertStore.create_store(home, "mitmproxy", 2048)
        ca = home / "mitmproxy-ca.pem"
        original_ca = ca.read_bytes()
        ca.chmod(0o444)
        tls = ssl.create_default_context(cafile=str(home / "mitmproxy-ca-cert.pem"))
        for mode in ("PATH", "HOST"):
            rendered = subprocess.check_output([
                "helm", "template", "proxy-test", str(CHART),
                "--set", "fleet.mitmproxy.enabled=true",
                "--set", "fleet.mitmproxy.caSecretName=test-ca",
                "--set", f"fleet.proxy.routing.mode={mode}",
            ], text=True)
            docs = list(yaml.load_all(rendered))
            configmap = next(d for d in docs if d and d["kind"] == "ConfigMap" and d["metadata"]["name"] == "mock-fleet-mitmproxy")
            config = yaml.load(configmap["data"]["config.yaml"])
            assert config["web_password"] == "mitmweb"
            proxy_port, web_port = free_port(), free_port()
            config.update(listen_host="127.0.0.1", listen_port=proxy_port, web_port=web_port)
            with (home / "config.yaml").open("w") as stream:
                yaml.dump(config, stream)
            (home / "route.py").write_text(configmap["data"]["route.py"])
            env = dict(os.environ, FLEET_HOST="mock-fleet.localhost", FLEET_PROXY_HOST="127.0.0.1",
                       FLEET_PROXY_PORT=str(backend.server_port), FLEET_ROUTING_MODE=mode)
            with (home / "process.log").open("w+") as log:
                process = subprocess.Popen([
                    str(Path(sys.executable).with_name("mitmweb")), "--set", f"confdir={home}",
                    "-s", str(home / "route.py"),
                ], env=env, stdout=log, stderr=log)
                try:
                    for _ in range(100):
                        if process.poll() is not None:
                            log.seek(0)
                            raise AssertionError(log.read())
                        try:
                            with socket.create_connection(("127.0.0.1", web_port), timeout=0.1):
                                break
                        except OSError:
                            time.sleep(0.1)
                    else:
                        raise AssertionError("mitmweb did not become ready")
                    host = "mock-fleet.localhost" if mode == "PATH" else "orders.mock-fleet.localhost"
                    path = "/orders/hello?q=%2F" if mode == "PATH" else "/hello?q=%2F"
                    for scheme in ("http", "https"):
                        if scheme == "https":
                            client = http.client.HTTPSConnection("127.0.0.1", proxy_port, context=tls, timeout=5)
                            client.set_tunnel(host, 443)
                            target = path
                        else:
                            client = http.client.HTTPConnection("127.0.0.1", proxy_port, timeout=5)
                            target = f"http://{host}{path}"
                        client.request("POST", target, body="request body")
                        response = client.getresponse()
                        result = response.read()
                        assert response.status == 200, result
                        assert json.loads(result) == {"host": host, "path": path, "body": "request body"}
                        client.close()
                    for foreign_host in ("example.invalid", "mock-fleet.localhost.evil.invalid", "127.0.0.1"):
                        for method, target in (("GET", f"http://{foreign_host}/"), ("CONNECT", f"{foreign_host}:443")):
                            client = http.client.HTTPConnection("127.0.0.1", proxy_port, timeout=5)
                            client.request(method, target)
                            response = client.getresponse()
                            assert response.status == 403, (method, target, response.status, response.read())
                            response.read()
                            client.close()
                    for password, expected in (("wrong", 403), ("mitmweb", 200)):
                        client = http.client.HTTPConnection("127.0.0.1", web_port, timeout=5)
                        client.request("GET", "/flows", headers={"Authorization": f"Bearer {password}"})
                        response = client.getresponse()
                        assert response.status == expected, (response.status, response.read())
                        response.read()
                        client.close()
                    assert ca.read_bytes() == original_ca
                finally:
                    process.terminate()
                    process.wait(timeout=10)
            print(f"{mode}: HTTP, HTTPS CONNECT, rejected destinations, web password, and stable CA passed")
finally:
    backend.shutdown()
    backend.server_close()
