{{/*
Expand the name of the chart.
*/}}
{{- define "swiftdrop-monitoring.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "swiftdrop-monitoring.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "swiftdrop-monitoring.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "swiftdrop-monitoring.labels" -}}
helm.sh/chart: {{ include "swiftdrop-monitoring.chart" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: swiftdrop-monitoring
{{- end }}

{{/*
Selector labels for a component
Usage: {{ include "swiftdrop-monitoring.selectorLabels" (dict "context" . "component" "prometheus") }}
*/}}
{{- define "swiftdrop-monitoring.selectorLabels" -}}
app.kubernetes.io/name: {{ .component }}
app.kubernetes.io/instance: {{ .context.Release.Name }}
{{- end }}

{{/*
Component labels
Usage: {{ include "swiftdrop-monitoring.componentLabels" (dict "context" . "component" "prometheus") }}
*/}}
{{- define "swiftdrop-monitoring.componentLabels" -}}
{{ include "swiftdrop-monitoring.labels" .context }}
{{ include "swiftdrop-monitoring.selectorLabels" . }}
app.kubernetes.io/version: {{ .context.Chart.AppVersion | quote }}
{{- end }}

{{/*
Render container image reference (prefers immutable digest when present)
Usage: {{ include "swiftdrop-monitoring.image" .Values.prometheus.image }}
*/}}
{{- define "swiftdrop-monitoring.image" -}}
{{- if .digest -}}
{{- printf "%s@%s" .repository .digest -}}
{{- else if .tag -}}
{{- printf "%s:%s" .repository .tag -}}
{{- else -}}
{{- .repository -}}
{{- end -}}
{{- end }}

