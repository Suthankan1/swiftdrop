{{/*
Expand the name of the chart.
*/}}
{{- define "swiftdrop-data.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "swiftdrop-data.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "swiftdrop-data.labels" -}}
helm.sh/chart: {{ include "swiftdrop-data.chart" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: swiftdrop
{{- end }}

{{/*
Selector labels for a component
Usage: {{ include "swiftdrop-data.selectorLabels" (dict "context" . "component" "accounts-postgres") }}
*/}}
{{- define "swiftdrop-data.selectorLabels" -}}
app.kubernetes.io/name: {{ .component }}
app.kubernetes.io/instance: {{ .context.Release.Name }}
{{- end }}

{{/*
Component labels
Usage: {{ include "swiftdrop-data.componentLabels" (dict "context" . "component" "accounts-postgres") }}
*/}}
{{- define "swiftdrop-data.componentLabels" -}}
{{ include "swiftdrop-data.labels" .context }}
{{ include "swiftdrop-data.selectorLabels" . }}
app.kubernetes.io/version: {{ .context.Chart.AppVersion | quote }}
{{- end }}

