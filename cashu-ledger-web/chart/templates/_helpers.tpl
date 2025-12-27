{{- define "cashu-ledger-web.name" -}}
cashu-ledger-web
{{- end -}}

{{- define "cashu-ledger-web.fullname" -}}
{{ include "cashu-ledger-web.name" . }}
{{- end -}}
