# 3차년도 백엔드 인수인계 가이드

> **브랜치**: `feat/3rd-year-monitoring-apis`
> **주제**: 카탈로그 기반 MLOps 개발 환경 자동 구축 기술 — 최적화 및 고도화
> **변경 규모**: 28 files changed, 1,532 insertions, 23 deletions

이 문서 하나로 ai-pass-3 레포 없이 백엔드 인수인계가 가능합니다.

---

## 1. 환경 구축 (처음부터)

### 1.1 사전 요구사항

| 항목 | 버전 | 설치 |
|------|------|------|
| Docker Desktop | K8s 활성화 | Settings → Kubernetes → Enable |
| Helm | 3.x | `brew install helm` |
| kubectl | 1.28+ | Docker Desktop 포함 |
| Java | 17+ (OpenJDK) | `sdk install java 21.0.9-tem` |
| dnsmasq | latest | `brew install dnsmasq` |

### 1.2 DNS 설정 (macOS)

```bash
# dnsmasq 와일드카드 DNS
brew install dnsmasq
echo 'address=/.aipaas/127.0.0.1' >> /opt/homebrew/etc/dnsmasq.conf
sudo brew services restart dnsmasq

# macOS resolver 등록
sudo mkdir -p /etc/resolver
echo 'nameserver 127.0.0.1' | sudo tee /etc/resolver/aipaas

# [필수] Java(Netty)는 /etc/resolver를 무시 → /etc/hosts에 직접 등록
echo '127.0.0.1 prometheus.aipaas alertmanager.aipaas' | sudo tee -a /etc/hosts
```

> **핵심 주의**: `/etc/hosts` 등록 안 하면 백엔드에서 Prometheus 접속 불가

### 1.3 K8s 인프라 설치

```bash
# 네임스페이스
kubectl create namespace monitoring
kubectl create namespace ai-pass3

# Nginx Ingress Controller
helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx
helm install ingress-nginx ingress-nginx/ingress-nginx \
  --namespace ingress-nginx --create-namespace \
  --set controller.service.type=LoadBalancer

# Prometheus Stack
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm install ai-pass3-prom prometheus-community/kube-prometheus-stack \
  --namespace monitoring \
  -f docs/infrastructure/ai-pass3-prometheus-values.yaml

# Ingress (prometheus.aipaas, alertmanager.aipaas)
kubectl apply -f docs/infrastructure/ingress.yaml

# Mock GPU Exporter (실제 GPU 없음, 4x RTX 3060 시뮬레이션)
cd docs/infrastructure/mock-nvidia-smi-exporter
docker build -t mock-nvidia-smi-exporter:latest .
kubectl apply -f deployment.yaml
cd -

# GPU ResourceQuota (4개 제한)
kubectl apply -f docs/infrastructure/gpu-quota.yaml

# GPU 가격 ConfigMap (단가 정보)
kubectl apply -f docs/infrastructure/gpu-pricing-configmap.yaml

# helm-exporter (릴리즈 메트릭 수집)
helm repo add speakeasyapi https://speakeasyapi.github.io/helm-exporter
helm install helm-exporter speakeasyapi/helm-exporter \
  --namespace monitoring \
  -f docs/infrastructure/helm-exporter-values.yaml
kubectl apply -f docs/infrastructure/helm-exporter-servicemonitor.yaml
```

> `honorLabels: true` 없으면 모든 릴리즈의 namespace가 `monitoring`으로 표시됨

### 1.4 ChartMuseum (차트 저장소)

```bash
docker run -d --name chartmuseum \
  -p 8880:8080 \
  -e STORAGE=local \
  -e STORAGE_LOCAL_ROOTDIR=/charts \
  ghcr.io/helm/chartmuseum:v0.16.0

# MLOps 차트 6종 등록
bash docs/infrastructure/sample-charts/create-charts.sh

# Helm CLI 레포 등록
helm repo add chart-museum-external http://localhost:8880
helm repo update
```

### 1.5 MariaDB

