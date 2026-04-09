#!/bin/bash
# ChartMuseum에 등록할 MLOps 차트 생성 및 push
# 모든 차트에 Ingress 포함 — 배포 시 <릴리즈명>.local 로 자동 접근
# 사용법: ./create-charts.sh [CHARTMUSEUM_URL]

CHART_URL="${1:-http://localhost:8880}"
TMPDIR=$(mktemp -d)

# 공통 Ingress 템플릿 생성 함수
write_ingress_template() {
  local dir=$1
  local port_name=${2:-http}
  cat > "$dir/templates/ingress.yaml" <<'TMPL'
{{- if .Values.ingress.enabled }}
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: {{ .Release.Name }}
  labels:
    app: {{ .Release.Name }}
  {{- with .Values.ingress.annotations }}
  annotations:
    {{- toYaml . | nindent 4 }}
  {{- end }}
spec:
  ingressClassName: {{ .Values.ingress.className | default "nginx" }}
  rules:
    - host: {{ .Values.ingress.host | default (printf "%s.aipaas" .Release.Name) }}
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: {{ .Release.Name }}
                port:
TMPL
  # 포트 이름에 따라 분기
  if [ "$port_name" = "console" ]; then
    cat >> "$dir/templates/ingress.yaml" <<'TMPL'
                  number: {{ .Values.service.consolePort }}
{{- end }}
TMPL
  else
    cat >> "$dir/templates/ingress.yaml" <<'TMPL'
                  number: {{ .Values.service.port }}
{{- end }}
TMPL
  fi
}

# ─────────────────────────────────────
# 1. gpu-jupyter: ML용 Jupyter Notebook
# ─────────────────────────────────────
DIR="$TMPDIR/gpu-jupyter"
mkdir -p "$DIR/templates"

cat > "$DIR/Chart.yaml" <<'EOF'
apiVersion: v2
name: gpu-jupyter
version: 0.2.0
appVersion: "1.0.0"
description: GPU-accelerated Jupyter Notebook with SciPy, Pandas, Matplotlib
type: application
keywords:
  - jupyter
  - gpu
  - notebook
  - machine-learning
EOF

cat > "$DIR/values.yaml" <<'EOF'
replicaCount: 1
image:
  repository: jupyter/scipy-notebook
  tag: latest
  pullPolicy: IfNotPresent
service:
  type: ClusterIP
  port: 8888
ingress:
  enabled: true
  className: nginx
  # host: gpu-jupyter.aipaas  (기본값: <릴리즈명>.aipaas)
  annotations: {}
jupyter:
  token: "demo1234"
securityContext:
  privileged: true
resources:
  requests:
    cpu: 200m
    memory: 512Mi
  limits:
    cpu: 1000m
    memory: 2Gi
EOF

cat > "$DIR/templates/deployment.yaml" <<'TMPL'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ .Release.Name }}
  labels:
    app: {{ .Release.Name }}
    chart: {{ .Chart.Name }}-{{ .Chart.Version }}
spec:
  replicas: {{ .Values.replicaCount }}
  selector:
    matchLabels:
      app: {{ .Release.Name }}
  template:
    metadata:
      labels:
        app: {{ .Release.Name }}
        component: notebook
    spec:
      containers:
        - name: jupyter
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
          ports:
            - containerPort: {{ .Values.service.port }}
              name: http
          env:
            - name: JUPYTER_TOKEN
              value: "{{ .Values.jupyter.token }}"
            - name: JUPYTER_ENABLE_LAB
              value: "yes"
          resources:
            {{- toYaml .Values.resources | nindent 12 }}
TMPL

cat > "$DIR/templates/service.yaml" <<'TMPL'
apiVersion: v1
kind: Service
metadata:
  name: {{ .Release.Name }}
  labels:
    app: {{ .Release.Name }}
spec:
  type: {{ .Values.service.type }}
  selector:
    app: {{ .Release.Name }}
  ports:
    - port: {{ .Values.service.port }}
      targetPort: {{ .Values.service.port }}
      name: http
TMPL

