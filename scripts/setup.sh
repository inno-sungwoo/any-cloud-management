#!/bin/bash
# =============================================================================
# 3차년도 MLOps 플랫폼 — 인프라 + 백엔드 원클릭 셋업
# 인수인계자가 이 스크립트 하나로 전체 환경을 구축할 수 있습니다.
#
# 사용법:
#   chmod +x scripts/setup.sh
#   ./scripts/setup.sh
#
# 사전 요구사항:
#   - Docker Desktop 실행 + Kubernetes 활성화
#   - brew, helm, kubectl 설치
#   - Java 17+ (SDKMAN 또는 brew install openjdk@17)
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
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

echo -e "${BLUE}╔════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║  3차년도 MLOps 플랫폼 — 인프라 + 백엔드 셋업  ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════╝${NC}"

# ─────────────────────────────────────
# 0. 사전 검사
# ─────────────────────────────────────
step 1 "사전 요구사항 검사"

command -v docker >/dev/null 2>&1 || fail "docker가 설치되어 있지 않습니다"
command -v kubectl >/dev/null 2>&1 || fail "kubectl이 설치되어 있지 않습니다"
command -v helm >/dev/null 2>&1 || fail "helm이 설치되어 있지 않습니다"

# Java 확인
if [ -n "${JAVA_HOME:-}" ] && "$JAVA_HOME/bin/java" -version >/dev/null 2>&1; then
  JAVA_VER=$("$JAVA_HOME/bin/java" -version 2>&1 | head -1)
  ok "Java: $JAVA_VER"
elif command -v java >/dev/null 2>&1; then
  JAVA_VER=$(java -version 2>&1 | head -1)
  ok "Java: $JAVA_VER"
else
  fail "Java가 설치되어 있지 않습니다. sdk install java 21.0.9-tem 또는 brew install openjdk@17"
fi

# K8s 컨텍스트 확인
KUBE_CTX=$(kubectl config current-context 2>/dev/null || echo "none")
if [ "$KUBE_CTX" = "docker-desktop" ]; then
  ok "K8s context: docker-desktop"
else
  warn "현재 K8s context: $KUBE_CTX (docker-desktop 권장)"
  echo -e "  ${YELLOW}docker-desktop으로 변경하시겠습니까? (y/N)${NC}"
  read -r ans
  if [[ "$ans" =~ ^[Yy] ]]; then
    kubectl config use-context docker-desktop
    ok "docker-desktop으로 변경됨"
  fi
fi

kubectl get nodes >/dev/null 2>&1 || fail "K8s 클러스터에 연결할 수 없습니다. Docker Desktop K8s를 활성화하세요."
ok "K8s 클러스터 연결 확인"

# ─────────────────────────────────────
# 1. dnsmasq 설정
# ─────────────────────────────────────
step 2 "DNS 설정 (*.aipaas → 127.0.0.1)"

if command -v dnsmasq >/dev/null 2>&1; then
  ok "dnsmasq 이미 설치됨"
else
  echo "  dnsmasq 설치 중..."
  brew install dnsmasq
fi

DNSMASQ_CONF="/opt/homebrew/etc/dnsmasq.conf"
if [ ! -f "$DNSMASQ_CONF" ]; then
  DNSMASQ_CONF="/usr/local/etc/dnsmasq.conf"
fi

if ! grep -q "address=/.aipaas/" "$DNSMASQ_CONF" 2>/dev/null; then
  echo 'address=/.aipaas/127.0.0.1' >> "$DNSMASQ_CONF"
  sudo brew services restart dnsmasq
  ok "dnsmasq 설정 추가 완료"
else
  ok "dnsmasq *.aipaas 설정 이미 존재"
fi

if [ ! -f /etc/resolver/aipaas ]; then
  sudo mkdir -p /etc/resolver
  echo 'nameserver 127.0.0.1' | sudo tee /etc/resolver/aipaas >/dev/null
  ok "/etc/resolver/aipaas 생성 완료"
else
  ok "/etc/resolver/aipaas 이미 존재"
fi

# Java(Netty)는 /etc/resolver를 무시하므로 /etc/hosts에 등록 필수
if ! grep -q "prometheus.aipaas" /etc/hosts 2>/dev/null; then
  echo '127.0.0.1 prometheus.aipaas alertmanager.aipaas' | sudo tee -a /etc/hosts >/dev/null
  ok "/etc/hosts에 prometheus.aipaas 등록 완료"
