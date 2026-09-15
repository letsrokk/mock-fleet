# Tinyproxy with Traefik or AWS NLB

Status: implemented in the chart and local setup. AWS deployment verification requires an EKS environment.

## Behavior

Run one optional Tinyproxy deployment. Keep it
disabled in the base chart and enabled in the Minikube profile. Mount native
Tinyproxy configuration from a ConfigMap. Remove the interception addon, CA
Secret requirement, web UI, and web password.

Expose both client protocols in either environment:

| Client endpoint | Traefik | AWS NLB |
| --- | --- | --- |
| HTTP proxy, port 8080 | TCP forwarding | TCP listener |
| HTTPS proxy, port 8433 | TLS termination and TCP forwarding | TLS listener with ACM certificate |

Both listeners forward to the same unprivileged Tinyproxy container port 8888.
HTTPS destinations use CONNECT; Tinyproxy does not decrypt destination TLS.
Clients must trust the destination ingress certificate and, when using the HTTPS
proxy endpoint, the proxy listener certificate. HTTPS proxy support is a client
requirement, even if the destination itself uses HTTP.

```text
Minikube: client -> Traefik :8080/:8433 -> Tinyproxy -> Fleet Traefik :80/:443 -> Fleet
AWS:      client -> NLB     :8080/:8433 -> Tinyproxy -> Fleet ALB     :80/:443 -> Fleet
```

## Helm interface and switching

Use `fleet.tinyproxy.ingress.enabled` as the switch.
There is no `exposure.provider` field or independently configurable Service type.
When `fleet.tinyproxy.enabled` is false, render no proxy resources.

| Proxy enabled | Ingress enabled | Service type | Exposure |
| --- | --- | --- | --- |
| false | either | No Service | Disabled |
| true | true | ClusterIP | Traefik TCP routes |
| true | false | LoadBalancer | Configured load balancer; AWS profile uses NLB |

Minikube profile:

```yaml
fleet:
  tinyproxy:
    enabled: true
    ingress:
      enabled: true
      className: traefik
      hostname: tinyproxy.minikube.localhost
      httpEntryPoint: tinyproxy-http
      httpsEntryPoint: tinyproxy-https
      tlsSecretName: "" # empty uses Traefik's default certificate
    service:
      ports:
        http: 8080
        https: 8433
```

AWS profile uses the same ports and native Service configuration:

```yaml
fleet:
  tinyproxy:
    enabled: true
    ingress:
      enabled: false
    service:
      loadBalancerClass: service.k8s.aws/nlb
      annotations:
        service.beta.kubernetes.io/aws-load-balancer-nlb-target-type: ip
        service.beta.kubernetes.io/aws-load-balancer-scheme: internal
        service.beta.kubernetes.io/aws-load-balancer-ssl-cert: <ACM certificate ARN>
        service.beta.kubernetes.io/aws-load-balancer-ssl-ports: "8433"
        service.beta.kubernetes.io/aws-load-balancer-backend-protocol: tcp
      loadBalancerSourceRanges: [] # supply the permitted client CIDRs
```

Only apply load-balancer settings when ingress is disabled. Expose both Service
ports in LoadBalancer mode, targeting container port 8888. In ClusterIP mode,
expose only the HTTP Service port; both Traefik routes forward to it after any
TLS termination. A port named HTTPS on a Service does not itself enable TLS:
the AWS certificate and TLS-port annotations configure that behavior on NLB.

Retain the existing chart patterns for image, replicas, resources, and labels.
Add native configuration overrides while keeping the listener port consistent
with probes and Services. Generate a Fleet hostname allowlist separately from
the tuning options. Permit the exact Fleet ingress hostname; HOST mode also
permits valid mock subdomains. Reject other destinations and restrict CONNECT
to destination port 443. Plain HTTP forwarding supports destination port 80.

The Minikube values file enables ingress. A separate AWS example values file
disables it and supplies environment-specific Service annotations, certificates,
DNS, and network restrictions. The proxy image and configuration format stay the
same. `ingress.enabled` here means the Traefik TCP routes, not a standard HTTP
Ingress or an ALB Ingress.

Use one Tinyproxy Service, with its type derived from the flag. Switching an
existing installation can require Service recreation because loadBalancerClass
cannot be freely changed or removed. Document an explicit Service replacement
and DNS cutover for that case; do not use a destructive Helm force upgrade.
Switching environments using their respective values files needs no application
code change. Switching a live installation is not a zero-downtime operation.

## Traefik profile

Create two IngressRouteTCP resources: a non-TLS catch-all on the dedicated HTTP
entry point, and a TLS route matching the proxy hostname on the HTTPS entry
point. Both target the Tinyproxy ClusterIP Service. Render these CRDs only when
`fleet.tinyproxy.ingress.enabled` is true.