```bash
docker run -d --name anycloud-db \
  -p 13306:3306 \
  -e MYSQL_ROOT_PASSWORD=yourP@ssW0rds \
  -e MYSQL_DATABASE=aipaas \
  -e MYSQL_USER=anycloud \
  -e MYSQL_PASSWORD=anycloud \
  mariadb:10.11
```

DB 초기화: `docs/infrastructure/db-init.sql`의 플레이스홀더(`@@API_SERVER_URL@@` 등)를 kubeconfig에서 추출한 실제 값으로 치환 후 실행.

```bash
# K8s API 서버 URL 확인 (포트가 랜덤이므로 동적 추출 필요)
kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}'
```

### 1.6 백엔드 시작

```bash
git checkout feat/3rd-year-monitoring-apis
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :anycloud:bootRun
# → http://localhost:8888
```

### 1.7 헬스 체크

```bash
curl -s localhost:8888/api/v1/system/clusters | python3 -m json.tool
curl -s "http://prometheus.aipaas/api/v1/query?query=up" | head -5
curl -s http://localhost:8880/api/charts | python3 -m json.tool
```

---

## 2. 아키텍처

```
macOS (Docker Desktop)
├── Docker Desktop K8s (context: docker-desktop)
│   ├── monitoring NS
│   │   ├── Prometheus Stack (kube-prometheus-stack)
│   │   └── helm-exporter (ServiceMonitor + honorLabels)
│   ├── ai-pass3 NS
│   │   ├── mock-nvidia-smi-exporter (4x RTX 3060 시뮬레이션)
│   │   ├── ResourceQuota (GPU 4개 제한)
│   │   └── 배포된 서비스들 (gpu-jupyter, mlflow 등)
│   └── ingress-nginx NS
│       └── nginx ingress controller
├── Docker 컨테이너
│   ├── MariaDB (port 13306)
│   └── ChartMuseum (port 8880)
├── 네이티브 프로세스
│   ├── Spring Boot 백엔드 (port 8888)
│   └── Vite 프론트엔드 (port 5173)
└── dnsmasq (*.aipaas → 127.0.0.1)
```

### 백엔드 → Prometheus 연동 흐름

1. DB `cluster` 테이블의 `monit_server_url` = `http://prometheus.aipaas`
2. 백엔드가 DB에서 URL 읽음
3. Prometheus HTTP API (`/api/v1/query`) 직접 REST 호출
4. 별도 SDK 없음, `RestTemplate`으로 PromQL 실행

### Prometheus 메트릭 소스

| 컴포넌트 | 네임스페이스 | 수집 방식 |
|----------|------------|----------|
| kube-state-metrics | monitoring | 자동 (Stack 포함) |
| node-exporter | monitoring | 자동 (Stack 포함) |
| mock-nvidia-smi-exporter | ai-pass3 | ServiceMonitor |
| helm-exporter | monitoring | ServiceMonitor (수동 적용) |

`serviceMonitorSelectorNilUsesHelmValues: false` 설정으로 **모든 NS의 ServiceMonitor 자동 수집**.

---

## 3. 신규/수정 코드 상세

**베이스 경로**: `anycloud/src/main/java/com/aipaas/anycloud/`

### 3.1 Controller (3개 신규)

| 파일 | API Prefix | 설명 |
|------|-----------|------|
| `controller/MonitController.java` | `/monit/` | 모니터링 (summary, releases, alerts, gpu-status, node resource) |
| `controller/CostController.java` | `/cost/` | 비용 관리 + GPU 예약 CRUD |
| `controller/AuditController.java` | `/audit/` | 감사 로그 (K8s events) |

### 3.2 Service (6개 신규)

| 파일 | 역할 |
|------|------|
| `service/MonitService.java` | 모니터링 인터페이스 |
| `service/Impl/MonitServiceImpl.java` | Prometheus PromQL 실행 + 파싱 |
| `service/CostService.java` | 비용 인터페이스 |
| `service/Impl/CostServiceImpl.java` | GPU 비용 계산 + 예약 CRUD |
| `service/AuditService.java` | 감사 로그 인터페이스 |
| `service/Impl/AuditServiceImpl.java` | K8s Events API 조회 (fabric8) |

### 3.3 DTO (9개 신규)

