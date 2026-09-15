import os
import re

from mitmproxy import http

FLEET_HOST = os.environ["FLEET_HOST"].lower()
PROXY_HOST = os.environ["FLEET_PROXY_HOST"]
PROXY_PORT = int(os.environ["FLEET_PROXY_PORT"])
ROUTING_MODE = os.environ["FLEET_ROUTING_MODE"]
MOCK_HOST = re.compile(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\." + re.escape(FLEET_HOST))


def allowed(host):
    host = host.lower()
    return host == FLEET_HOST or (ROUTING_MODE == "HOST" and MOCK_HOST.fullmatch(host) is not None)


def http_connect(flow: http.HTTPFlow):
    if not allowed(flow.request.host):
        flow.response = http.Response.make(403, b"Only Mock Fleet destinations are allowed.\n")


def requestheaders(flow: http.HTTPFlow):
    if not allowed(flow.request.host):
        flow.response = http.Response.make(403, b"Only Mock Fleet destinations are allowed.\n")
        return
    host = flow.request.host
    flow.request.scheme = "http"
    flow.request.host = PROXY_HOST
    flow.request.port = PROXY_PORT
    # Fleet HOST routing uses the original authority, not the Service DNS name.
    flow.request.host_header = host