The shared Minikube workloads repository owns Traefik's Helm values and its
8080/8433 Service ports. Mock Fleet owns only its TCP routes and does not upgrade
Traefik during local deployment. The dedicated container listener ports are
18080/18433, avoiding Traefik's administrative port. Existing public ports 80/443
continue serving Fleet. Apply the shared workloads stack before using Tinyproxy.

Minikube supports PATH routing only. Local deployment reads Traefik's Service
ClusterIP and passes `fleet.tinyproxy.hostAliases` to Helm, mapping the exact
`mock-fleet.minikube.localhost` name inside Tinyproxy pods. The request Host header
and TLS SNI remain unchanged. No shared CoreDNS configuration is modified.
Re-run local deployment if Traefik's ClusterIP changes. AWS uses normal DNS and
can support HOST routing with the appropriate wildcard DNS and certificates.

## AWS profile

Target the AWS Load Balancer Controller with LoadBalancer class
`service.k8s.aws/nlb` and IP targets. The NLB Service declares ports 8080 and
8433, both targeting container port 8888. Set the ACM certificate annotation,
enable TLS only on port 8433, and use plaintext TCP to the backend. Use TCP
health checks and leave PROXY protocol disabled.

Use an internal NLB by default. Deployment configuration supplies allowed
client CIDRs, ACM ARN, and subnet selection; DNS points the proxy hostname to
the provisioned NLB. The chart does not create certificates or DNS zones.
This profile targets the AWS Load Balancer Controller, not EKS Auto Mode.

Fleet remains behind its ALB. Require private resolution/reachability of the
Fleet ALB for the strict egress profile described below. The ALB must preserve
the existing Fleet host/path routing and have certificates covering the Fleet
names, including mock subdomains in HOST mode.

## Network isolation

Keep Tinyproxy ingress behavior consistent with Fleet's existing policy, on
the proxy container port only. Apply external client restrictions at the AWS
NLB as well. Default-deny Tinyproxy egress and explicitly allow cluster DNS on
TCP/UDP 53 plus the configured Fleet ingress destination.

- **Minikube:** allow the Traefik namespace/pod selector and its actual backend
  listener ports. Service ports and container ports can differ.
- **AWS:** an ALB is outside Kubernetes, so a pod selector cannot select it.
  Allow the private ALB subnet CIDRs on ports 80/443 in NetworkPolicy. Require
  a dedicated Tinyproxy pod security group whose application egress targets
  the Fleet ALB security group, with separate DNS rules. The ALB security group
  must accept that traffic. This requires compatible EKS pod security-group
  support and infrastructure configuration outside the Fleet chart.

Subnet CIDRs alone permit other destinations in those subnets; they do not
mean “only this ALB.” Do not present that weaker configuration as equivalent.
Do not pin changing ALB IPs or permit unrestricted Internet egress. Validate the
combined CNI and security-group behavior in the target EKS environment.

Keep egress destination configuration separate from the ingress flag:
the inbound load balancer does not inherently identify Fleet's outbound
ingress. Supply matching settings in each environment profile.

The Tinyproxy hostname filter further restricts use of shared ingress. It
does not inspect encrypted paths or bind inner TLS SNI to a CONNECT hostname.
Consequently, this design cannot enforce individual mock paths or isolate
other virtual hosts sharing the same ingress against a hostile tunnel client.
Such isolation requires a dedicated Fleet ingress endpoint or destination-side
access controls.

## Validation and rollout

1. Helm checks cover proxy disabled and both ingress flag values. Verify the
   derived Service type, conditional TCP routes, Service ports, and omission of
   load-balancer settings in ClusterIP mode. Invalid boolean values, duplicate
   ports, and missing required AWS TLS settings fail validation.
2. Locally test HTTP and HTTPS destinations through both proxy endpoints, in
   local PATH mode. Check HOST configuration rendering for other environments. Verify destination certificate trust without a proxy
   interception CA, rejected non-Fleet hosts, DNS resolution, and blocked
   egress with a reachable unrestricted control.
3. Validate the AWS rendered Service and annotations locally. Real NLB TLS,
   target health, DNS, and security-group isolation require an EKS smoke test;
   local tests are not evidence those AWS paths work.
4. Use Tinyproxy names for resources, `fleet.tinyproxy` configuration, and local
   enable/disable flags. Update the existing PR after implementation and local
   verification.
   Do not remove CA Secrets that may be user-managed.

## References

- [Traefik IngressRouteTCP](https://doc.traefik.io/traefik/reference/routing-configuration/kubernetes/crd/tcp/ingressroutetcp/)
- [AWS Load Balancer Controller Service annotations](https://kubernetes-sigs.github.io/aws-load-balancer-controller/latest/guide/service/annotations/)
- [NLB listeners](https://docs.aws.amazon.com/elasticloadbalancing/latest/network/load-balancer-listeners.html)
- [Kubernetes NetworkPolicy](https://kubernetes.io/docs/concepts/services-networking/network-policies/)
- [EKS security groups for pods](https://docs.aws.amazon.com/eks/latest/userguide/security-groups-for-pods.html)
