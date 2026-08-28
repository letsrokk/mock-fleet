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

{{/* Keep resource quantities as decimal digit strings so policy comparisons never round through float64. */}}
{{- define "mock-fleet.decimalMultiply" -}}
{{- $digits := .digits -}}
{{- $factor := int .factor -}}
{{- $result := list -}}
{{- $carry := 0 -}}
{{- range $offset := until (len $digits) -}}
{{- $position := sub (sub (len $digits) 1) $offset | int -}}
{{- $digit := substr $position (add $position 1 | int) $digits | int -}}
{{- $product := add (mul $digit $factor) $carry | int -}}
{{- $result = prepend $result (toString (mod $product 10)) -}}
{{- $carry = div $product 10 | int -}}
{{- end -}}
{{- if gt $carry 0 -}}
{{- $result = prepend $result (toString $carry) -}}
{{- end -}}
{{- join "" $result -}}
{{- end -}}

{{- define "mock-fleet.quantityValue" -}}
{{- $field := .field -}}
{{- $raw := trim (toString .value) -}}
{{- if hasSuffix "Ei" $raw -}}
{{- fail (printf "%s does not support Ei because Fabric8 cannot compare it exactly" $field) -}}
{{- end -}}
{{- $numberPattern := "(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)" -}}
{{- $quantityPattern := printf "^%s(?:[eE][+-]?[0-9]{1,2}|[PTGMK]i|[numkMGTPE])?$" $numberPattern -}}
{{- if or (gt (len $raw) 128) (not (regexMatch $quantityPattern $raw)) -}}
{{- fail (printf "%s must be a positive Kubernetes resource quantity" $field) -}}
{{- end -}}
{{- $number := regexFind (printf "^%s" $numberPattern) $raw -}}
{{- $suffix := trimPrefix $number $raw -}}
{{- $numberParts := splitList "." $number -}}
{{- $whole := index $numberParts 0 -}}
{{- $fraction := "" -}}
{{- if eq (len $numberParts) 2 -}}
{{- $fraction = index $numberParts 1 -}}
{{- end -}}
{{- $coefficient := regexReplaceAll "^0+" (printf "%s%s" $whole $fraction) "" -}}
{{- if not $coefficient -}}
{{- fail (printf "%s must be a positive Kubernetes resource quantity" $field) -}}
{{- end -}}
{{- $exponent := mul -1 (len $fraction) | int -}}
{{- if regexMatch "^[eE][+-]?[0-9]{1,2}$" $suffix -}}
{{- $exponent = add $exponent (substr 1 (len $suffix) $suffix | int) | int -}}
{{- else -}}
{{- $decimalExponents := dict "" 0 "n" -9 "u" -6 "m" -3 "k" 3 "M" 6 "G" 9 "T" 12 "P" 15 "E" 18 -}}
{{- if hasKey $decimalExponents $suffix -}}
{{- $exponent = add $exponent (get $decimalExponents $suffix | int) | int -}}
{{- else -}}
{{- $binaryPowers := dict "Ki" 1 "Mi" 2 "Gi" 3 "Ti" 4 "Pi" 5 -}}
{{- $power := get $binaryPowers $suffix | int -}}
{{- range $_ := until $power -}}
{{- $coefficient = include "mock-fleet.decimalMultiply" (dict "digits" $coefficient "factor" 1024) -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- printf "%s|%d" $coefficient $exponent -}}
{{- end -}}

{{- define "mock-fleet.quantityCompare" -}}
{{- $leftParts := splitList "|" .left -}}
{{- $rightParts := splitList "|" .right -}}
{{- $leftCoefficient := index $leftParts 0 -}}
{{- $rightCoefficient := index $rightParts 0 -}}
{{- $leftExponent := index $leftParts 1 | int -}}
{{- $rightExponent := index $rightParts 1 | int -}}
{{- $commonExponent := $leftExponent -}}
{{- if lt $rightExponent $commonExponent -}}
{{- $commonExponent = $rightExponent -}}
{{- end -}}
{{- $left := printf "%s%s" $leftCoefficient (repeat (sub $leftExponent $commonExponent | int) "0") -}}
{{- $right := printf "%s%s" $rightCoefficient (repeat (sub $rightExponent $commonExponent | int) "0") -}}
{{- if gt (len $left) (len $right) -}}
1
{{- else if lt (len $left) (len $right) -}}
-1
{{- else if eq $left $right -}}
0
{{- else if gt $left $right -}}
1
{{- else -}}
-1
{{- end -}}
{{- end -}}

