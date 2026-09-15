#!/usr/bin/env python3
"""Run with python3 deploy/helm/mock-fleet/tests/tinyproxy-chart.py (requires Helm)."""
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



def document(output, source):
    return next(doc for doc in output.split("---\n") if f"# Source: mock-fleet/templates/{source}" in doc)

assert "name: mock-fleet-tinyproxy" not in render({})
local = render({}, minikube=True)
assert local.count("kind: IngressRouteTCP") == 2
assert 'HostSNI(`tinyproxy.minikube.localhost`)' in local
assert 'HostSNI(`*`)' in local
assert '"tinyproxy-http"' in local and '"tinyproxy-https"' in local
assert "tls: {}" in local
service = document(local, "tinyproxy-service.yaml")
assert "type: ClusterIP" in service and "port: 8080" in service
assert "port: 8433" not in service and "loadBalancerClass:" not in service
assert "tinyproxy-ca" not in local and "route.py" not in local
assert "web_password" not in local and "containerPort: 8081" not in local
assert 'image: "ghcr.io/letsrokk/mock-fleet/tinyproxy:latest"' in local
assert "mountPath: /etc/tinyproxy" in local
policy = document(local, "tinyproxy-networkpolicy.yaml")
assert 'kubernetes.io/metadata.name: "traefik"' in policy
assert "app.kubernetes.io/name: traefik" in policy
assert "app.kubernetes.io/component: controller" not in policy
assert "port: 8000" in policy and "port: 8443" in policy
assert policy.count("port: 53") == 2
assert "ipBlock:" not in policy
assert "name: mock-fleet-tinyproxy" not in render({"fleet": {"tinyproxy": {"enabled": False}}}, minikube=True)
aws_annotations = {
    "service.beta.kubernetes.io/aws-load-balancer-ssl-cert": "arn:aws:acm:test",
    "service.beta.kubernetes.io/aws-load-balancer-ssl-ports": "8433",
    "service.beta.kubernetes.io/aws-load-balancer-backend-protocol": "tcp",
}
aws = {"fleet": {"tinyproxy": {
    "enabled": True, "ingress": {"enabled": False},
    "service": {"loadBalancerClass": "service.k8s.aws/nlb", "annotations": aws_annotations,
                "loadBalancerSourceRanges": ["10.20.0.0/16"]},
    "networkPolicy": {"ingressPodSelector": None, "allowedCidrs": ["10.30.0.0/24"]},
}}}
output = render(aws)
assert "kind: IngressRouteTCP" not in output
service = document(output, "tinyproxy-service.yaml")
assert "type: LoadBalancer" in service and 'loadBalancerClass: "service.k8s.aws/nlb"' in service
assert "port: 8080" in service and "port: 8433" in service
policy = document(output, "tinyproxy-networkpolicy.yaml")
assert 'cidr: "10.30.0.0/24"' in policy and "ingress-nginx" not in policy
render({"fleet": {"tinyproxy": {"enabled": True, "ingress": {"enabled": False}}}}, valid=False)
for annotation in list(aws_annotations):
    removed = aws_annotations.pop(annotation)
    render(aws, valid=False)
    aws_annotations[annotation] = removed
for change in ({"service": {"ports": {"http": 8433}}},
               {"ingress": {"enabled": "yes"}},
               {"ingress": {"httpEntryPoint": "tinyproxy-https"}},
               {"config": {"Port": 1234}}, {"config": {"pOrT": 1234}}, {"config": {"FilterDefaultDeny": "No"}},
               {"config": {"LogLevel": "Info\nPort 1234"}}):
    render({"fleet": {"tinyproxy": change}}, valid=False, minikube=True)
changed = render({"fleet": {"tinyproxy": {"config": {"Timeout": 300}}}}, minikube=True)
checksum = lambda text: next(line for line in text.splitlines() if "checksum/config:" in line)
assert checksum(local) != checksum(changed)
assert '^mock-fleet\\.minikube\\.localhost$' in local
host = render({"fleet": {"proxy": {"routing": {"mode": "HOST"}}}}, minikube=True)
assert '([a-z0-9]([a-z0-9-]*[a-z0-9])?\\.)?' in host
print("Tinyproxy chart checks passed")
