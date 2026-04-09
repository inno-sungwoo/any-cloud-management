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

- **Java 21+** (OpenJDK 21 — Dockerfile은 `eclipse-temurin:21`을 사용. Spring Boot 3.2.5는 Java 17부터 가능하지만 본 프로젝트는 21로 통일)
- Gradle 8.10 (Gradle wrapper 동봉)
- Docker + Compose (MariaDB / ChartMuseum / 백엔드 컨테이너 구동)
- Kubernetes 클러스터 + 정적 자격증명 kubeconfig
- helm 3.x — 로컬 직접 실행 시에만 호스트에 필요 (Docker 컨테이너에는 Dockerfile이 helm v3.19.0을 번들로 설치)

### 권장 실행: `./scripts/setup.sh` 한 번에 기동

```bash
cd <repo>/any-cloud-management
chmod +x scripts/setup.sh && ./scripts/setup.sh
# → Docker Compose 빌드/기동 + Prometheus 설치 + ChartMuseum 시드 + 헬스체크
```

자세한 단계와 환경 변수 설정은 [`QUICKSTART.md`](./QUICKSTART.md) 참고.

### 로컬 직접 실행 (개발/디버깅용)

```bash
cd <repo>/any-cloud-management

# Docker compose 로 의존 서비스만 먼저 띄우기
docker compose up -d anycloud-db chartmuseum

# 백엔드를 로컬 JVM 으로 실행 (local profile)
./gradlew :anycloud:bootRun --args='--spring.profiles.active=local'
# → http://localhost:8888/api/v1
```

### 설정 (Spring Profile 기반)

설정 파일은 Spring profile 패턴으로 분리되어 있습니다:

```
anycloud/src/main/resources/
├── application.properties           ← 공통 base (모든 키 + ${ENV:default} placeholder)
├── application-local.properties     ← 로컬 직접 실행 override
└── application-docker.properties    ← Docker 컨테이너 override
```

| 실행 모드 | 활성화 |
|---|---|
| 로컬 직접 실행 | `./gradlew :anycloud:bootRun --args='--spring.profiles.active=local'` |
| Docker 컨테이너 | Dockerfile의 `ENV SPRING_PROFILES_ACTIVE=docker` (자동) |
| profile 미지정 | base만 적용. `${VAR:default}` 기본값으로 동작 (로컬 친화적) |

자세한 환경 변수 가이드는 [`ENVIRONMENT-SETUP.md`](./ENVIRONMENT-SETUP.md)를 참고하세요.

> Prometheus URL은 DB의 `cluster` 테이블 `monit_server_url` 컬럼에서 읽어옵니다.
> 신규 cluster 등록 시 초기값은 `MONIT_DEFAULT_URL` 환경 변수로 주입.
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
| `MonitServiceImpl.java` | GPU 현황 API 추가, Prometheus PromQL 쿼리 대폭 확장 (DCGM 메트릭) |
| `ChartServiceImpl.java` | 비동기 배포 → 동기 배포 변경, dry-run 보안 검증 추가, 삭제 API 추가 |
| `MonitService.java` | 인터페이스에 summary/releases/alerts/gpu-status 메서드 추가 |
| `application.yaml` | Prometheus PromQL 쿼리 템플릿 트리(`prometheus.metrics.*`) 정의. JPA/Helm 경로 같은 Spring 설정은 `application.properties` 에 있음 |

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

- **Helm CLI 의존**: `HelmCommandExecutor`가 서버의 `helm` CLI를 자식 프로세스로 직접 호출합니다.
  - **Docker 컨테이너**: `Dockerfile`이 helm v3.19.0을 `/usr/local/bin/helm`에 번들로 설치하므로 별도 작업 불필요.
  - **로컬 직접 실행**: 호스트의 PATH에 helm 3.x 가 설치되어 있어야 합니다 (`brew install helm` / 공식 바이너리).