{{- define "mock-fleet.validateSecurityBoundaries" -}}
{{- if gt (int .Values.fleet.api.maxConcurrentStarts) (int .Values.fleet.api.maxActiveMocks) -}}
{{- fail "fleet.api.maxConcurrentStarts must not exceed fleet.api.maxActiveMocks" -}}
{{- end -}}
{{- if eq (int .Values.hazelcast.port) (int .Values.fleet.api.service.ports.targetHttp) -}}
{{- fail "hazelcast.port must not equal fleet.api.service.ports.targetHttp" -}}
{{- end -}}
{{- $cpuFloor := include "mock-fleet.quantityValue" (dict "field" "wiremock.resourcePolicy.requestFloor.cpu" "value" .Values.wiremock.resourcePolicy.requestFloor.cpu) -}}
{{- $memoryFloor := include "mock-fleet.quantityValue" (dict "field" "wiremock.resourcePolicy.requestFloor.memory" "value" .Values.wiremock.resourcePolicy.requestFloor.memory) -}}
{{- $cpuCeiling := include "mock-fleet.quantityValue" (dict "field" "wiremock.resourcePolicy.limitCeiling.cpu" "value" .Values.wiremock.resourcePolicy.limitCeiling.cpu) -}}
{{- $memoryCeiling := include "mock-fleet.quantityValue" (dict "field" "wiremock.resourcePolicy.limitCeiling.memory" "value" .Values.wiremock.resourcePolicy.limitCeiling.memory) -}}
{{- $cpuRequest := include "mock-fleet.quantityValue" (dict "field" "wiremock.config.default.resources.requests.cpu" "value" .Values.wiremock.config.default.resources.requests.cpu) -}}
{{- $memoryRequest := include "mock-fleet.quantityValue" (dict "field" "wiremock.config.default.resources.requests.memory" "value" .Values.wiremock.config.default.resources.requests.memory) -}}
{{- $cpuLimit := include "mock-fleet.quantityValue" (dict "field" "wiremock.config.default.resources.limits.cpu" "value" .Values.wiremock.config.default.resources.limits.cpu) -}}
{{- $memoryLimit := include "mock-fleet.quantityValue" (dict "field" "wiremock.config.default.resources.limits.memory" "value" .Values.wiremock.config.default.resources.limits.memory) -}}
{{- $_ := include "mock-fleet.quantityValue" (dict "field" "resourceQuota.hard.requests.cpu" "value" (index .Values.resourceQuota.hard "requests.cpu")) -}}
{{- $_ := include "mock-fleet.quantityValue" (dict "field" "resourceQuota.hard.requests.memory" "value" (index .Values.resourceQuota.hard "requests.memory")) -}}
{{- $_ := include "mock-fleet.quantityValue" (dict "field" "resourceQuota.hard.limits.cpu" "value" (index .Values.resourceQuota.hard "limits.cpu")) -}}
{{- $_ := include "mock-fleet.quantityValue" (dict "field" "resourceQuota.hard.limits.memory" "value" (index .Values.resourceQuota.hard "limits.memory")) -}}
{{- if eq (include "mock-fleet.quantityCompare" (dict "left" $cpuFloor "right" $cpuCeiling)) "1" -}}
{{- fail "wiremock.resourcePolicy.requestFloor.cpu must not exceed wiremock.resourcePolicy.limitCeiling.cpu" -}}
{{- end -}}
{{- if eq (include "mock-fleet.quantityCompare" (dict "left" $memoryFloor "right" $memoryCeiling)) "1" -}}
{{- fail "wiremock.resourcePolicy.requestFloor.memory must not exceed wiremock.resourcePolicy.limitCeiling.memory" -}}
{{- end -}}
{{- if eq (include "mock-fleet.quantityCompare" (dict "left" $cpuRequest "right" $cpuFloor)) "-1" -}}
{{- fail "wiremock.config.default.resources.requests.cpu must not be below wiremock.resourcePolicy.requestFloor.cpu" -}}
{{- end -}}
{{- if eq (include "mock-fleet.quantityCompare" (dict "left" $memoryRequest "right" $memoryFloor)) "-1" -}}
{{- fail "wiremock.config.default.resources.requests.memory must not be below wiremock.resourcePolicy.requestFloor.memory" -}}
{{- end -}}
{{- if eq (include "mock-fleet.quantityCompare" (dict "left" $cpuLimit "right" $cpuCeiling)) "1" -}}
{{- fail "wiremock.config.default.resources.limits.cpu must not exceed wiremock.resourcePolicy.limitCeiling.cpu" -}}
{{- end -}}
{{- if eq (include "mock-fleet.quantityCompare" (dict "left" $memoryLimit "right" $memoryCeiling)) "1" -}}
{{- fail "wiremock.config.default.resources.limits.memory must not exceed wiremock.resourcePolicy.limitCeiling.memory" -}}
{{- end -}}
{{- if eq (include "mock-fleet.quantityCompare" (dict "left" $cpuRequest "right" $cpuLimit)) "1" -}}
{{- fail "wiremock.config.default.resources.requests.cpu must not exceed wiremock.config.default.resources.limits.cpu" -}}
{{- end -}}
{{- if eq (include "mock-fleet.quantityCompare" (dict "left" $memoryRequest "right" $memoryLimit)) "1" -}}
{{- fail "wiremock.config.default.resources.requests.memory must not exceed wiremock.config.default.resources.limits.memory" -}}
{{- end -}}
{{- end -}}
