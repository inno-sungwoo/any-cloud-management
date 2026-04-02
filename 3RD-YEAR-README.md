# any-cloud-management — 3차년도 백엔드 변경 사항

> **브랜치**: `feat/3rd-year-monitoring-apis`
> **기준 브랜치**: `main`
> **변경 규모**: 28 files changed, 1,532 insertions, 23 deletions
> **커밋 수**: 19개

---

## 1. 개요

3차년도 과제의 백엔드 구현. Prometheus 연동 모니터링 API, GPU 비용 관리 API, 감사 로그 API, Helm 보안 검증(dry-run), GPU 예약제 API 등을 개발했다.

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

> Prometheus URL은 DB의 `cluster` 테이블 `monit_server_url` 컬럼에서 읽는다.
> 현재: `http://prometheus.aipaas` (macOS에서는 /etc/hosts에 등록 필요)

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
| GET | `/cost/summary?cluster=&ns=` | 비용 요약 (일 비용, 월 예상, GPU 할당, 활용률) |
| GET | `/cost/idle-warnings?cluster=&ns=` | 유휴 GPU 경고 목록 |
| GET | `/cost/report?cluster=&ns=` | 7일 사용 보고서 (날짜별 활용률, 비용, 판단) |
| GET | `/cost/estimate?cluster=&gpuCount=&hours=` | 비용 추정 (GPU 단가 × 수량 × 시간) |
| GET | `/cost/reservations?cluster=&ns=` | GPU 예약 목록 조회 |
| POST | `/cost/reservations` | GPU 예약 생성 (배포 시 예상 시간 저장) |

### 3-3. 감사 로그 API (`AuditController`)

| Method | URL | 설명 |
|--------|-----|------|
| GET | `/audit/events?cluster=&ns=` | K8s 이벤트 조회 (유형, 사유, 객체, 메시지, 시각) |

### 3-4. 인증 API (`AuthController`)

| Method | URL | 설명 |
|--------|-----|------|
| POST | `/auth/login` | 개발용 Mock 로그인 |

### 3-5. 차트 API 확장 (`ChartController`)

| Method | URL | 설명 |
|--------|-----|------|
| GET | `/charts/{repoName}/{chartName}/values` | 차트 values.yaml 조회 |
| POST | `/charts/deploy` | 차트 배포 (helm install + dry-run 보안 검증) |
| DELETE | `/charts/{cluster}/{ns}/{releaseName}` | 릴리즈 삭제 (helm uninstall) |

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
kubectl get configmap gpu-pricing -n ai-pass3 -o json
```

---

## 7. DB 스키마 (GPU 예약)

```sql
CREATE TABLE gpu_reservation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    cluster_name VARCHAR(255) NOT NULL,
    namespace VARCHAR(255) NOT NULL,
    release_name VARCHAR(255) NOT NULL,
    gpu_count INT NOT NULL,
    expected_hours DOUBLE NOT NULL,
    estimated_cost BIGINT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    expired_at DATETIME
);
```

> 기존 `cluster`, `helm_repo` 테이블은 변경 없음.

---

## 8. 주요 의존성

| 라이브러리 | 버전 | 용도 |
|-----------|------|------|
| Spring Boot | 3.2.5 | 웹 프레임워크 |
| Fabric8 Kubernetes Client | 7.2.0 | K8s API 호출 |
| MariaDB Connector | 3.1.2 | DB 연결 |
| SnakeYAML | 2.2 | values.yaml 파싱 (128MB 제한 확장) |

---

## 9. 알려진 이슈

- **Helm CLI 의존**: `HelmCommandExecutor`가 서버의 `helm` CLI를 직접 호출. PATH에 helm 바이너리 필요.
- **Prometheus DNS**: Java(Netty)는 macOS `/etc/resolver` 무시. `/etc/hosts`에 `prometheus.aipaas` 등록 필수.
- **SnakeYAML 파싱**: bitnami 대형 차트 values.yaml 파싱 시 128MB 제한 설정 필요 (적용 완료).

---

## 10. 접속 정보

| 항목 | 값 |
|------|-----|
| 백엔드 URL | http://localhost:8888/api/v1 |
| Swagger UI | http://localhost:8888/api/v1/swagger-ui/index.html |
| DB | MariaDB localhost:13306 / aipaas / anycloud:anycloud |
