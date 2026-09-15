#!/usr/bin/env python3
"""Check a deployed proxy: python3 tinyproxy-smoke.py [proxy-host] [fleet-host].

Requires curl, trusted ingress certificates, and both proxy listeners reachable.
Fleet's HTTP endpoint is expected to redirect to HTTPS.
"""
import subprocess
import sys

proxy_host = sys.argv[1] if len(sys.argv) > 1 else "tinyproxy.minikube.localhost"
fleet_host = sys.argv[2] if len(sys.argv) > 2 else "mock-fleet.minikube.localhost"
for proxy in (f"http://{proxy_host}:8080", f"https://{proxy_host}:8433"):
    for scheme, expected in (("http", {301, 302, 307, 308}), ("https", {200})):
        result = subprocess.run([
            "curl", "--noproxy", "", "--proxy", proxy, "--max-time", "15",
            "--silent", "--show-error", "--output", "/dev/null", "--write-out", "%{http_code}",
            f"{scheme}://{fleet_host}/__fleet/proxy/health/ready",
        ], text=True, capture_output=True)
        assert result.returncode == 0 and int(result.stdout) in expected, (proxy, scheme, result)
    for url in ("http://example.com/", "http://127.0.0.1/", f"http://{fleet_host}.example.com/"):
        result = subprocess.check_output([
            "curl", "--noproxy", "", "--proxy", proxy, "--max-time", "15",
            "--silent", "--output", "/dev/null", "--write-out", "%{http_code}", url,
        ], text=True)
        assert result == "403", (proxy, url, result)
    for authority in ("example.com:443", f"{fleet_host}:444"):
        result = subprocess.run([
            "curl", "--noproxy", "", "--proxy", proxy, "--max-time", "15",
            "--silent", "--output", "/dev/null", "--write-out", "%{http_connect}",
            f"https://{authority}/",
        ], text=True, capture_output=True)
        assert result.stdout == "403", (proxy, authority, result)
print("Both proxy listeners passed HTTP, HTTPS CONNECT, and destination rejection checks")