write_ingress_template "$DIR" "http"

# ─────────────────────────────────────
# 2. mlflow: 실험 추적 + 모델 레지스트리
# ─────────────────────────────────────
DIR="$TMPDIR/mlflow"
mkdir -p "$DIR/templates"

cat > "$DIR/Chart.yaml" <<'EOF'
apiVersion: v2
name: mlflow
version: 0.2.0
appVersion: "2.17.0"
description: MLflow Tracking Server - ML Experiment & Model Registry
type: application
keywords:
  - mlflow
  - experiment-tracking
  - model-registry
  - mlops
EOF

cat > "$DIR/values.yaml" <<'EOF'
replicaCount: 1
image:
  repository: ghcr.io/mlflow/mlflow
  tag: v2.17.0
  pullPolicy: IfNotPresent
service:
  type: ClusterIP
  port: 5000
ingress:
  enabled: true
  className: nginx
  annotations: {}
resources:
  requests:
    cpu: 100m
    memory: 256Mi
  limits:
    cpu: 500m
    memory: 512Mi
EOF

cat > "$DIR/templates/deployment.yaml" <<'TMPL'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ .Release.Name }}
  labels:
    app: {{ .Release.Name }}
    chart: {{ .Chart.Name }}-{{ .Chart.Version }}
spec:
  replicas: {{ .Values.replicaCount }}
  selector:
    matchLabels:
      app: {{ .Release.Name }}
  template:
    metadata:
      labels:
        app: {{ .Release.Name }}
        component: tracking-server
    spec:
      containers:
        - name: mlflow
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
          command: ["mlflow", "server", "--host", "0.0.0.0", "--port", "{{ .Values.service.port }}"]
          ports:
            - containerPort: {{ .Values.service.port }}
              name: http
          resources:
            {{- toYaml .Values.resources | nindent 12 }}
TMPL

cat > "$DIR/templates/service.yaml" <<'TMPL'
apiVersion: v1
kind: Service
metadata:
  name: {{ .Release.Name }}
  labels:
    app: {{ .Release.Name }}
spec:
  type: {{ .Values.service.type }}
  selector:
    app: {{ .Release.Name }}
  ports:
    - port: {{ .Values.service.port }}
      targetPort: {{ .Values.service.port }}
      name: http
TMPL

write_ingress_template "$DIR" "http"

# ─────────────────────────────────────
# 3. model-server: FastAPI 모델 서빙
# ─────────────────────────────────────
DIR="$TMPDIR/model-server"
mkdir -p "$DIR/templates"

cat > "$DIR/Chart.yaml" <<'EOF'
apiVersion: v2
name: model-server
version: 0.2.0
appVersion: "1.0.0"
description: ML Model Serving API (FastAPI/Uvicorn)
type: application
keywords:
  - model-serving
  - fastapi
  - inference
  - mlops
EOF

cat > "$DIR/values.yaml" <<'EOF'
replicaCount: 1
image:
  repository: tiangolo/uvicorn-gunicorn-fastapi
  tag: python3.11-slim
  pullPolicy: IfNotPresent
service:
  type: ClusterIP
  port: 80
ingress:
  enabled: true
  className: nginx
  annotations: {}
resources:
  requests:
    cpu: 100m
    memory: 128Mi
  limits:
    cpu: 500m
    memory: 256Mi
EOF

cat > "$DIR/templates/deployment.yaml" <<'TMPL'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ .Release.Name }}
  labels:
    app: {{ .Release.Name }}
    chart: {{ .Chart.Name }}-{{ .Chart.Version }}
spec:
  replicas: {{ .Values.replicaCount }}
  selector:
    matchLabels:
      app: {{ .Release.Name }}
  template:
    metadata:
      labels:
        app: {{ .Release.Name }}
        component: inference
    spec:
      containers:
        - name: server
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
          ports:
            - containerPort: {{ .Values.service.port }}
              name: http
          resources:
            {{- toYaml .Values.resources | nindent 12 }}
TMPL

