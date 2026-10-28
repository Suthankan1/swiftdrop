{{/*
Expand the name of the chart.
*/}}
{{- define "swiftdrop.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "swiftdrop.fullname" -}}
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
{{- define "swiftdrop.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "swiftdrop.labels" -}}
helm.sh/chart: {{ include "swiftdrop.chart" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: swiftdrop
{{- end }}

{{/*
Selector labels for a component
Usage: {{ include "swiftdrop.selectorLabels" (dict "context" . "component" "api-gateway") }}
*/}}
{{- define "swiftdrop.selectorLabels" -}}
app.kubernetes.io/name: {{ .component }}
app.kubernetes.io/instance: {{ .context.Release.Name }}
{{- end }}

{{/*
Component labels
Usage: {{ include "swiftdrop.componentLabels" (dict "context" . "component" "api-gateway") }}
*/}}
{{- define "swiftdrop.componentLabels" -}}
{{ include "swiftdrop.labels" .context }}
{{ include "swiftdrop.selectorLabels" . }}
app.kubernetes.io/version: {{ .context.Chart.AppVersion | quote }}
{{- end }}

{{/*
Render container image reference (prefers immutable digest when present)
Usage: {{ include "swiftdrop.image" .Values.accounts.image }}
*/}}
{{- define "swiftdrop.image" -}}
{{- if .digest -}}
{{- printf "%s@%s" .repository .digest -}}
{{- else if .tag -}}
{{- printf "%s:%s" .repository .tag -}}
{{- else -}}
{{- .repository -}}
{{- end -}}
{{- end }}
