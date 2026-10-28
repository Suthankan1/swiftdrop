{{/*
Expand the name of the chart.
*/}}
{{- define "swiftdrop-messaging.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "swiftdrop-messaging.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "swiftdrop-messaging.labels" -}}
helm.sh/chart: {{ include "swiftdrop-messaging.chart" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: swiftdrop
{{- end }}

{{/*
Selector labels for a component
Usage: {{ include "swiftdrop-messaging.selectorLabels" (dict "context" . "component" "kafka") }}
*/}}
{{- define "swiftdrop-messaging.selectorLabels" -}}
app.kubernetes.io/name: {{ .component }}
app.kubernetes.io/instance: {{ .context.Release.Name }}
{{- end }}

{{/*
Component labels
Usage: {{ include "swiftdrop-messaging.componentLabels" (dict "context" . "component" "kafka") }}
*/}}
{{- define "swiftdrop-messaging.componentLabels" -}}
{{ include "swiftdrop-messaging.labels" .context }}
{{ include "swiftdrop-messaging.selectorLabels" . }}
app.kubernetes.io/version: {{ .context.Chart.AppVersion | quote }}
{{- end }}