cat > "$DIR/templates/service.yaml" <<'TMPL'
apiVersion: v1
kind: Service
metadata:
  name: {{ .Release.Name }}
  labels:
    app: {{ .Release.Name }}
spec:
  type: {{ .Values.service.type }}
  selector:
    app: {{ .Release.Name }}
  ports:
    - port: {{ .Values.service.port }}
      targetPort: {{ .Values.service.port }}
      name: http
TMPL

write_ingress_template "$DIR" "http"

# ─────────────────────────────────────
# 4. minio: ML 아티팩트 오브젝트 스토리지
# ─────────────────────────────────────
DIR="$TMPDIR/minio"
mkdir -p "$DIR/templates"

cat > "$DIR/Chart.yaml" <<'EOF'
apiVersion: v2
name: minio
version: 0.2.0
appVersion: "2024.1.1"
description: MinIO Object Storage for ML Artifacts (S3-compatible)
type: application
keywords:
  - minio
  - s3
  - object-storage
  - artifacts
EOF

cat > "$DIR/values.yaml" <<'EOF'
replicaCount: 1
image:
  repository: minio/minio
  tag: latest
  pullPolicy: IfNotPresent
service:
  type: ClusterIP
  apiPort: 9000
  consolePort: 9001
ingress:
  enabled: true
  className: nginx
  annotations: {}
auth:
  rootUser: minioadmin
  rootPassword: minioadmin
resources:
  requests:
    cpu: 100m
    memory: 256Mi
  limits:
    cpu: 500m
    memory: 512Mi
EOF

cat > "$DIR/templates/deployment.yaml" <<'TMPL'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ .Release.Name }}
  labels:
    app: {{ .Release.Name }}
    chart: {{ .Chart.Name }}-{{ .Chart.Version }}
spec:
  replicas: {{ .Values.replicaCount }}
  selector:
    matchLabels:
      app: {{ .Release.Name }}
  template:
    metadata:
      labels:
        app: {{ .Release.Name }}
        component: storage
    spec:
      containers:
        - name: minio
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
          command: ["minio", "server", "/data", "--console-address", ":{{ .Values.service.consolePort }}"]
          ports:
            - containerPort: {{ .Values.service.apiPort }}
              name: api
            - containerPort: {{ .Values.service.consolePort }}
              name: console
          env:
            - name: MINIO_ROOT_USER
              value: "{{ .Values.auth.rootUser }}"
            - name: MINIO_ROOT_PASSWORD
              value: "{{ .Values.auth.rootPassword }}"
          resources:
            {{- toYaml .Values.resources | nindent 12 }}
TMPL

cat > "$DIR/templates/service.yaml" <<'TMPL'
apiVersion: v1
kind: Service
metadata:
  name: {{ .Release.Name }}
  labels:
    app: {{ .Release.Name }}
spec:
  type: {{ .Values.service.type }}
  selector:
    app: {{ .Release.Name }}
  ports:
    - port: {{ .Values.service.apiPort }}
      targetPort: {{ .Values.service.apiPort }}
      name: api
    - port: {{ .Values.service.consolePort }}
      targetPort: {{ .Values.service.consolePort }}
      name: console
TMPL

write_ingress_template "$DIR" "console"

# ─────────────────────────────────────
# 5. drift-detector: 데이터 드리프트 감지
# ─────────────────────────────────────
DIR="$TMPDIR/drift-detector"
mkdir -p "$DIR/templates"

cat > "$DIR/Chart.yaml" <<'EOF'
apiVersion: v2
name: drift-detector
version: 0.2.0
appVersion: "1.0.0"
description: Data Drift Detection Service with Evidently
type: application
keywords:
  - drift-detection
  - monitoring
  - data-quality
  - mlops
EOF

cat > "$DIR/values.yaml" <<'EOF'
replicaCount: 1
image:
  repository: python
  tag: 3.11-slim
  pullPolicy: IfNotPresent
service:
  type: ClusterIP
  port: 8000
ingress:
  enabled: true
  className: nginx
  annotations: {}