| 파일 | 용도 |
|------|------|
| `model/dto/MonitoringSummaryDto.java` | 대시보드 요약 |
| `model/dto/ReleaseStatusDto.java` | 릴리즈 상태 |
| `model/dto/AlertDto.java` | 활성 알림 |
| `model/dto/CostSummaryDto.java` | 비용 요약 |
| `model/dto/CostReportDto.java` | 7일 리포트 |
| `model/dto/CostEstimateDto.java` | 비용 추정 |
| `model/dto/AuditEventDto.java` | K8s 이벤트 |
| `model/dto/request/GpuReservationRequestDto.java` | GPU 예약 요청 |
| `model/dto/response/GpuReservationResponseDto.java` | GPU 예약 응답 |

### 3.4 Entity / Repository (2개 신규)

| 파일 | 설명 |
|------|------|
| `model/entity/GpuReservationEntity.java` | JPA Entity - gpu_reservation 테이블 |
| `repository/GpuReservationRepository.java` | JPA Repository |

### 3.5 유틸리티 (2개 신규)

| 파일 | 설명 |
|------|------|
| `service/util/PrometheusMetricProperties.java` | application.yaml PromQL → Java 매핑 |
| `service/util/PrometheusQueryService.java` | PromQL 템플릿 resolve + 변수 치환 |

### 3.6 수정된 기존 파일

| 파일 | 변경 |
|------|------|
| `service/Impl/ChartServiceImpl.java` | 비동기→동기 배포, not-found 삭제 성공 처리 |
| `error/handler/GlobalExceptionHandler.java` | HelmDeploymentException 400→500 변경 |
| `service/util/FormatConverter.java` | 타임스탬프 포맷 변환 추가 |

---

## 4. API 엔드포인트 전체

### 모니터링

| Method | Path | 설명 |
|--------|------|------|
| GET | `/monit/monitoring/summary?cluster=` | 대시보드 4개 요약 카드 |
| GET | `/monit/monitoring/releases?cluster=` | 헬름 릴리즈 + GPU 매핑 |
| GET | `/monit/monitoring/alerts?cluster=` | 활성 알림 목록 |
| GET | `/monit/monitoring/gpu-status?cluster=` | GPU 카드별 실시간 상태 |
| GET | `/monit/nodeStatus/{cluster}` | 노드 리소스 상태 |
| GET | `/monit/resourceMonit/{cluster}/{type}/{key}` | 리소스 게이지 (CPU/Mem/Disk/Pod) |

### 비용 관리 + GPU 예약

| Method | Path | 설명 |
|--------|------|------|
| GET | `/cost/summary?cluster=&ns=` | NS별 GPU 비용 요약 |
| GET | `/cost/idle-warnings?cluster=&ns=` | 유휴 GPU 경고 |
| GET | `/cost/report?cluster=&ns=` | 7일 사용 리포트 |
| GET | `/cost/estimate?cluster=&gpuCount=&hours=` | 비용 추정 |
| GET | `/cost/reservations?cluster=` | GPU 예약 목록 |
| POST | `/cost/reservations` | GPU 예약 등록 |
| PUT | `/cost/reservations/{releaseName}/extend?cluster=&minutes=` | 예약 연장 |
| DELETE | `/cost/reservations/{releaseName}?cluster=` | 예약 삭제 |

### 감사 로그

| Method | Path | 설명 |
|--------|------|------|
| GET | `/audit/events?cluster=&ns=` | K8s 이벤트 |

### 차트 (기존 확장)

| Method | Path | 설명 |
|--------|------|------|
| GET | `/charts/{repoName}/{chartName}/values` | values.yaml 조회 |
| POST | `/charts/deploy` | 차트 배포 (helm install) |
| DELETE | `/charts/{cluster}/{ns}/{releaseName}` | 릴리즈 삭제 |

---

## 5. DB 스키마

### 기존 테이블 (변경 없음)

| 테이블 | 용도 | 주요 데이터 |
|--------|------|-----------|
| `cluster` | K8s 클러스터 정보 | API URL, 인증서, `monit_server_url` (Prometheus URL) |
| `helm_repo` | Helm 저장소 정보 | bitnami, chart-museum-external |