- **Prometheus DNS**: Java(Netty)는 macOS `/etc/resolver`를 무시합니다. `/etc/hosts`에 `prometheus.aipaas` 등 사내 도메인을 직접 등록해 주세요.
- **SnakeYAML 파싱**: bitnami 대형 차트 values.yaml 파싱 시 128MB 제한 설정이 필요합니다 (적용 완료).
- **Bitnami OCI TLS**: bitnami 차트가 `registry-1.docker.io`로 이전되면서 사내망에서 `auth.docker.io` TLS handshake가 실패할 수 있습니다. helm `--insecure-skip-tls-verify` 플래그가 OCI 레지스트리 TLS도 깨뜨리므로 코드에서 제거되었습니다. 필요 시 ChartMuseum에 미러링하여 우회 (11-7 참조).

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
| **Java 21+** | 로컬 직접 실행 시에만 필요 (`./gradlew bootRun`). Docker 컨테이너는 `eclipse-temurin:21` 베이스로 자체 포함. `JAVA_HOME` export 권장 |
| Docker + docker compose | MariaDB / ChartMuseum / 백엔드 컨테이너 구동 (setup.sh가 docker compose 사용) |
| kubectl | 멀티클러스터의 모든 컨텍스트가 등록된 kubeconfig 필요 |
| helm 3.x | **로컬 직접 실행** 시에만 호스트에 필요. Docker 컨테이너는 helm v3.19.0이 번들 설치됨 |
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
| `MYSQL_USER` / `MYSQL_PASSWORD` | `anycloud` / `anycloud` | `docker-compose.yml`이 `${DATABASE_USERID}` / `${DATABASE_USERPASS}`로 참조하므로 `.env`에서 한 번에 변경 가능 (자동 동기화) |
| `TZ` | `Asia/Seoul` | 다른 타임존이 필요할 때 |
| `/etc/localtime` 마운트 | Linux 전용 | macOS에서는 제거 또는 무시 (compose가 경고만 출력) |

### 11-4. Spring 설정 파일 (Profile 구조)

설정은 Spring profile 패턴으로 분리되어 있으며, 모든 키는 `${ENV_VAR:default}` 형태로 환경 변수 override를 지원합니다.

| 파일 | 역할 |
|---|---|
| `application.properties` | 공통 base. 모든 키 정의 + 로컬 친화적 기본값 |
| `application-local.properties` | local profile override (대부분 비어있음 — base가 이미 로컬 친화적) |
| `application-docker.properties` | docker profile override (DB host=`anycloud-db`, kubeconfig=`/app/config/kubeconfig`, ddl-auto=`update` 등) |

#### 주요 키와 환경 변수 매핑

| 프로퍼티 키 | 환경 변수 | base 기본값 | docker 기본값 |
|---|---|---|---|
| `spring.datasource.url` | `DATABASE_HOST`, `DATABASE_PORT`, `DATABASE_NAME` | `jdbc:mariadb://localhost:13306/aipaas` | `jdbc:mariadb://anycloud-db:3306/aipaas` |
| `spring.datasource.username` | `DATABASE_USERID` | `anycloud` | (base 값 그대로) |
| `spring.datasource.password` | `DATABASE_USERPASS` | `anycloud` | (base 값 그대로) |
| `spring.jpa.hibernate.ddl-auto` | `JPA_DDL_AUTO` | `none` | `update` |
| `server.port` | `SERVER_PORT` | `8888` | (base 값 그대로) |
| `server.servlet.context-path` | `SERVER_CONTEXT_PATH` | `/api/v1` | (base 값 그대로) |
| `kubernetes.kubeconfig.path` | `KUBECONFIG_PATH` | `../config/kubeconfig` | `/app/config/kubeconfig` |
| `anycloud.monit.default-url` | `MONIT_DEFAULT_URL` | `http://localhost:9090` | `http://host.docker.internal:9090` |

> 코드에서 `@Value`로 직접 읽는 키는 `kubernetes.kubeconfig.path` (`KubeconfigProvider`)와 `anycloud.monit.default-url` (`KubeConfigClusterLoader`) 두 개입니다. 나머지는 Spring Framework / springdoc / Actuator가 자동 로딩합니다.

