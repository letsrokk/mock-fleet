#!/usr/bin/env bash
set -euo pipefail
REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
# Keep the installed controller version and its values, including the TLS store.
chart_version=$(helm list -n traefik -o json | python3 -c 'import json,sys; print(next(r["chart"].removeprefix("traefik-") for r in json.load(sys.stdin) if r["name"] == "traefik"))')
helm repo add traefik https://traefik.github.io/charts --force-update
helm repo update traefik
helm upgrade traefik traefik/traefik \
    --version "${chart_version}" -n traefik --reuse-values \
    -f "${REPO_ROOT}/deploy/helm/traefik/values.tinyproxy.minikube.yaml" --wait --timeout 2m

python3 - <<'PY'
import json
import subprocess

# Map both PATH and HOST routing names to the internal ingress Service.
# CoreDNS's answer rewrite preserves the name originally requested by the client.
result = subprocess.check_output(['kubectl', '-n', 'kube-system', 'get', 'cm', 'coredns', '-o', 'json'])
config = json.loads(result)
corefile = config['data']['Corefile']
rule = r'''    # mock-fleet-tinyproxy DNS
    rewrite stop {
        name regex ^(.*\.)?mock-fleet\.minikube\.localhost\.$ traefik.traefik.svc.cluster.local.
        answer auto
    }
'''
if '# mock-fleet-tinyproxy DNS' not in corefile:
    if '.:53 {' not in corefile:
        raise SystemExit('Expected CoreDNS .:53 server block; configure the Fleet DNS rewrite manually.')
    corefile = corefile.replace('.:53 {', '.:53 {\n' + rule, 1)
    patch = json.dumps({'data': {'Corefile': corefile}})
    subprocess.run(['kubectl', '-n', 'kube-system', 'patch', 'cm', 'coredns', '--type=merge', '-p', patch], check=True)
PY