### 신규 테이블

```sql
CREATE TABLE gpu_reservation (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 접속 정보

| 항목 | 값 |
|------|-----|
| Host | localhost:13306 |
| DB | aipaas |
| User | anycloud / anycloud (또는 root / yourP@ssW0rds) |

---

## 6. PromQL 쿼리 구조

`application.yaml`에 PromQL 쿼리가 YAML로 정의되어 있으며, `PrometheusQueryService.resolve(group, key, filters)`로 호출됩니다.

```yaml
prometheus:
  metrics:
    node:       # 노드 상태
    cpu:        # CPU total/usage/request/limit/load5
    memory:     # Memory total/usage/request/limit
    filesystem: # Disk total/usage
    gpu:        # GPU total/usage/util/memory/temperature/power
    pod:        # Pod total/used/usage_namespace
    monitoring: # 대시보드 요약 (helm_releases, gpu_count, gpu_avg_util, active_alerts)
    cost:       # 비용 (gpu_usage_by_ns, gpu_count_by_ns, gpu_util_range)
```

### 주요 PromQL

```promql
# GPU 평균 활용률
avg(nvidia_smi_utilization_gpu_ratio)

# GPU 현황 (모델별)
nvidia_smi_utilization_gpu_ratio
nvidia_smi_temperature_gpu
nvidia_smi_power_draw_watts
nvidia_smi_memory_used_bytes / nvidia_smi_memory_total_bytes

# 헬름 릴리즈 목록
helm_chart_info

# 활성 알림
ALERTS{alertstate="firing"}

# GPU 단가 (ConfigMap)
kubectl get configmap gpu-pricing -n monitoring -o json
```

---

## 7. 주요 기술 결정 사항

### helm-exporter + honorLabels

- **문제**: helm-exporter가 monitoring NS에서 실행 → Prometheus가 `namespace=monitoring` 덮어씀
- **해결**: ServiceMonitor에 `honorLabels: true` + 백엔드에 `exported_namespace` fallback

### Prometheus 동기화 지연

- **문제**: helm-exporter 30초 scrape → 삭제 직후 stale 데이터
- **해결**: 프론트엔드에서 60초간 `hiddenNames`로 숨김

### 비동기→동기 배포

- `ChartServiceImpl`에서 helm install 결과를 동기적으로 반환

### 비용 추정 API 소수 시간

- `/cost/estimate?hours=0.033` → int 파라미터라 400 에러
- 프론트에서 1시간 기준 단가 조회 후 로컬 계산으로 우회

---

## 8. 운영 K8s 이관 시 변경사항

| 항목 | Docker Desktop (현재) | 운영 K8s |
|------|---------------------|----------|
| DNS | dnsmasq `*.aipaas → 127.0.0.1` | 와일드카드 DNS `*.apps.innogrid.com → Ingress IP` |
| /etc/hosts | prometheus.aipaas 수동 | 불필요 (DNS 서버가 처리) |
| GPU | mock-nvidia-smi-exporter | 실제 NVIDIA GPU + nvidia-device-plugin + DCGM Exporter |
| GPU Quota | gpu-quota.yaml (4개) | 실제 GPU 수에 맞게 조정 |
| ChartMuseum | Docker (localhost:8880) | K8s 내부 서비스 |
| DB cluster 테이블 | API Server URL 동적 추출 | 실제 K8s API 서버 주소 고정 |
| Ingress 도메인 | `.aipaas` | `.apps.innogrid.com` |
| Prometheus values | ai-pass3-prometheus-values.yaml | 운영 환경용 values |

### GPU 이관

```bash
# 1. NVIDIA Device Plugin
kubectl apply -f https://raw.githubusercontent.com/NVIDIA/k8s-device-plugin/v0.14.0/nvidia-device-plugin.yml

# 2. DCGM Exporter (실제 GPU 메트릭)
helm install dcgm-exporter nvidia/dcgm-exporter \
  --namespace monitoring \
  -f docs/infrastructure/dcgm-exporter-values.yaml

