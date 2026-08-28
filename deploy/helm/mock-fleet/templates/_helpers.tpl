{{- define "mock-fleet.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "mock-fleet.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s" (include "mock-fleet.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "mock-fleet.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" -}}
{{- end -}}

{{- define "mock-fleet.namespace" -}}
{{- default .Release.Namespace .Values.namespaceOverride -}}
{{- end -}}

{{- define "mock-fleet.labels" -}}
helm.sh/chart: {{ include "mock-fleet.chart" . }}
app.kubernetes.io/name: {{ include "mock-fleet.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "mock-fleet.selectorLabels" -}}
app.kubernetes.io/name: {{ include "mock-fleet.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "mock-fleet.proxyFullname" -}}
{{- printf "%s-proxy" (include "mock-fleet.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "mock-fleet.apiFullname" -}}
{{- printf "%s-api" (include "mock-fleet.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "mock-fleet.apiHazelcastFullname" -}}
{{- printf "%s-hazelcast" (include "mock-fleet.apiFullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "mock-fleet.dashFullname" -}}
{{- printf "%s-dash" (include "mock-fleet.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "mock-fleet.mcpFullname" -}}
{{- printf "%s-mcp" (include "mock-fleet.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "mock-fleet.wiremockEgressNetworkPolicyName" -}}
{{- printf "%s-wiremock-egress" (include "mock-fleet.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "mock-fleet.apiIngressNetworkPolicyName" -}}
{{- printf "%s-api-ingress" (include "mock-fleet.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "mock-fleet.apiServiceUrl" -}}
{{- $host := printf "%s.%s.svc.%s" (include "mock-fleet.apiFullname" .) (include "mock-fleet.namespace" .) (required "clusterDomain is required" .Values.clusterDomain) -}}
{{- $port := int .Values.fleet.api.service.ports.http -}}
{{- if eq $port 80 -}}
{{- printf "http://%s" $host -}}
{{- else -}}
{{- printf "http://%s:%d" $host $port -}}
{{- end -}}
{{- end -}}

{{- define "mock-fleet.proxyServiceUrl" -}}
{{- $host := printf "%s.%s.svc.%s" (include "mock-fleet.proxyFullname" .) (include "mock-fleet.namespace" .) (required "clusterDomain is required" .Values.clusterDomain) -}}
{{- $port := int .Values.fleet.proxy.service.ports.http -}}
{{- if eq $port 80 -}}
{{- printf "http://%s" $host -}}
{{- else -}}
{{- printf "http://%s:%d" $host $port -}}
{{- end -}}
{{- end -}}

{{- define "mock-fleet.mcpApiBaseUrl" -}}
{{- default (include "mock-fleet.apiServiceUrl" .) .Values.fleet.mcp.apiBaseUrl -}}
{{- end -}}

{{- define "mock-fleet.mcpProxyBaseUrl" -}}
{{- default (include "mock-fleet.proxyServiceUrl" .) .Values.fleet.mcp.proxyBaseUrl -}}
{{- end -}}

{{- define "mock-fleet.mcpRoutingMode" -}}
{{- default .Values.fleet.proxy.routing.mode .Values.fleet.mcp.routing.mode -}}
{{- end -}}

{{- define "mock-fleet.mcpFleetHost" -}}
{{- default .Values.ingress.host .Values.fleet.mcp.routing.fleetHost -}}
{{- end -}}

{{- define "mock-fleet.mcpAllowedOrigins" -}}
{{- if .Values.fleet.mcp.allowedOrigins -}}
{{- join "," .Values.fleet.mcp.allowedOrigins -}}
{{- else -}}
{{- $scheme := ternary "https" "http" (gt (len .Values.ingress.tls) 0) -}}
{{- printf "%s://%s" $scheme .Values.ingress.host -}}
{{- end -}}
{{- end -}}

{{- define "mock-fleet.proxySelectorLabels" -}}
{{ include "mock-fleet.selectorLabels" . }}
app.kubernetes.io/component: proxy
{{- end -}}

{{- define "mock-fleet.apiSelectorLabels" -}}
{{ include "mock-fleet.selectorLabels" . }}
app.kubernetes.io/component: api
{{- end -}}

{{- define "mock-fleet.dashSelectorLabels" -}}
{{ include "mock-fleet.selectorLabels" . }}
app.kubernetes.io/component: dash
{{- end -}}

{{- define "mock-fleet.mcpSelectorLabels" -}}
{{ include "mock-fleet.selectorLabels" . }}
app.kubernetes.io/component: mcp
{{- end -}}

{{- define "mock-fleet.serviceAccountName" -}}
{{- if .Values.serviceAccount.name -}}
{{- .Values.serviceAccount.name -}}
{{- else -}}
{{- printf "%s-pod-manager" (include "mock-fleet.fullname" .) -}}
{{- end -}}
{{- end -}}

{{- define "mock-fleet.wiremockServiceAccountName" -}}
{{- if .Values.wiremock.serviceAccount.name -}}
{{- .Values.wiremock.serviceAccount.name -}}
{{- else if .Values.wiremock.serviceAccount.create -}}
{{- printf "%s-wiremock" (include "mock-fleet.fullname" .) -}}
{{- end -}}
{{- end -}}

{{- define "mock-fleet.roleName" -}}
{{- printf "%s-pod-manager-role" (include "mock-fleet.fullname" .) -}}
{{- end -}}

{{- define "mock-fleet.roleBindingName" -}}
{{- printf "%s-pods-manager-role-binding" (include "mock-fleet.fullname" .) -}}
{{- end -}}

{{- define "mock-fleet.wiremockConfigMapName" -}}
{{- printf "%s-wiremock-config" (include "mock-fleet.fullname" .) -}}
{{- end -}}

{{- define "mock-fleet.wiremockUserConfigMapName" -}}
{{- printf "%s-wiremock-user-config" (include "mock-fleet.fullname" .) -}}
{{- end -}}

{{- define "mock-fleet.validateStorage" -}}
{{- if and .Values.storage.persistent (ne .Values.storage.type "s3") -}}
{{- fail (printf "Unsupported persistent storage.type %q. Supported values: s3" .Values.storage.type) -}}
{{- end -}}
{{- if and .Values.storage.persistent (eq .Values.storage.type "s3") (not .Values.storage.s3.bucket) -}}
{{- fail "storage.s3.bucket is required when storage.persistent=true and storage.type=s3" -}}
{{- end -}}
{{- end -}}

{{- define "mock-fleet.validateMcp" -}}
{{- if .Values.fleet.mcp.enabled -}}
{{- if ne (int .Values.fleet.mcp.replicas) 1 -}}
{{- fail "fleet.mcp.replicas must be 1 because the stable Streamable HTTP transport keeps session state" -}}
{{- end -}}
{{- if not (regexMatch "^.+:3\\.[0-9]+\\.[0-9]+(-[A-Za-z0-9][A-Za-z0-9.-]*)?(@sha256:[a-fA-F0-9]{64})?$" .Values.wiremock.containerImage) -}}
{{- fail "wiremock.containerImage must use a parseable pinned WireMock 3.x.y tag when fleet.mcp.enabled=true" -}}
{{- end -}}
{{- $mode := include "mock-fleet.mcpRoutingMode" . -}}
{{- if not (has $mode (list "PATH" "HOST")) -}}
{{- fail "fleet.mcp.routing.mode must be PATH or HOST (or empty to inherit fleet.proxy.routing.mode)" -}}
{{- end -}}
{{- if and (eq $mode "HOST") (not (include "mock-fleet.mcpFleetHost" .)) -}}
{{- fail "fleet.mcp.routing.fleetHost or ingress.host is required for HOST routing" -}}
{{- end -}}
{{- if or (lt (int .Values.fleet.mcp.defaultPageSize) 1) (gt (int .Values.fleet.mcp.defaultPageSize) (int .Values.fleet.mcp.maxPageSize)) (gt (int .Values.fleet.mcp.maxPageSize) 200) -}}
{{- fail "fleet.mcp page sizes must satisfy 1 <= defaultPageSize <= maxPageSize <= 200" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "mock-fleet.quantityValue" -}}
{{- $field := .field -}}
{{- $raw := trim (toString .value) -}}
{{- $numberPattern := "(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)" -}}
{{- $quantityPattern := printf "^%s(?:[eE][+-]?[0-9]+|[EPTGMK]i|[numkMGTPE])?$" $numberPattern -}}
{{- if not (regexMatch $quantityPattern $raw) -}}
{{- fail (printf "%s must be a positive Kubernetes resource quantity" $field) -}}
{{- end -}}
{{- $number := regexFind (printf "^%s" $numberPattern) $raw -}}
{{- $suffix := trimPrefix $number $raw -}}
{{- $value := float64 $number -}}
{{- if regexMatch "^[eE][+-]?[0-9]+$" $suffix -}}
{{- $value = float64 $raw -}}
{{- else if eq $suffix "n" -}}
{{- $value = mulf $value 0.000000001 -}}
{{- else if eq $suffix "u" -}}
{{- $value = mulf $value 0.000001 -}}
{{- else if eq $suffix "m" -}}
{{- $value = mulf $value 0.001 -}}
{{- else if or (eq $suffix "k") (eq $suffix "K") -}}
{{- $value = mulf $value 1000 -}}
{{- else if eq $suffix "M" -}}
{{- $value = mulf $value 1000000 -}}
{{- else if eq $suffix "G" -}}
{{- $value = mulf $value 1000000000 -}}
{{- else if eq $suffix "T" -}}
{{- $value = mulf $value 1000000000000 -}}
{{- else if eq $suffix "P" -}}
{{- $value = mulf $value 1000000000000000 -}}
{{- else if eq $suffix "E" -}}
{{- $value = mulf $value 1000000000000000000 -}}
{{- else if eq $suffix "Ki" -}}
{{- $value = mulf $value 1024 -}}
{{- else if eq $suffix "Mi" -}}
{{- $value = mulf $value 1048576 -}}
{{- else if eq $suffix "Gi" -}}
{{- $value = mulf $value 1073741824 -}}
{{- else if eq $suffix "Ti" -}}
{{- $value = mulf $value 1099511627776 -}}
{{- else if eq $suffix "Pi" -}}
{{- $value = mulf $value 1125899906842624 -}}
{{- else if eq $suffix "Ei" -}}
{{- $value = mulf $value 1152921504606846976 -}}
{{- end -}}
{{- if le $value (float64 0) -}}
{{- fail (printf "%s must be a positive Kubernetes resource quantity" $field) -}}
{{- end -}}
{{- printf "%g" $value -}}
{{- end -}}

{{- define "mock-fleet.validateSecurityBoundaries" -}}
{{- if gt (int .Values.fleet.api.maxConcurrentStarts) (int .Values.fleet.api.maxActiveMocks) -}}
{{- fail "fleet.api.maxConcurrentStarts must not exceed fleet.api.maxActiveMocks" -}}
{{- end -}}
{{- $cpuFloor := include "mock-fleet.quantityValue" (dict "field" "wiremock.resourcePolicy.requestFloor.cpu" "value" .Values.wiremock.resourcePolicy.requestFloor.cpu) | float64 -}}
{{- $memoryFloor := include "mock-fleet.quantityValue" (dict "field" "wiremock.resourcePolicy.requestFloor.memory" "value" .Values.wiremock.resourcePolicy.requestFloor.memory) | float64 -}}
{{- $cpuCeiling := include "mock-fleet.quantityValue" (dict "field" "wiremock.resourcePolicy.limitCeiling.cpu" "value" .Values.wiremock.resourcePolicy.limitCeiling.cpu) | float64 -}}
{{- $memoryCeiling := include "mock-fleet.quantityValue" (dict "field" "wiremock.resourcePolicy.limitCeiling.memory" "value" .Values.wiremock.resourcePolicy.limitCeiling.memory) | float64 -}}
{{- $cpuRequest := include "mock-fleet.quantityValue" (dict "field" "wiremock.config.default.resources.requests.cpu" "value" .Values.wiremock.config.default.resources.requests.cpu) | float64 -}}
{{- $memoryRequest := include "mock-fleet.quantityValue" (dict "field" "wiremock.config.default.resources.requests.memory" "value" .Values.wiremock.config.default.resources.requests.memory) | float64 -}}
{{- $cpuLimit := include "mock-fleet.quantityValue" (dict "field" "wiremock.config.default.resources.limits.cpu" "value" .Values.wiremock.config.default.resources.limits.cpu) | float64 -}}
{{- $memoryLimit := include "mock-fleet.quantityValue" (dict "field" "wiremock.config.default.resources.limits.memory" "value" .Values.wiremock.config.default.resources.limits.memory) | float64 -}}
{{- $_ := include "mock-fleet.quantityValue" (dict "field" "resourceQuota.hard.requests.cpu" "value" (index .Values.resourceQuota.hard "requests.cpu")) -}}
{{- $_ := include "mock-fleet.quantityValue" (dict "field" "resourceQuota.hard.requests.memory" "value" (index .Values.resourceQuota.hard "requests.memory")) -}}
{{- $_ := include "mock-fleet.quantityValue" (dict "field" "resourceQuota.hard.limits.cpu" "value" (index .Values.resourceQuota.hard "limits.cpu")) -}}
{{- $_ := include "mock-fleet.quantityValue" (dict "field" "resourceQuota.hard.limits.memory" "value" (index .Values.resourceQuota.hard "limits.memory")) -}}
{{- if gt $cpuFloor $cpuCeiling -}}
{{- fail "wiremock.resourcePolicy.requestFloor.cpu must not exceed wiremock.resourcePolicy.limitCeiling.cpu" -}}
{{- end -}}
{{- if gt $memoryFloor $memoryCeiling -}}
{{- fail "wiremock.resourcePolicy.requestFloor.memory must not exceed wiremock.resourcePolicy.limitCeiling.memory" -}}
{{- end -}}
{{- if lt $cpuRequest $cpuFloor -}}
{{- fail "wiremock.config.default.resources.requests.cpu must not be below wiremock.resourcePolicy.requestFloor.cpu" -}}
{{- end -}}
{{- if lt $memoryRequest $memoryFloor -}}
{{- fail "wiremock.config.default.resources.requests.memory must not be below wiremock.resourcePolicy.requestFloor.memory" -}}
{{- end -}}
{{- if gt $cpuLimit $cpuCeiling -}}
{{- fail "wiremock.config.default.resources.limits.cpu must not exceed wiremock.resourcePolicy.limitCeiling.cpu" -}}
{{- end -}}
{{- if gt $memoryLimit $memoryCeiling -}}
{{- fail "wiremock.config.default.resources.limits.memory must not exceed wiremock.resourcePolicy.limitCeiling.memory" -}}
{{- end -}}
{{- if gt $cpuRequest $cpuLimit -}}
{{- fail "wiremock.config.default.resources.requests.cpu must not exceed wiremock.config.default.resources.limits.cpu" -}}
{{- end -}}
{{- if gt $memoryRequest $memoryLimit -}}
{{- fail "wiremock.config.default.resources.requests.memory must not exceed wiremock.config.default.resources.limits.memory" -}}
{{- end -}}
{{- end -}}
