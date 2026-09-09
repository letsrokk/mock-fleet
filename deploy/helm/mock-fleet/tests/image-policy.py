#!/usr/bin/env python3
"""Run with python3 deploy/helm/mock-fleet/tests/image-policy.py (requires Helm)."""
import json
import re
import subprocess
import tempfile
from pathlib import Path

CHART = Path(__file__).resolve().parents[1]


def render(values, template="wiremock-version-catalog-configmap.yaml", valid=True):
    with tempfile.NamedTemporaryFile(mode="w", suffix=".json") as source:
        json.dump(values, source)
        source.flush()
        result = subprocess.run(
            ["helm", "template", "image-policy-test", str(CHART), "-f", source.name,
             "--show-only", f"templates/{template}"], capture_output=True, text=True)
    assert (result.returncode == 0) == valid, result.stderr
    return result.stdout


def catalog(values):
    output = render(values)
    policy = json.loads(json.loads(re.search(r"mock-fleet/image-policy: (.+)", output)[1]))
    versions = set(re.findall(r"selectable\.([\d.]+):", output))
    return policy, versions


policy, versions = catalog({})
assert policy["allowedVersionRange"] == "" and len(policy["allowedImages"]) == 5
assert policy["defaultImage"] in policy["allowedImages"]
assert versions == {"3.9.2", "3.10.0", "3.11.0", "3.12.1", "3.13.2"}
for lower in "[(":
    for upper in "])":
        interval = f"{lower}3.10,3.13.2{upper}"
        policy, versions = catalog({"mockOps": {"enabled": True, "allowedVersionRange": interval},
                                     "wiremock": {"containerImage": "wiremock/wiremock:3.12.1-2"}})
        expected = {"3.11.0", "3.12.1"}
        if lower == "[": expected.add("3.10.0")
        if upper == "]": expected.add("3.13.2")
        assert versions == expected, (interval, versions)
        assert policy["allowedImages"] == [] and policy["allowedVersionRange"] == interval
catalog({"mockOps": {"enabled": True, "allowedVersionRange": "[3.0,999999999999999999999.0)"}})
_, versions = catalog({"mockOps": {"enabled": True, "allowedVersionRange": "[3.13.2,3.13.2]"},
                       "wiremock": {"supportedImageTags": []}})
assert versions == {"3.13.2"}
for interval in ("3.x", "[3.14,3.13)", "(3.13,3.13]", "[3.0, 4.0)", "[3.0,3.13.2)"):
    render({"mockOps": {"enabled": True, "allowedVersionRange": interval}}, valid=False)
render({"mockOps": {"defaultVersionConstraint": "3.x"}}, valid=False)
render({"wiremock": {"supportedImageTags": []}}, valid=False)
render({"wiremock": {"containerImage": "wiremock/wiremock:3.13.2-9"}}, valid=False)
render({"wiremock": {"supportedImageTags": ["3.13.2-2", "3.13.2-9"]}}, valid=False)
account = render({"wiremock": {"serviceAccount": {"imagePullSecrets": [{"name": "private-pull"}]}}},
                 "wiremock-serviceaccount.yaml")
assert "imagePullSecrets:" in account and "name: private-pull" in account
cron = render({"mockOps": {"enabled": True, "registry": {"credentialsSecretName": "registry-login"}}},
              "mock-ops-cronjob.yaml")
assert "MOCK_FLEET_WIREMOCK_ALLOWED_VERSION_RANGE" in cron and "MOCK_FLEET_WIREMOCK_DEFAULT_IMAGE" in cron
assert 'name: "registry-login"' in cron and "key: username" in cron and "key: password" in cron
print("Image-policy render checks passed")