> ⚠️ 환경별 비밀값(DB 비밀번호 등)은 git에 포함된 properties 파일에 직접 쓰지 말고 **`.env` 파일이나 환경 변수로 주입**하세요. 모든 키가 `${VAR:default}` 형태이므로 그대로 동작합니다.

> 📝 `application.properties_docker` 파일은 **제거되었습니다** (Spring profile 패턴으로 대체). Dockerfile은 더 이상 파일을 복사하지 않으며, `ENV SPRING_PROFILES_ACTIVE=docker`로 profile을 활성화합니다. 자세한 변경 배경은 [`ENVIRONMENT-SETUP.md`](./ENVIRONMENT-SETUP.md) 참조.

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

### 11-7. ChartMuseum 로컬 저장소 등록

`./chartmuseum_data` 디렉토리는 `docker-compose.yml`에 정의된 `chartmuseum` 서비스가 `/charts`로 마운트하여 사용하는 로컬 차트 저장소입니다. 이 디렉토리에 `.tgz` 파일을 떨어뜨리면 ChartMuseum이 자동으로 인덱싱하여 `index.yaml`을 생성/갱신합니다.

#### (1) 디렉토리 구조 예시

```
any-cloud-management/
└── chartmuseum_data/
    ├── ai-pipeline-0.2.0.tgz       # AI Training Pipeline Orchestrator
    ├── drift-detector-0.2.0.tgz    # Data Drift Detection (Evidently)
    ├── gpu-jupyter-0.2.0.tgz       # GPU 지원 Jupyter
    ├── minio-0.2.0.tgz             # 오브젝트 스토리지
    ├── mlflow-0.2.0.tgz            # 실험 추적
    ├── model-server-0.2.0.tgz      # 모델 서빙
    └── index-cache.yaml            # ChartMuseum이 자동 생성하는 인덱스 캐시 (수정 금지)
```

> 명명 규칙: `<chartName>-<version>.tgz` (helm이 `helm package`로 만든 파일명 그대로). 디렉토리 트리는 평탄(flat)해야 하며 하위 폴더는 사용하지 않습니다.

#### (2) ChartMuseum 컨테이너 기동 확인

`docker-compose.yml`에 이미 정의되어 있으므로 별도 작업 없이 다음으로 기동/확인합니다.

```bash
docker compose up -d chartmuseum
docker exec chartmuseum wget -qO- http://localhost:8080/index.yaml | head -20
# → entries 아래에 chartmuseum_data 의 .tgz 들이 나열되면 정상
```

호스트에서는 `http://localhost:8880/index.yaml` 로 동일하게 접근 가능합니다 (`8880:8080` 매핑).

#### (3) 백엔드 helm_repo 테이블에 등록

백엔드(`anycloud-backend`) 컨테이너에서 ChartMuseum을 호출하므로 **반드시 컨테이너 네트워크 호스트명**(`http://chartmuseum:8080`)을 사용해야 합니다. `localhost:8880`이나 `host.docker.internal`은 백엔드 컨테이너 내부에서 동작하지 않습니다.

| 등록 위치 | URL |
|---|---|
| ✅ 백엔드 컨테이너 → ChartMuseum | `http://chartmuseum:8080` |
| ❌ 사용 불가 | `http://localhost:8880` |
| ❌ 사용 불가 | `http://host.docker.internal:8880` |

등록 방법은 세 가지 중 택일합니다.

**A. REST API (권장)**

```bash
curl -X POST 'http://localhost:8888/api/v1/helm-repos' \
  -H 'Content-Type: application/json' \
  --data-raw '{
    "name": "chartmuseum-local",
    "url": "http://chartmuseum:8080",
    "insecureSkipTLSVerify": false
  }'
# 응답: "CREATED"
```

**B. 프론트엔드 UI**
- ai-paas-web의 Helm Repository 관리 페이지에서 입력
- Name: `chartmuseum-local` / URL: `http://chartmuseum:8080`

**C. DB 직접 INSERT (시드 스크립트용)**

