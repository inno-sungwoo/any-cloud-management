# any-cloud-management — 3차년도 백엔드 변경 사항

> **브랜치**: `feat/3rd-year-monitoring-apis`
> **기준 브랜치**: `main`
> **변경 규모**: 28 files changed, 1,532 insertions, 23 deletions
> **커밋 수**: 19개

---

## 1. 개요

3차년도 과제의 백엔드를 구현하였습니다. Prometheus 연동 모니터링 API, GPU 비용 관리 API, 감사 로그 API, Helm 보안 검증(dry-run), GPU 예약제 API 등을 개발하였습니다.

## 2. 실행 방법

### 사전 요구사항

- Java 17+ (OpenJDK 17)
- Gradle 8.10
- MariaDB (Docker: anycloud-db, port 13306)
- Kubernetes 클러스터 (kubeconfig 설정)
- Prometheus (http://prometheus.aipaas 또는 설정 변경)

### 실행

```bash
cd /Users/usermackbookpro/innogrid-prj/any-cloud-management
git checkout feat/3rd-year-monitoring-apis

export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :anycloud:bootRun
# → http://localhost:8888
```

### 설정

`anycloud/src/main/resources/application.properties`:
```properties
spring.datasource.url=jdbc:mariadb://localhost:13306/aipaas
spring.datasource.username=anycloud
spring.datasource.password=anycloud
server.port=8888
server.servlet.context-path=/api/v1
```

> Prometheus URL은 DB의 `cluster` 테이블 `monit_server_url` 컬럼에서 읽어옵니다.
> 현재: `http://prometheus.aipaas` (macOS에서는 /etc/hosts에 등록이 필요합니다)

---

## 3. 신규 API 목록

### 3-1. 모니터링 API (`MonitController`)

| Method | URL | 설명 |
|--------|-----|------|
| GET | `/monit/monitoring/summary?cluster=` | 클러스터 요약 (릴리즈 수, GPU 수, 평균 활용률, 알림 수) |
| GET | `/monit/monitoring/releases?cluster=` | 헬름 릴리즈 목록 (릴리즈/NS/상태/차트/버전/GPU) |
| GET | `/monit/monitoring/alerts?cluster=` | 활성 알림 목록 (AlertManager) |
| GET | `/monit/monitoring/gpu-status?cluster=` | GPU 현황 (모델, 활용률, 온도, 전력, VRAM, 팬, 드라이버) |
| GET | `/monit/nodeStatus/{cluster}` | 노드 리소스 상태 |
| GET | `/monit/resourceMonit/{cluster}/{type}/{key}` | 리소스 모니터링 (게이지 차트용) |

### 3-2. 비용 관리 API (`CostController`)

| Method | URL | 설명 |
|--------|-----|------|
| GET | `/cost/summary?cluster=` | 비용 요약 (네임스페이스별 GPU 비용) |
| GET | `/cost/idle-warnings?cluster=` | 유휴 GPU 경고 목록 (AlertManager 기반) |
| GET | `/cost/report?cluster=` | 최근 7일 네임스페이스별 사용량/비용 리포트 |
| GET | `/cost/estimate?gpuCount=&hours=` | 비용 추정 (GPU 단가 × 수량 × 시간) |
| GET | `/cost/reservations?cluster=` | GPU 예약 목록 조회 |
| POST | `/cost/reservations` | GPU 예약 생성 (배포 시 예상 시간 저장) |
| PUT | `/cost/reservations/{releaseName}/extend?cluster=&minutes=` | GPU 예약 시간 연장 |
| DELETE | `/cost/reservations/{releaseName}?cluster=` | GPU 예약 삭제 |

### 3-3. 감사 로그 API (`AuditController`)

| Method | URL | 설명 |
|--------|-----|------|
| GET | `/audit/events?clusterName=&namespace=` | K8s 이벤트 조회 (유형, 사유, 객체, 메시지, 시각) |

### 3-4. 인증 API (`AuthController`)

| Method | URL | 설명 |
|--------|-----|------|
| POST | `/auth/login` | 개발용 Mock 로그인 |

### 3-5. 차트 API (`ChartController`)

| Method | URL | 설명 |
|--------|-----|------|
| GET | `/charts/{repoName}` | 레포의 차트 목록 (index.yaml 기반) |
| GET | `/charts/{repoName}/{chartName}/detail?version=` | 차트 상세 (version 미지정 시 최신) |
| GET | `/charts/{repoName}/{chartName}/values?version=` | 차트 values.yaml 조회 (helm CLI) |
| GET | `/charts/{repoName}/{chartName}/readme?version=` | 차트 README.md 조회 |
| POST | `/charts/{repoName}/{chartName}/deploy` | 차트 배포 (multipart, helm install + dry-run 보안 검증) |
| GET | `/charts/{repoName}/{chartName}/status?releaseName=&clusterId=&namespace=` | 릴리즈 배포 상태 |
| GET | `/charts/releases?clusterId=&namespace=` | 클러스터 릴리즈 목록 |
| GET | `/charts/releases/{releaseName}/resources?clusterId=&namespace=` | 릴리즈 K8s 리소스 |
| DELETE | `/charts/releases/{releaseName}?clusterId=&namespace=` | 릴리즈 삭제 (helm uninstall) |

---

## 4. 신규 파일

### Controller

| 파일 | 설명 |
|------|------|
| `controller/CostController.java` | 비용 관리 API (6개 엔드포인트) |
| `controller/AuditController.java` | 감사 로그 API |
| `controller/AuthController.java` | 개발용 Mock 인증 |

### Service

| 파일 | 설명 |
|------|------|
| `service/CostService.java` | 비용 관리 인터페이스 |
| `service/Impl/CostServiceImpl.java` | 비용 계산 구현 (Prometheus PromQL + ConfigMap 단가) |
| `service/AuditService.java` | 감사 로그 인터페이스 |
| `service/Impl/AuditServiceImpl.java` | K8s Events API 연동 |

### DTO

| 파일 | 설명 |
|------|------|
| `dto/response/MonitoringSummaryDto.java` | 모니터링 요약 응답 |
| `dto/response/ReleaseStatusDto.java` | 릴리즈 상태 응답 |
| `dto/response/AlertDto.java` | 알림 응답 |
| `dto/response/CostSummaryDto.java` | 비용 요약 응답 |
| `dto/response/CostReportDto.java` | 비용 보고서 응답 |
| `dto/response/CostEstimateDto.java` | 비용 추정 응답 |
| `dto/response/GpuReservationResponseDto.java` | GPU 예약 응답 |
| `dto/request/GpuReservationRequestDto.java` | GPU 예약 요청 |
| `dto/response/AuditEventDto.java` | 감사 이벤트 응답 |

### Entity / Repository

| 파일 | 설명 |
|------|------|
| `entity/GpuReservationEntity.java` | GPU 예약 JPA 엔티티 |
| `repository/GpuReservationRepository.java` | GPU 예약 Repository |

### Utility

| 파일 | 설명 |
|------|------|
| `service/util/HelmCommandExecutor.java` | Helm CLI 래퍼 (install, uninstall, list, get values) |
| `service/util/ChartParser.java` | 차트 메타데이터 파싱 |
| `service/util/ChartValidator.java` | 차트 dry-run 보안 검증 |
| `service/util/HelmReleaseScanner.java` | 릴리즈 스캐너 |
| `service/util/DeploymentOrchestrator.java` | 배포 오케스트레이션 |

---

## 5. 주요 수정 파일

| 파일 | 변경 내용 |
|------|---------|
| `MonitServiceImpl.java` | GPU 현황 API 추가, Prometheus PromQL 쿼리 대폭 확장 (nvidia_smi 메트릭) |
| `ChartServiceImpl.java` | 비동기 배포 → 동기 배포 변경, dry-run 보안 검증 추가, 삭제 API 추가 |
| `MonitService.java` | 인터페이스에 summary/releases/alerts/gpu-status 메서드 추가 |
| `application.yaml` | JPA ddl-auto, Helm 경로 설정 추가 |

---

## 6. Prometheus PromQL 쿼리 (주요)

GPU 메트릭은 **DCGM Exporter** 기반으로 통합되었습니다 (이전 `nvidia_smi_*` → `DCGM_FI_DEV_*`, 커밋 `83c3c03` 참조). 일부 레거시 경로에는 `nvidia_smi_*`가 남아있을 수 있습니다.

```promql
# GPU 평균 활용률 / 개수 / 알림 수 (MonitController summary)
avg(DCGM_FI_DEV_GPU_UTIL)
count(DCGM_FI_DEV_GPU_UTIL)
count(ALERTS{alertstate="firing"})

# GPU 현황 (DCGM)
DCGM_FI_DEV_GPU_UTIL          # 활용률(%)
DCGM_FI_DEV_GPU_TEMP          # 온도
DCGM_FI_DEV_POWER_USAGE       # 전력
DCGM_FI_DEV_FB_USED           # VRAM 사용
DCGM_FI_DEV_FB_USED + DCGM_FI_DEV_FB_FREE  # VRAM 총량

# 헬름 릴리즈 목록 (helm-exporter)
helm_chart_info{description!~".*failed.*"}

# 비용 — 네임스페이스별 GPU 할당
max by (namespace) (sum by (namespace, instance)
  (kube_pod_container_resource_requests{resource="nvidia_com_gpu"}))

# GPU 단가 (ConfigMap)
kubectl get configmap gpu-pricing -n ai-pass -o json
```

> 전체 PromQL 쿼리는 `anycloud/src/main/resources/application.yaml`의 `prometheus.metrics.*` 트리에 정의되어 있습니다.

---

## 7. DB 스키마 (GPU 예약)

```sql
CREATE TABLE gpu_reservation (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  release_name VARCHAR(100) NOT NULL,
  namespace VARCHAR(100) NOT NULL DEFAULT 'default',
  cluster_id VARCHAR(45) NOT NULL,
  gpu_count INT NOT NULL DEFAULT 1,
  estimated_minutes INT NOT NULL,        -- 단위: 분 (시간 아님)
  unit_price_krw INT NOT NULL DEFAULT 1200,
  estimated_cost_krw INT NOT NULL DEFAULT 0,
  deployed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_release_cluster (release_name, cluster_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

> JPA 엔티티: `model/entity/GpuReservationEntity.java`. 만료 시각 컬럼(`expired_at`)은 존재하지 않으며, `deployed_at + estimated_minutes`로 산정합니다.

> 기존 `cluster`, `helm_repo` 테이블은 변경 사항이 없습니다.

---

## 8. 주요 의존성

| 라이브러리 | 버전 | 용도 |
|-----------|------|------|
| Spring Boot | 3.2.5 | 웹 프레임워크 |
| Fabric8 Kubernetes Client | 7.2.0 | K8s API 호출 |
| MariaDB Connector | 3.1.2 | DB 연결 |
| SnakeYAML | (Spring Boot transitive) | values.yaml 파싱 — 코드에서 128MB 제한으로 확장 |

---

## 9. 알려진 이슈

- **Helm CLI 의존**: `HelmCommandExecutor`가 서버의 `helm` CLI를 직접 호출합니다. PATH에 helm 바이너리가 필요합니다.
- **Prometheus DNS**: Java(Netty)는 macOS `/etc/resolver`를 무시합니다. `/etc/hosts`에 `prometheus.aipaas`를 등록해 주세요.
- **SnakeYAML 파싱**: bitnami 대형 차트 values.yaml 파싱 시 128MB 제한 설정이 필요합니다 (적용 완료).

---

## 10. 접속 정보

| 항목 | 값 |
|------|-----|
| 백엔드 URL | http://localhost:8888/api/v1 |
| Swagger UI | http://localhost:8888/api/v1/docs |
| DB | MariaDB localhost:13306 / aipaas / anycloud:anycloud |

---

## 11. 다른 환경으로 이전 시 설정 사항

새 머신/계정에서 프로젝트를 처음 띄울 때 손봐야 하는 항목입니다. (가장 빠른 방법은 `./scripts/setup.sh` 한 번에 실행하는 것이지만, 아래는 그 스크립트가 내부적으로 무엇을 건드리는지 정리한 체크리스트입니다.)

### 11-1. 사전 설치 (호스트 머신)

| 항목 | 비고 |
|------|------|
| Java 17+ | `JAVA_HOME` export 필요 (macOS: `/opt/homebrew/opt/openjdk@17`) |
| Docker + docker compose | MariaDB / ChartMuseum 컨테이너 구동 |
| kubectl | 멀티클러스터의 모든 컨텍스트가 등록된 kubeconfig 필요 |
| helm 3.x | 백엔드의 `HelmCommandExecutor`가 PATH의 `helm` 바이너리를 직접 호출 |
| pnpm | 프론트엔드(`../ai-paas-web`) 실행용 |
| python3 | `setup.sh`가 JSON 파싱에 사용 |
| kubeconfig 정적 자격증명 | 각 user에 token / client-certificate-data / client-key-data 등 정적 값이 채워져 있어야 함 (exec 플러그인 인증은 지원하지 않음) |

### 11-2. 경로 / 디렉토리 구조

- `setup.sh`는 프론트엔드를 `<repo>/../ai-paas-web` 에서 찾습니다. 다른 위치라면 `FRONTEND_DIR` 변수를 수정하세요.
- README 2장의 `cd /Users/usermackbookpro/innogrid-prj/any-cloud-management` 경로는 작성자의 macOS 기준이므로 본인 환경의 절대 경로로 교체합니다.
- 호스트 볼륨 마운트 경로:
  - `./db_data` — MariaDB 데이터 (기존 DB를 그대로 가져오려면 이 디렉토리를 통째로 복사)
  - `./chartmuseum_data` — ChartMuseum 차트 저장소

### 11-3. `docker-compose.yml`

기본값은 대부분 그대로 사용 가능하지만 환경에 따라 다음을 점검합니다.

| 항목 | 기본값 | 변경 필요 시점 |
|------|--------|---------------|
| `anycloud-db` 포트 | `13306:3306` | 호스트의 13306이 이미 사용 중일 때 |
| `chartmuseum` 포트 | `8880:8080` | 8880이 사용 중일 때 |
| `anycloud-backend` 포트 | `8888:8888` | 8888이 사용 중일 때 (`application.properties`의 `server.port`도 함께 변경) |
| `MYSQL_ROOT_PASSWORD` | `yourP@ssW0rds` | 운영 환경에선 반드시 변경 |
| `MYSQL_USER` / `MYSQL_PASSWORD` | `anycloud` / `anycloud` | 변경 시 `application.properties`도 동기화 |
| `TZ` | `Asia/Seoul` | 다른 타임존이 필요할 때 |
| `/etc/localtime` 마운트 | Linux 전용 | macOS에서는 제거 또는 무시 (compose가 경고만 출력) |

### 11-4. `application.properties`

| 키 | 기본값 | 환경별 설정 포인트 |
|----|--------|-------------------|
| `spring.datasource.url` | `jdbc:mariadb://localhost:13306/aipaas` | DB 호스트/포트가 다르거나 컨테이너 내부에서 실행할 땐 `anycloud-db:3306` 으로 변경 |
| `spring.datasource.username/password` | `anycloud/anycloud` | docker-compose 환경 변수와 동일하게 유지 |
| `server.port` | `8888` | 변경 시 `setup.sh`의 헬스체크 URL과 docker-compose 포트도 함께 수정 |
| `server.servlet.context-path` | `/api/v1` | 프론트엔드 baseURL과 동기화 |
| `kubernetes.kubeconfig.path` | `${KUBECONFIG:~/.kube/config}` | **필수**. 비어있으면 `KubernetesClientConfig`가 기동/호출 시 `IllegalStateException`을 던집니다. 다른 kubeconfig를 쓰려면 `KUBECONFIG` 환경 변수 또는 이 값을 직접 지정 |
| `com.innogrid.rndplan.medge.monitoringUrl` | `https://localhost:9000` | Thanos 사용 시에만 의미 있음 |

> ⚠️ `application.properties`는 git에 포함된 파일입니다. 환경별 비밀값은 `application.properties_sample`을 참고하여 환경 변수 또는 `application-local.properties`로 분리하는 것을 권장합니다.

### 11-5. Kubernetes / 모니터링 설정

`scripts/setup.sh`가 자동으로 처리하지만, 수동 설치가 필요한 경우 아래 항목을 점검합니다.

1. **K8s 컨텍스트(멀티클러스터)** — 백엔드는 `cluster.id == kubeconfig context.name` 매핑으로 라우팅합니다. `setup.sh`는 `kubectl config get-contexts -o name`을 순회하면서 모든 컨텍스트를 `cluster` 테이블에 upsert하므로, 사용할 모든 클러스터 컨텍스트가 kubeconfig에 등록되어 있어야 합니다.
2. **kubeconfig 자격증명** — 각 컨텍스트의 user는 **정적 자격증명**(`token:`, `client-certificate-data:` + `client-key-data:` 등)이어야 합니다. `exec:` 플러그인 기반 인증은 지원하지 않습니다.
3. **네임스페이스** — 기본 `ai-pass`. 다른 NS를 쓰려면 `NAMESPACE` 변수 변경 + Ingress host도 함께 수정.
4. **Prometheus 설치** — `helm install ai-pass-prom prometheus-community/kube-prometheus-stack -f docs/infrastructure/ai-pass-prometheus-values.yaml -n ai-pass`
5. **Ingress 호스트** — `prometheus-aipass.innogrid.com`, `alertmanager-aipass.innogrid.com`. 외부 도메인이 다르면 `setup.sh`의 Ingress YAML 수정 + DNS/`/etc/hosts` 등록.
6. **GPU 가격 ConfigMap** — `gpu-pricing` ConfigMap (NS: `ai-pass`)의 단가가 비용 API의 입력값입니다. 환경에 맞게 조정.
7. **GPU ResourceQuota** — `requests.nvidia.com/gpu: "4"` 기본값. 클러스터 GPU 수에 맞게 조정.
8. **Prometheus 접근** — DB의 `cluster.monit_server_url` 컬럼이 백엔드가 호출하는 Prometheus URL입니다.
   - 로컬 개발: `setup.sh`가 `kubectl port-forward`로 `localhost:9090` 노출 후 DB에 그대로 저장.
   - 운영/원격: Ingress 도메인(예: `http://prometheus-aipass.innogrid.com`)으로 직접 변경.
   - macOS에서 Ingress 도메인을 쓸 경우 Java(Netty)가 `/etc/resolver`를 무시하므로 `/etc/hosts`에 등록 필수.

### 11-6. DB 초기 데이터

`docs/infrastructure/db-init.sql`은 최초 컨테이너 기동 시(빈 DB일 때만) 자동 실행됩니다. 이미 데이터가 있는 볼륨에서는 실행되지 않으므로, `setup.sh`가 별도로 `INSERT ... ON DUPLICATE KEY UPDATE`로 다음을 갱신합니다.

| 테이블 | 갱신 항목 | 환경 의존 값 |
|--------|----------|--------------|
| `cluster` (kubeconfig context별 1행) | `api_server_url`, `api_server_ip`, `monit_server_url`, `version`, `status` | K8s API 서버, Prometheus URL |
| `helm_repo` | `bitnami`, `chart-museum-external` | ChartMuseum 호스트가 다르면 `url` 수정 |

> `cluster.id`는 kubeconfig의 context 이름과 동일해야 합니다. 인증 자격증명은 DB가 아닌 kubeconfig 파일에서 직접 읽기 때문에 `client_token`/`client_ca`/`client_key`/`server_ca` 컬럼은 더 이상 런타임에 사용되지 않습니다(엔티티/CRUD에는 남아있음).

### 11-7. 빠른 점검 체크리스트

다른 환경 진입 후 아래 순서대로 확인하세요.

1. `kubectl config get-contexts -o name` → DB에 등록될 모든 컨텍스트 확인
2. `docker compose up -d anycloud-db chartmuseum` → DB/ChartMuseum 기동
3. `docker exec anycloud-db mariadb -uanycloud -panycloud aipaas -e "SELECT id, api_server_url, monit_server_url FROM cluster;"` → context별 클러스터 레코드 확인
4. `curl http://localhost:9090/api/v1/query?query=up` → Prometheus 도달 확인 (port-forward 또는 Ingress)
5. `helm version` + `helm list -A` → CLI 동작 확인
6. `./gradlew :anycloud:bootRun` → 백엔드 기동 후 `curl http://localhost:8888/api/v1/system/clusters`
7. Swagger (`/api/v1/docs`) 에서 모니터링/비용 API 응답 확인
