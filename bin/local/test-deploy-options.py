#!/usr/bin/env python3
"""Check local routing options without building images or contacting Kubernetes."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory() as directory:
    tools = Path(directory)
    marker = tools / "called"
    minikube = tools / "minikube"
    minikube.write_text('#!/bin/sh\n: > "$OPTION_TEST_MARKER"\nexit 1\n')
    minikube.chmod(0o755)
    env = {**os.environ, "PATH": f"{tools}:{os.environ['PATH']}",
           "OPTION_TEST_MARKER": str(marker)}
    env.pop("MOCK_FLEET_ROUTING_MODE", None)
    cases = [
        ([], None, True),
        (["--routing", "PATH"], None, True),
        (["--routing", "HOST"], None, False),
        (["--routing", "HOST", "--no-tinyproxy"], None, True),
        (["--no-tinyproxy"], None, True),
        ([], "HOST", False),
        (["--no-tinyproxy"], "HOST", True),
        (["--routing", "invalid", "--no-tinyproxy"], None, False),
    ]
    for args, routing_env, accepted in cases:
        marker.unlink(missing_ok=True)
        case_env = {**env}
        if routing_env:
            case_env["MOCK_FLEET_ROUTING_MODE"] = routing_env
        result = subprocess.run([str(ROOT / "bin/local/deploy.sh"), *args],
                                env=case_env, text=True, capture_output=True)
        assert result.returncode == 1, result
        assert marker.exists() == accepted, (args, routing_env, result.stderr)
        if not accepted and ("HOST" in args or routing_env == "HOST"):
            assert "Use PATH or --no-tinyproxy" in result.stderr, result.stderr
for value, disabled in (("true", False), ("false", True)):
    result = subprocess.check_output(["make", "-n", "local-deploy", f"TINYPROXY={value}"], cwd=ROOT, text=True)
    assert ("--no-tinyproxy" in result) == disabled, result
print("Local deploy option checks passed")