else
  ok "/etc/hosts에 prometheus.aipaas 이미 존재"
fi

# ─────────────────────────────────────
# 2. K8s 네임스페이스
# ─────────────────────────────────────
step 3 "K8s 네임스페이스 생성"

kubectl create namespace monitoring 2>/dev/null && ok "monitoring NS 생성" || ok "monitoring NS 이미 존재"
kubectl create namespace ai-pass3 2>/dev/null && ok "ai-pass3 NS 생성" || ok "ai-pass3 NS 이미 존재"

# ─────────────────────────────────────
# 3. Nginx Ingress Controller
# ─────────────────────────────────────
step 4 "Nginx Ingress Controller"

if kubectl get deploy -n ingress-nginx ingress-nginx-controller >/dev/null 2>&1; then
  ok "이미 설치됨"
else
  helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx 2>/dev/null || true
  helm repo update ingress-nginx
  helm install ingress-nginx ingress-nginx/ingress-nginx \
    --namespace ingress-nginx --create-namespace \
    --set controller.service.type=LoadBalancer
  ok "설치 완료"
fi

# ─────────────────────────────────────
# 4. Prometheus Stack
# ─────────────────────────────────────
step 5 "Prometheus Stack"

if kubectl get statefulset -n monitoring ai-pass3-prom-kube-prometh-prometheus >/dev/null 2>&1; then
  ok "이미 설치됨"
else
  helm repo add prometheus-community https://prometheus-community.github.io/helm-charts 2>/dev/null || true
  helm repo update prometheus-community
  helm install ai-pass3-prom prometheus-community/kube-prometheus-stack \
    --namespace monitoring \
    -f "$INFRA_DIR/ai-pass3-prometheus-values.yaml"
  ok "설치 완료"
fi

kubectl apply -f "$INFRA_DIR/ingress.yaml" >/dev/null
ok "Ingress (prometheus.aipaas, alertmanager.aipaas) 적용"

# ─────────────────────────────────────
# 5. Mock GPU Exporter
# ─────────────────────────────────────
step 6 "Mock GPU Exporter (4x RTX 3060 시뮬레이션)"

if kubectl get deploy -n ai-pass3 mock-nvidia-smi-exporter >/dev/null 2>&1; then
  ok "이미 배포됨"
else
  docker build -t mock-nvidia-smi-exporter:latest "$INFRA_DIR/mock-nvidia-smi-exporter" -q
  kubectl apply -f "$INFRA_DIR/mock-nvidia-smi-exporter/deployment.yaml" >/dev/null
  ok "빌드 + 배포 완료"
fi

# ─────────────────────────────────────
# 6. GPU Quota + Pricing ConfigMap
# ─────────────────────────────────────
step 7 "GPU ResourceQuota + 가격 ConfigMap"

kubectl apply -f "$INFRA_DIR/gpu-quota.yaml" >/dev/null
ok "GPU ResourceQuota (4개) 적용"

kubectl apply -f "$INFRA_DIR/gpu-pricing-configmap.yaml" >/dev/null
ok "GPU 가격 ConfigMap 적용"

# ─────────────────────────────────────
# 7. helm-exporter
# ─────────────────────────────────────
step 8 "helm-exporter"

if kubectl get deploy -n monitoring helm-exporter >/dev/null 2>&1; then
  ok "이미 설치됨"
else
  helm repo add speakeasyapi https://speakeasyapi.github.io/helm-exporter 2>/dev/null || true
  helm repo update speakeasyapi
  helm install helm-exporter speakeasyapi/helm-exporter \
    --namespace monitoring \
    -f "$INFRA_DIR/helm-exporter-values.yaml"
  ok "설치 완료"
fi

kubectl apply -f "$INFRA_DIR/helm-exporter-servicemonitor.yaml" >/dev/null
ok "honorLabels ServiceMonitor 적용"

# ─────────────────────────────────────
# 8. ChartMuseum
# ─────────────────────────────────────
step 9 "ChartMuseum (차트 저장소)"

if docker ps -a --format '{{.Names}}' | grep -q '^chartmuseum$'; then
  docker start chartmuseum 2>/dev/null || true
  ok "이미 존재, 시작됨"
