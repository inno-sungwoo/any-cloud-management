#!/bin/bash
# =============================================================================
# AI-PaaS 플랫폼 — 한방 셋업 스크립트
# 이 스크립트 하나로 전체 개발환경이 구축됩니다.
#
# 사용법:
#   chmod +x scripts/setup.sh && ./scripts/setup.sh
#
# 사전 요구사항:
#   - kubectl 설치 + ai-platform-k8s 컨텍스트 등록 (kubeconfig)
#   - helm 3.x 설치
#   - Docker 실행 중 (docker compose 사용 가능)
#   - Java 17+ 설치
#   - pnpm 설치 (프론트엔드)
#
# 실행 결과:
#   - MariaDB (localhost:13306) + ChartMuseum (localhost:8880) Docker 컨테이너
#   - ai-pass NS에 Prometheus Stack + helm-exporter + GPU 설정
#   - Prometheus port-forward (localhost:9090)
#   - 백엔드 (localhost:8888) + 프론트엔드 (localhost:5173) 자동 시작
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
FRONTEND_DIR="$(cd "$REPO_DIR/../ai-paas-web" && pwd 2>/dev/null || echo "")"
INFRA_DIR="$REPO_DIR/docs/infrastructure"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

step() { echo -e "\n${BLUE}[$1/$TOTAL_STEPS]${NC} $2"; }
ok()   { echo -e "  ${GREEN}✓${NC} $1"; }
warn() { echo -e "  ${YELLOW}!${NC} $1"; }
fail() { echo -e "  ${RED}✗${NC} $1"; exit 1; }

TOTAL_STEPS=12
NAMESPACE="ai-pass"
PROM_RELEASE="ai-pass-prom"
PROM_SVC="${PROM_RELEASE}-kube-promethe-prometheus"

echo -e "${BLUE}╔══════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║  AI-PaaS 플랫폼 — 한방 셋업                      ║${NC}"
echo -e "${BLUE}║  K8s Context : (kubeconfig 모든 컨텍스트 동기화) ║${NC}"
echo -e "${BLUE}║  Namespace   : ${NAMESPACE}                            ║${NC}"
echo -e "${BLUE}╚══════════════════════════════════════════════════╝${NC}"

# ═══════════════════════════════════════
# 1. 사전 요구사항 검사
# ═══════════════════════════════════════
step 1 "사전 요구사항 검사"

for cmd in docker kubectl helm java; do
  command -v $cmd >/dev/null 2>&1 || fail "${cmd}이(가) 설치되어 있지 않습니다"
  ok "$cmd 설치 확인"
done

# Java 버전
JAVA_VER=$(java -version 2>&1 | head -1)
ok "Java: $JAVA_VER"

# 멀티클러스터: kubeconfig에 등록된 모든 컨텍스트를 그대로 사용 (강제 전환 없음)
CTX_COUNT=$(kubectl config get-contexts -o name 2>/dev/null | wc -l)
[ "$CTX_COUNT" -gt 0 ] || fail "kubeconfig에 등록된 컨텍스트가 없습니다"
ok "kubeconfig 컨텍스트 ${CTX_COUNT}개 발견"

kubectl get nodes >/dev/null 2>&1 || fail "현재 컨텍스트의 K8s 클러스터에 연결할 수 없습니다"
NODE_COUNT=$(kubectl get nodes --no-headers 2>/dev/null | wc -l)
ok "현재 컨텍스트 K8s 클러스터 연결 확인 (${NODE_COUNT}노드)"

# ═══════════════════════════════════════
# 2. Docker 서비스 (MariaDB + ChartMuseum)
# ═══════════════════════════════════════
step 2 "Docker 서비스 시작 (MariaDB + ChartMuseum)"

cd "$REPO_DIR"

# 기존 컨테이너가 있으면 시작, 없으면 docker compose로 생성
if docker ps --format '{{.Names}}' | grep -q '^anycloud-db$' && docker ps --format '{{.Names}}' | grep -q '^chartmuseum$'; then
  ok "MariaDB + ChartMuseum 이미 실행 중"
