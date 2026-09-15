#!/usr/bin/env python3
"""Run with python3 deploy/helm/mock-fleet/tests/mitmproxy-chart.py (requires Helm)."""
import json
import subprocess
import tempfile
from pathlib import Path

CHART = Path(__file__).resolve().parents[1]


def render(values, valid=True, minikube=False):
    with tempfile.NamedTemporaryFile(mode="w", suffix=".json") as source:
        json.dump(values, source)
        source.flush()
        result = subprocess.run(
            ["helm", "template", "proxy-test", str(CHART)]
            + (["-f", str(CHART / "values.minikube.yaml")] if minikube else [])
            + ["-f", source.name],
            capture_output=True, text=True,
        )
    assert (result.returncode == 0) == valid, result.stderr
    return result.stdout


assert "name: mock-fleet-mitmproxy" not in render({})
local = render({}, minikube=True)
assert "name: mock-fleet-mitmproxy" in local
assert 'host: "mitmweb.minikube.localhost"' in local
assert 'HostSNI(`mitmproxy.minikube.localhost`)' in local
assert "kind: IngressRouteTCP" in local and "tls: {}" in local
assert 'ingressClassName: "traefik"' in local
assert '    - "websecure"' in local
assert "type: LoadBalancer" not in local
assert "kind: IngressRouteTCP" not in render({"fleet": {"mitmproxy": {"ingress": {"enabled": False}}}}, minikube=True)
render({"fleet": {"mitmproxy": {"ingress": {"webHost": "same.localhost", "proxyHost": "same.localhost"}}}}, valid=False, minikube=True)
custom_tls = render({"fleet": {"mitmproxy": {"ingress": {"tlsSecretName": "ingress-cert"}}}}, minikube=True)
assert custom_tls.count('secretName: "ingress-cert"') == 2
assert "name: mock-fleet-mitmproxy" not in render({"fleet": {"mitmproxy": {"enabled": False}}}, minikube=True)
values = {"fleet": {"mitmproxy": {"enabled": True, "caSecretName": "test-ca"}}}
output = render(values)
assert "name: mock-fleet-mitmproxy" in output
assert "command: [mitmweb]" in output
assert "web_password: mitmweb" in output
assert "port: 8888" in output
assert "secretName: \"test-ca\"" in output
assert "/home/mitmproxy/.mitmproxy/config.yaml" in output
assert "checksum/config:" in output
render({"fleet": {"mitmproxy": {"enabled": True}}}, valid=False)
for config in ({"listen_port": 9999}, {"mode": ["reverse:http://example.com"]},
               {"connection_strategy": "eager"}, {"upstream_cert": True}):
    render({"fleet": {"mitmproxy": {"enabled": True, "caSecretName": "test-ca", "config": config}}}, valid=False)
policy = next(doc for doc in output.split("---\n") if "# Source: mock-fleet/templates/mitmproxy-networkpolicy.yaml" in doc)
assert "policyTypes: [Ingress, Egress]" in policy
assert policy.count("    - to:") == 2
assert "ipBlock:" not in policy
assert "app.kubernetes.io/instance: proxy-test" in policy
assert "app.kubernetes.io/component: proxy" in policy
assert 'kubernetes.io/metadata.name: "kube-system"' in policy
assert "k8s-app: kube-dns" in policy
assert policy.count("port: 53") == 2
assert "port: 8080" in policy
service = next(doc for doc in output.split("---\n") if "# Source: mock-fleet/templates/mitmproxy-service.yaml" in doc)
assert "port: 8081" in service and "port: 8888" in service
assert "port: 8081" in policy
assert "web_host: 0.0.0.0" in output
custom = render({"fullnameOverride": "custom", "namespaceOverride": "custom-ns", "clusterDomain": "example.internal",
                 "fleet": {"mitmproxy": {"enabled": True, "caSecretName": "custom-ca", "config": {"termlog_verbosity": "debug"}}}})
assert 'value: "custom-proxy.custom-ns.svc.example.internal"' in custom
assert "namespace: custom-ns" in custom
assert "termlog_verbosity: debug" in custom
checksum = lambda text: next(line.strip() for line in text.splitlines() if "checksum/config:" in line)
changed = render({"fleet": {"mitmproxy": {"enabled": True, "caSecretName": "test-ca", "config": {"web_password": "different"}}}})
assert checksum(output) != checksum(changed)
print("Mitmproxy render checks passed")