else
  docker run -d --name chartmuseum \
    -p 8880:8080 \
    -e STORAGE=local \
    -e STORAGE_LOCAL_ROOTDIR=/charts \
    ghcr.io/helm/chartmuseum:v0.16.0 >/dev/null
  ok "컨테이너 생성 완료"
fi

# ChartMuseum 준비 대기
echo "  ChartMuseum 준비 대기 중..."
for i in $(seq 1 15); do
  if curl -s http://localhost:8880/api/charts >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

# 차트 등록
CHART_COUNT=$(curl -s http://localhost:8880/api/charts 2>/dev/null | python3 -c "import sys,json; print(len(json.load(sys.stdin)))" 2>/dev/null || echo "0")
if [ "$CHART_COUNT" -ge 6 ] 2>/dev/null; then
  ok "MLOps 차트 $CHART_COUNT개 이미 등록됨"
else
  bash "$INFRA_DIR/sample-charts/create-charts.sh"
  ok "MLOps 차트 6종 등록 완료"
fi

# Helm CLI 레포 등록
helm repo add chart-museum-external http://localhost:8880 2>/dev/null || true
helm repo update chart-museum-external 2>/dev/null || true
ok "Helm CLI 레포 등록"

# ─────────────────────────────────────
# 9. MariaDB
# ─────────────────────────────────────
step 10 "MariaDB"

if docker ps -a --format '{{.Names}}' | grep -q '^anycloud-db$'; then
  docker start anycloud-db 2>/dev/null || true
  ok "이미 존재, 시작됨"
else
  docker run -d --name anycloud-db \
    -p 13306:3306 \
    -e MYSQL_ROOT_PASSWORD=yourP@ssW0rds \
    -e MYSQL_DATABASE=aipaas \
    -e MYSQL_USER=anycloud \
    -e MYSQL_PASSWORD=anycloud \
    mariadb:10.11 >/dev/null
  ok "컨테이너 생성 완료"

  echo "  MariaDB 준비 대기 중..."
  for i in $(seq 1 30); do
    if docker exec anycloud-db mariadb -uroot -p'yourP@ssW0rds' -e "SELECT 1" >/dev/null 2>&1; then
      break
    fi
    sleep 1
  done
  ok "MariaDB 준비 완료"
fi

# ─────────────────────────────────────
# 10. DB 초기화 (cluster + helm_repo)
# ─────────────────────────────────────
step 11 "DB 초기화 (cluster, helm_repo, gpu_reservation)"

API_SERVER_URL=$(kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}')
SERVER_CA=$(kubectl config view --minify --raw -o jsonpath='{.clusters[0].cluster.certificate-authority-data}' 2>/dev/null || echo "")
CLIENT_CA=$(kubectl config view --minify --raw -o jsonpath='{.users[0].user.client-certificate-data}' 2>/dev/null || echo "")
CLIENT_KEY=$(kubectl config view --minify --raw -o jsonpath='{.users[0].user.client-key-data}' 2>/dev/null || echo "")

# cluster 테이블에 데이터 존재 확인
HAS_CLUSTER=$(docker exec anycloud-db mariadb -uroot -p'yourP@ssW0rds' -N -e \
  "SELECT COUNT(*) FROM aipaas.cluster WHERE id='innogrid-aikube'" 2>/dev/null || echo "0")

if [ "$HAS_CLUSTER" = "0" ] 2>/dev/null; then
  # 테이블이 없거나 데이터가 없으면 앱이 JPA로 생성할 때까지 대기
  warn "cluster 테이블 데이터 없음 — 백엔드 첫 실행 시 JPA가 테이블 생성 후 아래 SQL 수동 실행 필요:"
  echo ""
  echo "  docker exec -i anycloud-db mariadb -uroot -p'yourP@ssW0rds' aipaas <<'SQL'"
  echo "  INSERT INTO cluster (id, name, api_server_url, api_server_ip, server_ca, client_ca, client_key, monit_server_url, description, status)"
  echo "  VALUES ('innogrid-aikube', 'innogrid-aikube', '$API_SERVER_URL', '127.0.0.1', '$SERVER_CA', '$CLIENT_CA', '$CLIENT_KEY', 'http://prometheus.aipaas', 'Docker Desktop K8s', 'ACTIVE')"
  echo "  ON DUPLICATE KEY UPDATE api_server_url='$API_SERVER_URL', server_ca='$SERVER_CA', client_ca='$CLIENT_CA', client_key='$CLIENT_KEY';"
  echo "  SQL"
  echo ""
else
  # 기존 데이터 업데이트
  docker exec anycloud-db mariadb -uroot -p'yourP@ssW0rds' aipaas -e \
    "UPDATE cluster SET api_server_url='$API_SERVER_URL', server_ca='$SERVER_CA', client_ca='$CLIENT_CA', client_key='$CLIENT_KEY', monit_server_url='http://prometheus.aipaas' WHERE id='innogrid-aikube'" 2>/dev/null
  ok "cluster 테이블 업데이트 (API URL: $API_SERVER_URL)"
fi

# helm_repo 업데이트
docker exec anycloud-db mariadb -uroot -p'yourP@ssW0rds' aipaas -e \
  "UPDATE helm_repo SET url='http://localhost:8880' WHERE id='4'" 2>/dev/null && \
  ok "helm_repo ChartMuseum URL 업데이트" || \
  warn "helm_repo 업데이트 실패 (백엔드 첫 실행 후 재시도)"

# gpu_reservation 테이블 생성
docker exec anycloud-db mariadb -uroot -p'yourP@ssW0rds' aipaas -e \
  "CREATE TABLE IF NOT EXISTS gpu_reservation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    release_name VARCHAR(100) NOT NULL,
    namespace VARCHAR(100) NOT NULL DEFAULT 'default',
    cluster_id VARCHAR(45) NOT NULL,
    gpu_count INT NOT NULL DEFAULT 1,
    estimated_minutes INT NOT NULL,
    unit_price_krw INT NOT NULL DEFAULT 1200,
    estimated_cost_krw INT NOT NULL DEFAULT 0,
    deployed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_release_cluster (release_name, cluster_id)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" 2>/dev/null && \
  ok "gpu_reservation 테이블 준비 완료" || \
  warn "gpu_reservation 테이블 생성 실패 (JPA가 자동 생성할 수 있음)"

# ─────────────────────────────────────
# 11. 백엔드 빌드 확인
# ─────────────────────────────────────
step 12 "백엔드 빌드 확인"

cd "$REPO_DIR"
CURRENT_BRANCH=$(git branch --show-current)
if [ "$CURRENT_BRANCH" != "feat/3rd-year-monitoring-apis" ]; then
  warn "현재 브랜치: $CURRENT_BRANCH (feat/3rd-year-monitoring-apis 아님)"
  echo -e "  ${YELLOW}브랜치 전환하시겠습니까? (y/N)${NC}"
  read -r ans
  if [[ "$ans" =~ ^[Yy] ]]; then
    git checkout feat/3rd-year-monitoring-apis
    ok "브랜치 전환 완료"
  fi
fi

echo "  Gradle 빌드 테스트 중... (최초 실행 시 수 분 소요)"
./gradlew :anycloud:classes -q 2>/dev/null && ok "빌드 성공" || warn "빌드 실패 — Java 버전 및 설정 확인 필요"

# ─────────────────────────────────────
# 완료
# ─────────────────────────────────────
echo ""
echo -e "${GREEN}╔════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║             셋업 완료!                         ║${NC}"
echo -e "${GREEN}╚════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "백엔드 시작:"
echo -e "  ${BLUE}./gradlew :anycloud:bootRun${NC}"
echo -e "  → http://localhost:8888/api/v1/docs (Swagger UI)"
echo ""
echo -e "프론트엔드 시작 (ai-paas-web 레포에서):"
echo -e "  ${BLUE}pnpm install && pnpm dev${NC}"
echo -e "  → http://localhost:5173 (admin/1234)"
echo ""
echo -e "헬스 체크:"
echo -e "  curl -s localhost:8888/api/v1/system/clusters | python3 -m json.tool"
echo -e "  curl -s http://prometheus.aipaas/api/v1/query?query=up | head -5"
echo -e "  curl -s http://localhost:8880/api/charts | python3 -m json.tool"
echo ""
echo -e "상세 문서: ${BLUE}docs/HANDOVER.md${NC}"