else
  # 기존 중지 컨테이너 정리 후 compose up
  docker compose up -d anycloud-db chartmuseum 2>/dev/null || \
  docker-compose up -d anycloud-db chartmuseum 2>/dev/null || \
  {
    # docker compose 실패 시 개별 컨테이너로 실행
    warn "docker compose 실패 — 개별 컨테이너로 시작"

    if ! docker ps -a --format '{{.Names}}' | grep -q '^anycloud-db$'; then
      docker run -d --name anycloud-db \
        -p 13306:3306 \
        -e MYSQL_ROOT_PASSWORD=yourP@ssW0rds \
        -e MYSQL_DATABASE=aipaas \
        -e MYSQL_USER=anycloud \
        -e MYSQL_PASSWORD=anycloud \
        -v "$INFRA_DIR/db-init.sql:/docker-entrypoint-initdb.d/01-init.sql:ro" \
        mariadb:10.11 >/dev/null
    else
      docker start anycloud-db 2>/dev/null || true
    fi

    if ! docker ps -a --format '{{.Names}}' | grep -q '^chartmuseum$'; then
      docker run -d --name chartmuseum \
        -p 8880:8080 \
        -e STORAGE=local \
        -e STORAGE_LOCAL_ROOTDIR=/charts \
        -v "$REPO_DIR/chartmuseum_data:/charts" \
        ghcr.io/helm/chartmuseum:v0.16.0 >/dev/null
    else
      docker start chartmuseum 2>/dev/null || true
    fi
  }
  ok "Docker 서비스 시작 완료"
fi

# MariaDB 준비 대기
echo "  MariaDB 준비 대기 중..."
for i in $(seq 1 30); do
  if docker exec anycloud-db mariadb -uroot -p'yourP@ssW0rds' -e "SELECT 1" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
ok "MariaDB 준비 완료 (localhost:13306)"

# ChartMuseum 준비 대기
for i in $(seq 1 15); do
  if curl -s http://localhost:8880/api/charts >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
ok "ChartMuseum 준비 완료 (localhost:8880)"

# ═══════════════════════════════════════
# 3. DB 초기화 + 데이터 설정
# ═══════════════════════════════════════
step 3 "DB 초기화 + 클러스터 데이터 설정"

K8S_VERSION=$(kubectl version -o json 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin)['serverVersion']['gitVersion'])" 2>/dev/null || echo "unknown")

# 인증은 외부 kubeconfig의 정적 자격증명만을 사용합니다.
# (exec 플러그인 토큰 발급, DB token/cert 저장 로직은 모두 제거되었습니다)

# kubeconfig의 모든 컨텍스트를 순회하여 DB cluster 테이블에 upsert.
# 백엔드 KubernetesClientConfig가 cluster.id를 컨텍스트 이름으로 사용하므로,
# 컨텍스트 이름 == cluster.id 매핑을 유지해야 멀티클러스터 호출이 정상 동작합니다.
CONTEXTS=$(kubectl config get-contexts -o name 2>/dev/null || echo "")
if [ -z "$CONTEXTS" ]; then
  fail "kubeconfig에 등록된 컨텍스트가 없습니다"
fi
ok "동기화 대상 컨텍스트 ${#CONTEXTS} 개 발견"

# DB 테이블 + 데이터 upsert (db-init.sql이 docker-entrypoint에서 실행되었을 수도 있음)
docker exec -i anycloud-db mariadb -uroot -p'yourP@ssW0rds' <<SQL
CREATE DATABASE IF NOT EXISTS aipaas DEFAULT CHARACTER SET utf8mb4;
GRANT ALL PRIVILEGES ON aipaas.* TO 'anycloud'@'%';
FLUSH PRIVILEGES;
USE aipaas;

