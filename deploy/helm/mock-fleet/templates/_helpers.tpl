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

{{- define "mock-fleet.serviceAccountName" -}}
{{- if .Values.serviceAccount.name -}}
{{- .Values.serviceAccount.name -}}
{{- else -}}
{{- printf "%s-pod-manager" (include "mock-fleet.fullname" .) -}}
{{- end -}}
{{- end -}}

{{- define "mock-fleet.roleName" -}}
{{- printf "%s-pod-manager-role" (include "mock-fleet.fullname" .) -}}
{{- end -}}

{{- define "mock-fleet.roleBindingName" -}}
{{- printf "%s-pods-manager-role-binding" (include "mock-fleet.fullname" .) -}}
{{- end -}}

{{- define "mock-fleet.hazelcastConfigMapName" -}}
{{- printf "%s-hazelcast-client" (include "mock-fleet.fullname" .) -}}
{{- end -}}

{{- define "mock-fleet.hazelcastServiceName" -}}
{{- printf "%s-hazelcast" .Release.Name -}}
{{- end -}}

{{- define "mock-fleet.ingressRules" -}}
- host: {{ required "ingress.host is required" .Values.ingress.host | quote }}
  http:
    paths:
      - backend:
          service:
            name: {{ include "mock-fleet.fullname" . }}
            port:
              name: http
        path: {{ .Values.ingress.path }}
        pathType: {{ .Values.ingress.pathType }}
{{- if eq .Values.routing.mode "HOST" }}
- host: {{ printf "*.%s" .Values.ingress.host | quote }}
  http:
    paths:
      - backend:
          service:
            name: {{ include "mock-fleet.fullname" . }}
            port:
              name: http
        path: {{ .Values.ingress.path }}
        pathType: {{ .Values.ingress.pathType }}
{{- end }}
{{- end -}}