resources:
  requests:
    cpu: 100m
    memory: 128Mi
  limits:
    cpu: 500m
    memory: 256Mi
EOF

cat > "$DIR/templates/deployment.yaml" <<'TMPL'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ .Release.Name }}
  labels:
    app: {{ .Release.Name }}
    chart: {{ .Chart.Name }}-{{ .Chart.Version }}
spec:
  replicas: {{ .Values.replicaCount }}
  selector:
    matchLabels:
      app: {{ .Release.Name }}
  template:
    metadata:
      labels:
        app: {{ .Release.Name }}
        component: detector
    spec:
      containers:
        - name: detector
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
          command: ["python", "-m", "http.server", "{{ .Values.service.port }}"]
          ports:
            - containerPort: {{ .Values.service.port }}
              name: http
          resources:
            {{- toYaml .Values.resources | nindent 12 }}
TMPL

cat > "$DIR/templates/service.yaml" <<'TMPL'
apiVersion: v1
kind: Service
metadata:
  name: {{ .Release.Name }}
  labels:
    app: {{ .Release.Name }}
spec:
  type: {{ .Values.service.type }}
  selector:
    app: {{ .Release.Name }}
  ports:
    - port: {{ .Values.service.port }}
      targetPort: {{ .Values.service.port }}
      name: http
TMPL

write_ingress_template "$DIR" "http"

# ─────────────────────────────────────
# 6. ai-pipeline: 학습 파이프라인
# ─────────────────────────────────────
DIR="$TMPDIR/ai-pipeline"
mkdir -p "$DIR/templates"

cat > "$DIR/Chart.yaml" <<'EOF'
apiVersion: v2
name: ai-pipeline
version: 0.2.0
appVersion: "1.0.0"
description: AI Training Pipeline Orchestrator
type: application
keywords:
  - pipeline
  - training
  - orchestrator
  - mlops
EOF

cat > "$DIR/values.yaml" <<'EOF'
replicaCount: 1
image:
  repository: nginx
  tag: alpine
  pullPolicy: IfNotPresent
service:
  type: ClusterIP
  port: 8080
ingress:
  enabled: true
  className: nginx
  annotations: {}
EOF

cat > "$DIR/templates/deployment.yaml" <<'TMPL'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ .Release.Name }}
  labels:
    app: {{ .Release.Name }}
    chart: {{ .Chart.Name }}-{{ .Chart.Version }}
spec:
  replicas: {{ .Values.replicaCount }}
  selector:
    matchLabels:
      app: {{ .Release.Name }}
  template:
    metadata:
      labels:
        app: {{ .Release.Name }}
        component: pipeline
    spec:
      containers:
        - name: pipeline
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
          ports:
            - containerPort: {{ .Values.service.port }}
              name: http
TMPL

cat > "$DIR/templates/service.yaml" <<'TMPL'
apiVersion: v1
kind: Service
metadata:
  name: {{ .Release.Name }}
spec:
  selector:
    app: {{ .Release.Name }}
  ports:
    - port: {{ .Values.service.port }}
      targetPort: {{ .Values.service.port }}
      name: http
TMPL

write_ingress_template "$DIR" "http"

# ─────────────────────────────────────
# 패키징 & 업로드
# ─────────────────────────────────────
echo "Creating MLOps charts (v0.2.0 with Ingress)..."
for CHART in gpu-jupyter mlflow model-server minio drift-detector ai-pipeline; do
  helm package "$TMPDIR/$CHART" -d "$TMPDIR" > /dev/null 2>&1
  # 이전 버전 삭제
  curl -s -X DELETE "$CHART_URL/api/charts/$CHART/0.1.0" > /dev/null 2>&1
  curl -s -X DELETE "$CHART_URL/api/charts/$CHART/0.2.0" > /dev/null 2>&1
  curl -s --data-binary "@$TMPDIR/$CHART-0.2.0.tgz" "$CHART_URL/api/charts" > /dev/null
  echo "  Pushed $CHART-0.2.0"
done

rm -rf "$TMPDIR"
echo "Done. Charts registered at $CHART_URL"