# 3. mock-nvidia-smi-exporter 삭제
kubectl delete -f docs/infrastructure/mock-nvidia-smi-exporter/deployment.yaml

# 4. PromQL 수정 불필요 — nvidia_smi_* 메트릭명을 DCGM과 동일하게 맞춤
```

### 도메인 이관

차트 Ingress 템플릿 기본 도메인 변경:
```yaml
# docs/infrastructure/sample-charts/create-charts.sh 내
# 변경 전: host: "{{ .Release.Name }}.aipaas"
# 변경 후: host: "{{ .Release.Name }}.apps.innogrid.com"
```

---

## 9. 인프라 파일 목록 (docs/infrastructure/)

| 파일 | 용도 |
|------|------|
| `ai-pass3-prometheus-values.yaml` | Prometheus Stack Helm values (Docker Desktop용, GPU alerts + recording rules 포함) |
| `prometheus-values.yaml` | Prometheus Stack Helm values (경량 운영용) |
| `ingress.yaml` | prometheus.aipaas, alertmanager.aipaas Ingress |
| `gpu-quota.yaml` | GPU ResourceQuota (ai-pass3 NS, 4개) |
| `gpu-pricing-configmap.yaml` | GPU 모델별 시간당 단가 ConfigMap |
| `gpu-alert-rules.yaml` | GPU 유휴 경고 PrometheusRule (운영용) |
| `helm-exporter-values.yaml` | helm-exporter Helm values |
| `helm-exporter-servicemonitor.yaml` | honorLabels ServiceMonitor (수동 적용) |
| `nvidia-smi-exporter.yaml` | 실제 GPU용 nvidia-smi-exporter DaemonSet (운영용) |
| `dcgm-exporter-values.yaml` | DCGM Exporter Helm values (운영용) |
| `dcgm-exporter-consumer-gpu.yaml` | DCGM Exporter Consumer GPU 설정 (RTX 3060 등) |
| `db-init.sql` | MariaDB 초기 데이터 (cluster, helm_repo) |
| `mock-nvidia-smi-exporter/` | Mock GPU 메트릭 서버 (Dockerfile + exporter.py + K8s manifests) |
| `sample-charts/create-charts.sh` | 6개 MLOps 차트 생성 + ChartMuseum 등록 |

---

## 10. 알려진 이슈

- **Helm CLI 의존**: `HelmCommandExecutor`가 서버의 `helm` CLI를 직접 호출. PATH에 helm 필요
- **Prometheus DNS**: Java(Netty)는 macOS `/etc/resolver` 무시 → `/etc/hosts` 등록 필수
- **SnakeYAML 파싱**: bitnami 대형 차트 128MB 제한 설정 적용 완료
- **bitnami 이미지 유료화**: 2025-08부터 Docker Hub bitnami 이미지 무료 중단. ChartMuseum에 차트는 있지만 이미지 pull 불가
- **GPU limit + Docker Desktop**: 실제 GPU 없으므로 `nvidia.com/gpu` 리소스 요청 시 Pending

---

## 11. 시연 플로우

1. 로그인 (admin/1234) → 모니터링 대시보드
2. GPU 현황 (4개 GPU, 활용률 ~50%)
3. 카탈로그 → gpu-jupyter 배포
4. 보안 검사 → 비용 추정 (2분, 40원) → 배포
5. http://gpu-jupyter-demo.aipaas 접속
6. 2분 대기 → 비용 최적화에서 초과 경고 확인
7. 헬름 릴리즈 → 삭제
8. 비용 최적화 → 일/월 비용
9. 감사 로그 → K8s 이벤트

---

## 12. 접속 정보 요약

| 항목 | URL |
|------|-----|
| 백엔드 API | http://localhost:8888/api/v1 |
| Swagger UI | http://localhost:8888/api/v1/docs |
| Prometheus | http://prometheus.aipaas |
| AlertManager | http://alertmanager.aipaas |
| ChartMuseum | http://localhost:8880 |
| MariaDB | localhost:13306 (root/yourP@ssW0rds) |
| 포털 | http://localhost:5173 (admin/1234) |
| 배포 서비스 | http://<릴리즈명>.aipaas |
