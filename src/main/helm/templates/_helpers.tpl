{{ define "mock-fleet.ingressRules" }}
{{- $host := required "app.host is required" .Values.app.host }}
{{- $routingMode := required "app.envs.MOCK_FLEET_ROUTING_MODE is required" .Values.app.envs.MOCK_FLEET_ROUTING_MODE }}
- host: {{ $host | quote }}
  http:
    paths:
      - backend:
          service:
            name: mock-fleet
            port:
              name: http
        path: /
        pathType: Prefix
{{- if eq $routingMode "HOST" }}
- host: {{ printf "*.%s" $host | quote }}
  http:
    paths:
      - backend:
          service:
            name: mock-fleet
            port:
              name: http
        path: /
        pathType: Prefix
{{- end }}
{{ end }}
