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

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
FRONTEND_DIR="$(cd "$REPO_DIR/../ai-paas-web" && pwd 2>/dev/null || echo "")"
INFRA_DIR="$REPO_DIR/docs/infrastructure"
LOCAL_KUBECONFIG="$REPO_DIR/config/kubeconfig"

# 프로젝트 내 local kubeconfig가 있으면 우선 사용
if [ -f "$LOCAL_KUBECONFIG" ]; then
  export KUBECONFIG="$LOCAL_KUBECONFIG"
  echo -e "${YELLOW}! 프로젝트 로컬 kubeconfig 사용 중: $LOCAL_KUBECONFIG${NC}"
fi

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

kubectl get nodes --request-timeout='5s' >/dev/null 2>&1 || fail "현재 컨텍스트의 K8s 클러스터에 연결할 수 없습니다 (timeout 5s)"
NODE_COUNT=$(kubectl get nodes --no-headers --request-timeout='5s' 2>/dev/null | wc -l)
ok "현재 컨텍스트 K8s 클러스터 연결 확인 (${NODE_COUNT}노드)"

# ═══════════════════════════════════════
# 2. Docker Compose 서비스 빌드 + 시작 (MariaDB + ChartMuseum + Backend)
# ═══════════════════════════════════════
step 2 "Docker Compose 서비스 빌드 + 시작 (db + chartmuseum + backend)"

cd "$REPO_DIR"

# .env 상태 안내 (docker compose 가 자동으로 .env 를 로드하므로 source 불필요)
if [ -f .env ]; then
  ok ".env 파일 발견 — docker compose 가 자동 로드합니다"
else
  warn ".env 없음 — docker-compose.yml 의 default 값으로 동작합니다"
  warn "운영 환경에서는 'cp .env.example .env' 후 비밀번호 변경을 권장합니다"
fi

# 포트 / 환경변수 / 볼륨 설정은 모두 docker-compose.yml에서 관리됨
if docker compose version >/dev/null 2>&1; then
  DC="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
  DC="docker-compose"
else
  fail "docker compose / docker-compose 명령을 찾을 수 없습니다"
fi

$DC up -d --build 2>&1 | tail -20 || fail "docker compose up 실패"
ok "docker compose up 완료 (db + chartmuseum + backend)"

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
# 3. DB 초기화 (스키마 + 클러스터 자동 등록)
# ═══════════════════════════════════════
step 3 "DB 초기화 안내"

# - 스키마: db-init.sql 이 docker-entrypoint-initdb.d 에서 컨테이너 첫 기동 시 자동 적용
# - 클러스터 데이터: 백엔드(KubeConfigClusterLoader)가 ApplicationReadyEvent 시점에
#   kubernetes.kubeconfig.path / KUBECONFIG 의 모든 컨텍스트를 cluster 테이블에 upsert
ok "DB 스키마: db-init.sql(docker-entrypoint)에서 처리됨"
ok "클러스터 데이터: 백엔드 KubeConfigClusterLoader 가 기동 시 자동 등록"

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
# 7. K8s 추가 리소스 (helm chart: any-cloud-management/k8s)
# ═══════════════════════════════════════
step 7 "K8s 추가 리소스 helm 배포 (ai-pass-resources)"

# Ingress / ServiceMonitor / GPU Quota+Pricing / helm-exporter 등은 모두
# any-cloud-management/k8s 헬름 차트로 관리됩니다.
RESOURCES_CHART="$REPO_DIR/k8s"
RESOURCES_RELEASE="ai-pass-resources"

helm upgrade --install "$RESOURCES_RELEASE" "$RESOURCES_CHART" \
  --namespace "$NAMESPACE" \
  --create-namespace \
  --set promRelease="$PROM_RELEASE" \
  --wait --timeout 3m
ok "ai-pass-resources 차트 배포 완료 (Ingress / ServiceMonitor / GPU / helm-exporter)"

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

kubectl port-forward --address 0.0.0.0 -n "$NAMESPACE" "svc/${PROM_SVC}" 9090:9090 >/dev/null 2>&1 &
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
# 10. 백엔드 헬스 체크 (이미 step 2에서 docker compose로 실행됨)
# ═══════════════════════════════════════
step 10 "백엔드 헬스 체크 (anycloud-backend 컨테이너)"

# 기존 로컬 gradle bootRun 프로세스가 떠 있다면 정리 (도커로 통합되었음)
pkill -f "anycloud:bootRun" 2>/dev/null || true

echo "  백엔드 컨테이너 준비 대기 중... (로그: docker logs -f anycloud-backend)"
for i in $(seq 1 5); do
  if curl -s -o /dev/null -w "%{http_code}" http://localhost:8888/api/v1/system/clusters 2>/dev/null | grep -q "200"; then
    ok "백엔드 준비 완료 (localhost:8888)"
    break
  fi
  if [ "$i" = "60" ]; then
    warn "백엔드 준비 대기 시간 초과 (로그 확인: docker logs -f anycloud-backend)"
  fi
  sleep 2
done

# ═══════════════════════════════════════
# (구) step 10-1 의 monit_server_url UPDATE 는 제거됨.
# 이제 KubeConfigClusterLoader 가 INSERT 시점에 application.properties 의
# anycloud.monit.default-url (docker 환경에서는 MONIT_DEFAULT_URL env) 값으로 자동 채움.
# ═══════════════════════════════════════

# ═══════════════════════════════════════
step 11 "프론트엔드 시작"

# if [ -n "$FRONTEND_DIR" ] && [ -d "$FRONTEND_DIR" ]; then
#   cd "$FRONTEND_DIR"

#   # 기존 프론트엔드 프로세스 정리
#   pkill -f "vite" 2>/dev/null || true
#   sleep 1

#   if ! command -v pnpm >/dev/null 2>&1; then
#     warn "pnpm이 설치되어 있지 않습니다. npm install -g pnpm 으로 설치 후 수동 실행:"
#     echo "  cd $FRONTEND_DIR && pnpm install && pnpm dev"
#   else
#     pnpm install --frozen-lockfile 2>/dev/null || pnpm install
#     pnpm dev > /tmp/anycloud-frontend.log 2>&1 &
#     FRONTEND_PID=$!
#     echo "  프론트엔드 PID: $FRONTEND_PID (로그: /tmp/anycloud-frontend.log)"

#     for i in $(seq 1 15); do
#       if curl -s -o /dev/null -w "%{http_code}" http://localhost:5173 2>/dev/null | grep -q "200"; then
#         ok "프론트엔드 시작 완료 (localhost:5173)"
#         break
#       fi
#       sleep 1
#     done
#   fi
# else
#   warn "프론트엔드 디렉토리를 찾을 수 없습니다: $REPO_DIR/../ai-paas-web"
#   echo "  수동 실행: cd <ai-paas-web> && pnpm install && pnpm dev"
# fi

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
echo -e "    kill ${BACKEND_PID:-} ${FRONTEND_PID:-} $PF_PID  # 백엔드 + 프론트엔드 + port-forward"
echo -e "    docker compose down                   # MariaDB + ChartMuseum"