CREATE TABLE IF NOT EXISTS cluster (
  id VARCHAR(45) NOT NULL PRIMARY KEY,
  description VARCHAR(255), status VARCHAR(45), version VARCHAR(45),
  api_server_url VARCHAR(100) NOT NULL, api_server_ip VARCHAR(45),
  server_ca MEDIUMTEXT, client_ca MEDIUMTEXT, client_key MEDIUMTEXT, client_token MEDIUMTEXT,
  monit_server_url VARCHAR(100), cluster_type VARCHAR(100) NOT NULL DEFAULT '', cluster_provider VARCHAR(100) NOT NULL DEFAULT '',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS helm_repo (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  name VARCHAR(100) NOT NULL, url VARCHAR(100) NOT NULL,
  username VARCHAR(100), password VARCHAR(100), ca_file LONGTEXT,
  insecure_skip_tls_verify TINYINT(1) NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS gpu_reservation (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  release_name VARCHAR(100) NOT NULL, namespace VARCHAR(100) NOT NULL DEFAULT 'default',
  cluster_id VARCHAR(45) NOT NULL, gpu_count INT NOT NULL DEFAULT 1,
  estimated_minutes INT NOT NULL, unit_price_krw INT NOT NULL DEFAULT 1200,
  estimated_cost_krw INT NOT NULL DEFAULT 0, deployed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_release_cluster (release_name, cluster_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO helm_repo (id, name, url, insecure_skip_tls_verify) VALUES ('1', 'bitnami', 'https://charts.bitnami.com/bitnami', 0);
INSERT INTO helm_repo (id, name, url, insecure_skip_tls_verify) VALUES ('4', 'chart-museum-external', 'http://localhost:8880', 0)
ON DUPLICATE KEY UPDATE url='http://localhost:8880';
SQL
ok "DB 스키마/Helm 레포 초기화 완료"

# 컨텍스트별 cluster 행 upsert
SYNCED=0
for CTX in $CONTEXTS; do
  CTX_API=$(kubectl config view -o jsonpath="{.clusters[?(@.name==\"$(kubectl config view -o jsonpath="{.contexts[?(@.name==\"$CTX\")].context.cluster}")\")].cluster.server}" 2>/dev/null || echo "")
  if [ -z "$CTX_API" ]; then
    warn "컨텍스트 '$CTX' API server URL을 찾을 수 없습니다 — 스킵"
    continue
  fi
  CTX_HOST=$(echo "$CTX_API" | sed -E 's|https?://||; s|:.*||')
  # SQL injection 방지용 escaping (작은따옴표만 처리)
  CTX_ESC=$(printf '%s' "$CTX" | sed "s/'/''/g")
  API_ESC=$(printf '%s' "$CTX_API" | sed "s/'/''/g")
  HOST_ESC=$(printf '%s' "$CTX_HOST" | sed "s/'/''/g")

  docker exec -i anycloud-db mariadb -uroot -p'yourP@ssW0rds' aipaas <<SQL >/dev/null
INSERT INTO cluster (id, description, status, version, api_server_url, api_server_ip, monit_server_url, cluster_type, cluster_provider)
VALUES ('$CTX_ESC', 'kubeconfig context: $CTX_ESC', 'ACTIVE', '$K8S_VERSION', '$API_ESC', '$HOST_ESC', 'http://localhost:9090', 'k8s', 'on-premise')
ON DUPLICATE KEY UPDATE
  api_server_url='$API_ESC',
  api_server_ip='$HOST_ESC',
  monit_server_url='http://localhost:9090',
  version='$K8S_VERSION',
  status='ACTIVE';
SQL
  ok "동기화: $CTX → $CTX_API"
  SYNCED=$((SYNCED + 1))
done
ok "DB 클러스터 동기화 완료 ($SYNCED 개)"

# ═══════════════════════════════════════
# 4. MLOps 샘플 차트 등록
# ═══════════════════════════════════════
step 4 "ChartMuseum MLOps 차트 등록"

CHART_COUNT=$(curl -s http://localhost:8880/api/charts 2>/dev/null | python3 -c "import sys,json; print(len(json.load(sys.stdin)))" 2>/dev/null || echo "0")
if [ "$CHART_COUNT" -ge 6 ] 2>/dev/null; then
  ok "MLOps 차트 ${CHART_COUNT}개 이미 등록됨"
else
  bash "$INFRA_DIR/sample-charts/create-charts.sh"
  ok "MLOps 차트 6종 등록 완료"
fi

helm repo add chart-museum-external http://localhost:8880 2>/dev/null || true
helm repo update chart-museum-external 2>/dev/null || true
ok "Helm CLI 레포 등록"

# ═══════════════════════════════════════
# 5. K8s 네임스페이스
# ═══════════════════════════════════════
step 5 "K8s 네임스페이스 ($NAMESPACE)"

kubectl create namespace "$NAMESPACE" 2>/dev/null && ok "$NAMESPACE NS 생성" || ok "$NAMESPACE NS 이미 존재"

# ═══════════════════════════════════════
# 6. Prometheus Stack
# ═══════════════════════════════════════
step 6 "Prometheus Stack ($NAMESPACE 전용)"

if helm status "$PROM_RELEASE" -n "$NAMESPACE" >/dev/null 2>&1; then
  ok "이미 설치됨"
else
  helm repo add prometheus-community https://prometheus-community.github.io/helm-charts 2>/dev/null || true
  helm repo update prometheus-community
  helm install "$PROM_RELEASE" prometheus-community/kube-prometheus-stack \
    --namespace "$NAMESPACE" \
    -f "$INFRA_DIR/ai-pass-prometheus-values.yaml" \
    --wait --timeout 3m
  ok "설치 완료"
fi

# ═══════════════════════════════════════
# 7. K8s 추가 리소스 (Ingress, ServiceMonitor, GPU, helm-exporter)
# ═══════════════════════════════════════
step 7 "K8s 추가 리소스 적용"

# Prometheus + AlertManager Ingress
cat <<EOF | kubectl apply -f - >/dev/null
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: prometheus-ingress
  namespace: $NAMESPACE
  annotations:
    nginx.ingress.kubernetes.io/proxy-read-timeout: "300"
spec:
  ingressClassName: nginx
  rules:
    - host: prometheus-aipass.innogrid.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: $PROM_SVC
                port:
                  number: 9090
---
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: alertmanager-ingress
  namespace: $NAMESPACE
  annotations:
    nginx.ingress.kubernetes.io/proxy-read-timeout: "300"
spec:
  ingressClassName: nginx
  rules:
    - host: alertmanager-aipass.innogrid.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: ${PROM_RELEASE}-kube-promethe-alertmanager
                port:
                  number: 9093
EOF
ok "Prometheus Ingress 적용"

# node-exporter ServiceMonitor (monitoring NS 기존 exporter 스크랩)
cat <<EOF | kubectl apply -f - >/dev/null
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: external-node-exporter
  namespace: $NAMESPACE
  labels:
    release: $PROM_RELEASE
spec:
  namespaceSelector:
    matchNames:
      - monitoring
  selector:
    matchLabels:
      app.kubernetes.io/name: prometheus-node-exporter
  endpoints:
    - port: metrics
      interval: 30s
EOF
ok "node-exporter ServiceMonitor 적용"

# GPU ResourceQuota + Pricing
cat <<EOF | kubectl apply -f - >/dev/null
apiVersion: v1
kind: ResourceQuota
metadata:
  name: gpu-quota
  namespace: $NAMESPACE
spec:
  hard:
    requests.nvidia.com/gpu: "4"
    limits.nvidia.com/gpu: "4"
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: gpu-pricing
  namespace: $NAMESPACE
data:
  A100_KRW_PER_HOUR: "1200"
  V100_KRW_PER_HOUR: "800"
  T4_KRW_PER_HOUR: "400"
  DEFAULT_KRW_PER_HOUR: "600"
EOF
ok "GPU ResourceQuota + 가격 ConfigMap 적용"

# helm-exporter
if kubectl get deploy -n "$NAMESPACE" helm-exporter >/dev/null 2>&1; then
  ok "helm-exporter 이미 설치됨"
else
  cat <<EOF | kubectl apply -f - >/dev/null
apiVersion: v1
kind: ServiceAccount
metadata:
  name: helm-exporter
  namespace: $NAMESPACE
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: helm-exporter-${NAMESPACE}
rules:
  - apiGroups: [""]
    resources: ["secrets", "namespaces"]
    verbs: ["get", "list", "watch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: helm-exporter-${NAMESPACE}
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: helm-exporter-${NAMESPACE}
subjects:
  - kind: ServiceAccount
    name: helm-exporter
    namespace: $NAMESPACE
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: helm-exporter
  namespace: $NAMESPACE
  labels:
    app.kubernetes.io/name: helm-exporter
spec:
  replicas: 1
  selector:
    matchLabels:
      app.kubernetes.io/name: helm-exporter
  template:
    metadata:
      labels:
        app.kubernetes.io/name: helm-exporter
    spec:
      serviceAccountName: helm-exporter
      containers:
        - name: helm-exporter
          image: sstarcher/helm-exporter:latest
          ports:
            - containerPort: 9571
              name: http
          args: ["-namespaces="]
---
apiVersion: v1
kind: Service
metadata:
  name: helm-exporter
  namespace: $NAMESPACE
  labels:
    app.kubernetes.io/name: helm-exporter
spec:
  selector:
    app.kubernetes.io/name: helm-exporter
  ports:
    - port: 9571
      targetPort: 9571
      name: http
---
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: helm-exporter
  namespace: $NAMESPACE
  labels:
    release: $PROM_RELEASE
spec:
  endpoints:
  - interval: 30s
    port: http
    honorLabels: true
  selector:
    matchLabels:
      app.kubernetes.io/name: helm-exporter
EOF
  ok "helm-exporter 설치 완료"
fi

# ═══════════════════════════════════════
# 8. Prometheus Pod 준비 대기
# ═══════════════════════════════════════
step 8 "Prometheus Pod 준비 대기"

echo "  Pod 상태 확인 중..."
kubectl wait --for=condition=ready pod \
  -l app.kubernetes.io/name=prometheus \
  -n "$NAMESPACE" --timeout=120s 2>/dev/null && ok "Prometheus Ready" || warn "Prometheus 대기 시간 초과 (계속 진행)"

# ═══════════════════════════════════════
# 9. Prometheus port-forward
# ═══════════════════════════════════════
step 9 "Prometheus port-forward (localhost:9090)"

# 기존 port-forward 정리
pkill -f "port-forward.*${PROM_SVC}" 2>/dev/null || true
sleep 1

kubectl port-forward -n "$NAMESPACE" "svc/${PROM_SVC}" 9090:9090 >/dev/null 2>&1 &
PF_PID=$!
echo "  port-forward PID: $PF_PID"

# 연결 확인
for i in $(seq 1 10); do
  if curl -s "http://localhost:9090/api/v1/query?query=up" >/dev/null 2>&1; then
    ok "Prometheus 접근 확인 (localhost:9090)"
    break
  fi
  sleep 1
done

# ═══════════════════════════════════════
# 10. 백엔드 빌드 + 시작
# ═══════════════════════════════════════
step 10 "백엔드 빌드 + 시작"

cd "$REPO_DIR"

# 기존 백엔드 프로세스 정리
pkill -f "anycloud:bootRun" 2>/dev/null || true
sleep 1

echo "  Gradle 빌드 중..."
./gradlew :anycloud:classes -q 2>/dev/null && ok "빌드 성공" || fail "빌드 실패"

echo "  백엔드 시작 중..."
./gradlew :anycloud:bootRun > /tmp/anycloud-backend.log 2>&1 &
BACKEND_PID=$!
echo "  백엔드 PID: $BACKEND_PID (로그: /tmp/anycloud-backend.log)"

for i in $(seq 1 30); do
  if curl -s http://localhost:8888/api/v1/system/clusters 2>/dev/null | grep -q "innogrid"; then
    ok "백엔드 시작 완료 (localhost:8888)"
    break
  fi
  if [ "$i" = "30" ]; then
    warn "백엔드 시작 대기 시간 초과 (로그 확인: tail -f /tmp/anycloud-backend.log)"
  fi
  sleep 2
done

# ═══════════════════════════════════════
# 11. 프론트엔드 시작
# ═══════════════════════════════════════
step 11 "프론트엔드 시작"

if [ -n "$FRONTEND_DIR" ] && [ -d "$FRONTEND_DIR" ]; then
  cd "$FRONTEND_DIR"

  # 기존 프론트엔드 프로세스 정리
  pkill -f "vite" 2>/dev/null || true
  sleep 1

  if ! command -v pnpm >/dev/null 2>&1; then
    warn "pnpm이 설치되어 있지 않습니다. npm install -g pnpm 으로 설치 후 수동 실행:"
    echo "  cd $FRONTEND_DIR && pnpm install && pnpm dev"
  else
    pnpm install --frozen-lockfile 2>/dev/null || pnpm install
    pnpm dev > /tmp/anycloud-frontend.log 2>&1 &
    FRONTEND_PID=$!
    echo "  프론트엔드 PID: $FRONTEND_PID (로그: /tmp/anycloud-frontend.log)"

    for i in $(seq 1 15); do
      if curl -s -o /dev/null -w "%{http_code}" http://localhost:5173 2>/dev/null | grep -q "200"; then
        ok "프론트엔드 시작 완료 (localhost:5173)"
        break
      fi
      sleep 1
    done
  fi
else
  warn "프론트엔드 디렉토리를 찾을 수 없습니다: $REPO_DIR/../ai-paas-web"
  echo "  수동 실행: cd <ai-paas-web> && pnpm install && pnpm dev"
fi

# ═══════════════════════════════════════
# 12. 헬스 체크
# ═══════════════════════════════════════
step 12 "전체 시스템 헬스 체크"

echo ""
PASS=0
TOTAL=0

check() {
  TOTAL=$((TOTAL + 1))
  if eval "$2" >/dev/null 2>&1; then
    ok "$1"
    PASS=$((PASS + 1))
  else
    warn "$1 — 실패"
  fi
}

check "K8s 클러스터 연결" "kubectl get nodes"
check "ai-pass Prometheus Pod" "kubectl get pod -n $NAMESPACE -l app.kubernetes.io/name=prometheus --no-headers | grep Running"
check "ai-pass helm-exporter Pod" "kubectl get pod -n $NAMESPACE -l app.kubernetes.io/name=helm-exporter --no-headers | grep Running"
check "Prometheus API (localhost:9090)" "curl -sf http://localhost:9090/api/v1/query?query=up"
check "MariaDB (localhost:13306)" "docker exec anycloud-db mariadb -uanycloud -panycloud aipaas -e 'SELECT 1'"
check "ChartMuseum (localhost:8880)" "curl -sf http://localhost:8880/api/charts"
check "백엔드 API (localhost:8888)" "curl -sf http://localhost:8888/api/v1/system/clusters"
check "프론트엔드 (localhost:5173)" "curl -sf http://localhost:5173"

echo ""
echo -e "${GREEN}╔══════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║          셋업 완료! ($PASS/$TOTAL 정상)                ║${NC}"
echo -e "${GREEN}╚══════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "  ${BLUE}백엔드 API${NC}    : http://localhost:8888/api/v1"
echo -e "  ${BLUE}Swagger UI${NC}    : http://localhost:8888/api/v1/docs"
echo -e "  ${BLUE}프론트엔드${NC}     : http://localhost:5173  (admin / 1234)"
echo -e "  ${BLUE}Prometheus${NC}    : http://localhost:9090"
echo -e "  ${BLUE}ChartMuseum${NC}   : http://localhost:8880"
echo -e "  ${BLUE}MariaDB${NC}       : localhost:13306  (root / yourP@ssW0rds)"
echo ""
echo -e "  ${YELLOW}로그 확인${NC}:"
echo -e "    백엔드   : tail -f /tmp/anycloud-backend.log"
echo -e "    프론트엔드: tail -f /tmp/anycloud-frontend.log"
echo ""
echo -e "  ${YELLOW}종료하려면${NC}:"
echo -e "    kill $BACKEND_PID ${FRONTEND_PID:-} $PF_PID  # 백엔드 + 프론트엔드 + port-forward"
echo -e "    docker compose down                   # MariaDB + ChartMuseum"