```sql
INSERT INTO helm_repo (id, name, url, insecure_skip_tls_verify, created_at, updated_at)
VALUES (UUID(), 'chartmuseum-local', 'http://chartmuseum:8080', 0, NOW(), NOW())
ON DUPLICATE KEY UPDATE url=VALUES(url), updated_at=NOW();
```

#### (4) 등록 확인

```bash
# 1) 저장소 목록 확인
curl -s 'http://localhost:8888/api/v1/helm-repos'

# 2) 백엔드를 통한 차트 목록 조회
curl -s 'http://localhost:8888/api/v1/charts/chartmuseum-local' | python3 -m json.tool
```

#### (5) 새 차트 추가하는 방법

| 방법 | 명령 |
|---|---|
| 파일 복사 | `cp my-chart-1.0.0.tgz ./chartmuseum_data/` |
| ChartMuseum API 업로드 | `curl --data-binary "@my-chart-1.0.0.tgz" http://localhost:8880/api/charts` |
| `helm cm-push` 플러그인 | `helm plugin install https://github.com/chartmuseum/helm-push`<br>`helm repo add cm-local http://localhost:8880`<br>`helm cm-push my-chart-1.0.0.tgz cm-local` |

> ChartMuseum은 `ALLOW_OVERWRITE: "true"` 옵션으로 기동되므로 동일 버전 재업로드가 허용됩니다. 운영 환경에서는 false로 변경을 권장합니다.

#### (6) Bitnami 등 외부 차트 미러링 (docker.io OCI 우회)

Bitnami 차트가 OCI(`registry-1.docker.io`)로 이전되면서 사내망 / TLS 환경에 따라 `auth.docker.io` handshake가 실패할 수 있습니다. 자주 쓰는 차트를 ChartMuseum에 미리 미러링해 두면 외부 의존성을 제거할 수 있습니다.

```bash
helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo update
helm pull bitnami/nginx --version 18.x.x
curl --data-binary "@nginx-18.x.x.tgz" http://localhost:8880/api/charts
```

이후 프론트엔드 카탈로그에서 `chartmuseum-local` 저장소를 선택해 배포하면 docker.io 의존성 없이 안정적으로 배포됩니다.

---

### 11-8. 빠른 점검 체크리스트

#### 권장 경로 — `./scripts/setup.sh` 한 번에
1. `kubectl config get-contexts -o name` → 사용할 컨텍스트가 모두 보이는지
2. `./scripts/setup.sh` → 12단계 자동 기동 (DB / ChartMuseum / Prometheus / GPU / 백엔드 모두 docker compose로 띄움)
3. 마지막에 출력되는 헬스 체크에서 8개 항목이 모두 ✓ 인지 확인
4. Swagger UI(`http://localhost:8888/api/v1/docs`) 접속해서 API 응답 확인

> ⚠️ `setup.sh` step 11(프론트엔드 자동 시작)은 현재 주석 처리되어 있습니다. 프론트는 `docker-compose.yml`의 `anycloud-frontend` 서비스로 띄우거나(`docker compose up -d anycloud-frontend`), 별도로 `cd ../ai-paas-web && pnpm dev`로 실행하세요.

#### 수동 진단 — setup.sh 없이 확인할 때
1. `kubectl config get-contexts -o name` → DB에 등록될 모든 컨텍스트 확인
2. `docker compose up -d anycloud-db chartmuseum anycloud-backend` → 의존 서비스 + 백엔드 기동
3. `docker exec anycloud-db mariadb -uanycloud -panycloud aipaas -e "SELECT id, api_server_url, monit_server_url FROM cluster;"` → 컨텍스트별 cluster row 자동 등록 확인
4. `docker exec anycloud-backend ls -la /app/app.jar` → jar 빌드 시각이 최신인지 (코드 변경 후엔 `docker compose build` 필요)
5. `curl http://localhost:9090/api/v1/query?query=up` → Prometheus 도달 확인 (port-forward 또는 Ingress)
6. `curl http://localhost:8888/api/v1/system/clusters` → 백엔드가 cluster 목록 응답하는지
7. Swagger (`/api/v1/docs`) 에서 모니터링/비용 API 응답 확인