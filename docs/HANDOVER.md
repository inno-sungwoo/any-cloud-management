# AI-PaaS 플랫폼 인수인계 문서

**작성일**: 2026-04-06  
**작성자**: 개발팀  

---

## 1. 프로젝트 구조

```
ai-paas/
├── ai-paas-web/          # 프론트엔드 (React + Vite + TypeScript)
│   ├── src/
│   │   ├── pages/        # 페이지 컴포넌트
│   │   ├── hooks/        # React Query 훅 (API 연동)
│   │   ├── components/   # 재사용 컴포넌트
│   │   └── types/        # TypeScript 타입 정의
│   ├── e2e/              # Playwright E2E 테스트
│   └── playwright.config.ts
│
└── any-cloud-management/ # 백엔드 (Spring Boot + Java 21)
    ├── anycloud/src/main/java/com/aipaas/anycloud/
    │   ├── controller/   # REST API 컨트롤러
    │   ├── service/      # 비즈니스 로직
    │   └── model/        # Entity, DTO
    ├── docker-compose.yml  # MariaDB
    └── anycloud/src/main/resources/
        ├── application.properties  # DB, 서버 설정
        └── application.yaml        # Prometheus 쿼리 정의
```

---

## 2. 환경 시작 방법

### 2-1. 데이터베이스 (MariaDB)
```bash
cd any-cloud-management
docker compose up -d
# MariaDB: localhost:13306, user: anycloud, pw: anycloud, db: aipaas
```

### 2-2. 백엔드 (Spring Boot)
```bash
cd any-cloud-management
./gradlew :anycloud:bootRun
# http://localhost:8888
# Swagger: http://localhost:8888/api/v1/docs
```

### 2-3. 프론트엔드 (Vite)
```bash
cd ai-paas-web
pnpm install
pnpm dev
# http://localhost:5173
```

### 2-4. 로그인
- URL: http://localhost:5173/login
- 인증: localStorage에 JWT 토큰 저장 (accessToken, refreshToken)
- 로그인 후 `/infra-management/monitoring-dashboard`로 리다이렉트

---

## 3. 주요 기능 테스트 가이드

### 테스트 순서대로 진행하세요.

### 3-1. E2E 테스트 실행
```bash
cd ai-paas-web
pnpm test:e2e        # 31개 테스트 자동 실행
pnpm test:e2e:report # HTML 리포트 확인
```
- 로그인 페이지 렌더링, 인증 리다이렉트, 주요 페이지 접근 테스트
- 테스트 파일: `e2e/login.spec.ts`, `e2e/navigation.spec.ts`, `e2e/dashboard.spec.ts`, `e2e/infra.spec.ts`

### 3-2. 모니터링 대시보드
**URL**: http://localhost:5173/infra-management/monitoring-dashboard

확인 항목:
- [ ] 상단 카드 4개: 헬름 릴리즈(13), GPU 수(1), 평균 GPU 활용률, 활성 알림
- [ ] 리소스 게이지: CPU, Memory, GPU 사용률 (실시간 Prometheus 데이터)
- [ ] 리소스 현황: CPU, 메모리, 파일시스템, 파드, GPU 게이지 5개
- [ ] 파드 섹션: 네임스페이스별 Pod 수 테이블 (총 ~314개, 실시간)
- [ ] 성능 지표: CPU 사용량, CPU Load Average 라인차트 (실시간)
- [ ] GPU 현황 테이블: **RTX 3060 실제 데이터** (온도 ~45C, 전력 ~21W, VRAM 12GB)
- [ ] 헬름 릴리즈 현황: 배포된 릴리즈 목록
- [ ] 활성 알림: 알림 목록

### 3-3. 이벤트 페이지
**URL**: http://localhost:5173/infra-management/event

확인 항목:
- [ ] K8s 이벤트가 실시간으로 표시됨 (30초 ���신)
- [ ] 클러스터/네임스페이스 필터 동작
- [ ] 실시간/일시���지 토글
- [ ] Warning/Normal 이벤트가 타임라인 UI로 표시

### 3-4. 감사 로��
**URL**: http://localhost:5173/infra-management/audit-log

확인 항목:
- [ ] 네임스페���스별 K8s 이벤트 테이블
- [ ] 최신순/오래된순 정렬
- [ ] 이��트 수 카운트

### 3-5. 카탈로그 배포 (Helm 차트 배포)
**URL**: http://localhost:5173/infra-management/application/catalog

테스트 시나리오:
1. [ ] 차트 목록이 표시���는지 확인
2. [ ] 차트 선택 (예: gpu-jupyter) -> "배포" 클릭
3. [ ] 릴리즈 이름이 자동 생성되는지 확인 (예: `gpu-jupyter-m3k5p2`)
4. [ ] "자동 생성" 버튼으로 새 이름 생성 가능
5. [ ] values.yaml 편집기에 기본값 로드 + ingress host 자동 설정
6. [ ] "배포" -> 보안 검사 -> 비용 추정 모달 -> "배포" 확인
7. [ ] 배포 성공 시 토스트 메시지
8. [ ] 배포 실패 시 에러 메시지 표시 (이름 중복, 쿼터 초과 등)
9. [ ] 배포 완료 후 접속 URL 확인: `http://{릴리즈이름}.192.168.201.171.nip.io`

**핵심 로직**: 배포 성공 후에만 GPU 예약이 생성됨 (고아 예약 방��)

### 3-6. 배포된 서비스 접속 테스트
배포 완료 후 브라우저에서 직접 접속:
```
http://{릴리즈이름}.192.168.201.171.nip.io
```
- 예시: http://gpu-jupyter-sungwoo32.192.168.201.171.nip.io
- Jupyter Lab 토큰: Pod 로그에서 확인
  ```bash
  kubectl exec -n ai-pass3 <pod-name> -- jupyter server list
  ```
- DNS 설정 불필요 (nip.io가 자동으로 192.168.201.171로 해석)
- 릴리즈 이름만 다르면 여러 사용자가 동시에 각자 서비스 접속 가능

### 3-7. 헬름 릴리즈 관리
**URL**: http://localhost:5173/infra-management/application/helm-release

테스트 ���나리오:
1. [ ] 릴리즈 목록 표시 (네임스페이스 필터)
2. [ ] 릴리즈 삭제 -> 확인 모달 -> 삭제
3. [ ] ���제 시 GPU 예약도 자동 삭제되는지 확인
4. [ ] 삭제 후 서비스 URL 접속 불가 확인

### 3-8. 비용 최적화
**URL**: http://localhost:5173/infra-management/cost-optimization

확인 항목:
- [ ] GPU 사용 예약 목록 (배포 시 자동 생성, 삭제 시 자동 제거)
- [ ] 예약 시간 연장 기능
- [ ] 유휴 GPU 경고
- [ ] 비용 리포트

### 3-9. 클러스터 관리
**URL**: http://localhost:5173/infra-management/cluster-management

��인 항목:
- [ ] 클러스터 목록 (innogrid-aikube)
- [ ] 노��� 상태 표시

---

## 4. 인프라 현황

### 4-1. Kubernetes 클러스터

| 항목 | 값 |
|------|-----|
| API Server | https://192.168.201.171:6443 |
| 인증 방식 | 클라���언트 인증서 (만료: 2027-03-19) |
| 노드 수 | 8개 (1 CP + 6 Worker + 1 GPU) |
| GPU 노드 | `ai-platform` (NVIDIA GeForce RTX 3060, 12GB VRAM) |
| Ingress VIP | 192.168.201.171 (nginx ingress, hostNetwork) |

노드 목록:
| 노드 | IP | 역할 | GPU |
|------|-----|------|-----|
| ai-platform-k8s-cp-1 | 10.10.2.247 | Control Plane | - |
| ai-platform-k8s-worker-1 | 10.10.2.38 | Worker | - |
| ai-platform-k8s-worker-2 | 10.10.2.166 | Worker | - |
| ai-platform-k8s-worker-3 | 10.10.2.75 | Worker | - |
| ai-platform-k8s-worker-4 | 10.10.2.107 | Worker | - |
| ai-platform-k8s-worker-5 | 10.10.2.208 | Worker | - |
| ai-platform-k8s-worker-6 | 10.10.2.61 | Worker | - |
| ai-platform | 192.168.190.53 | Worker + GPU | RTX 3060 (12GB) |

### 4-2. GPU Operator
```
네임스페이스: gpu-operator
구성:
- NVIDIA Driver: 580.126.20 (컨테이너 드라이버)
- Device Plugin: nvidia.com/gpu=1 리소스 등록
- DCGM Exporter: 실시간 GPU 메트릭 수집
- Container Toolkit: GPU 컨테이너 지원
- GPU Feature Discovery: 노드 라벨 자동 설정
```

GPU 상태 확인:
```bash
export KUBECONFIG=/tmp/test-kubeconfig.yaml
kubectl exec -n gpu-operator <nvidia-driver-pod> -- nvidia-smi
```

### 4-3. Prometheus

| 항목 | 값 |
|------|-----|
| URL | http://192.168.201.171:31935 (NodePort) |
| 네임스페이스 | ai-pass |
| ��집 메트릭 | DCGM GPU, kube-state-metrics, node-exporter, helm-exporter |

주요 GPU 메트릭:
- `DCGM_FI_DEV_GPU_UTIL`: GPU 활용률 (%)
- `DCGM_FI_DEV_GPU_TEMP`: GPU 온도 (C)
- `DCGM_FI_DEV_POWER_USAGE`: GPU 전력 (W)
- `DCGM_FI_DEV_FB_USED` / `DCGM_FI_DEV_FB_FREE`: VRAM 사용량 (MB)
- `DCGM_FI_DEV_MEM_COPY_UTIL`: 메모리 활용률 (%)

### 4-4. ��이터베이스

| 항목 | 값 |
|------|-----|
| MariaDB | localhost:13306 (docker compose) |
| DB | aipaas |
| 계정 | anycloud / anycloud |

### 4-5. 서비스 접속 (Ingress + nip.io)

배포된 서비스는 nip.io를 통해 DNS 설정 없이 접속 가능:
```
http://{릴리즈이���}.192.168.201.171.nip.io
```

작동 원리:
```
브라우저: gpu-jupyter-abc.192.168.201.171.nip.io
  -> nip.io DNS: 192.168.201.171 응답
  -> Ingress Controller: Host 헤더로 라우팅
  -> 해당 Pod로 전달
```

향후 내부 DNS에 `*.aipaas -> 192.168.201.171` 와일드카드 레코드를 추가하면,
`DeployCatalogModal.tsx`의 `INGRESS_DOMAIN` 상수를 `'aipaas'`로 변경하여
`http://{릴리즈이름}.aipaas`로 접속 가능.

---

## 5. 주요 변경 사항 (2026-04-06)

### 5-1. GPU 통합 (하드웨어 -> 메트릭 -> 화면 전체 파이프라인)

```
[물리 GPU] RTX 3060 (ai-platform 노드)
  -> [GPU Operator] Driver + Device Plugin + DCGM Exporter
  -> [Prometheus] DCGM_FI_DEV_* 메트릭 수집 (NodePort 31935)
  -> [백엔드] application.yaml DCGM 쿼리
  -> [프론트엔드] 모니터링 대시보드 GPU 현황 표시
```

변경 파일:
- `application.yaml`: `nvidia_smi_*` -> `DCGM_FI_DEV_*` 쿼리로 변경
- `MonitServiceImpl.monitoringGpuStatus()`: DCGM 메트릭 기반 GPU 정보 수집, fallback 로직 추가
- `GpuStatusTable.tsx`: nvidia-smi 없으면 GPU 할당 정보 표시, GPU 없으면 안내 메시지

### 5-2. 클러스터 인증
- 외부 kubeconfig의 정적 자격증명(client cert/key 또는 token)만 사용
- DB `cluster` 테이블의 `oidc_*` 필드는 모두 제거됨
- `api_server_url`을 내부 IP(`192.168.201.171:6443`)로 변경

### 5-3. 배포/삭제 흐름 개선

배포 흐름 (고아 예약 방지):
```
카탈로그 -> 배포 모달 -> 비용 추정 -> deploy API 호출
  -> 성공 시에만 reservation 생성
  -> 실패 시 reservation 미생성 (깨끗한 상태 유지)
```

삭제 흐름 (자동 정리):
```
릴리즈 삭제 -> helm uninstall
  -> 백엔드: costService.deleteReservation() 자동 호출
  -> 프론트: DELETE /cost/reservations/{name} 추가 호출 (이중 안전)
```

기타:
- 릴리즈 이름 자동 생성 (`{chartName}-{timestamp}`)
- values.yaml ingress host 자동 설정 (`{릴리즈이름}.{INGRESS_DOMAIN}`)
- 릴리즈 이름 변경 시 host도 자동 업데이트

### 5-4. 모니터링 대시보��
- `application.yaml`의 `kube_node_info` 조인 중복 해결 (`max by` 추가, 13곳)
- Prometheus URL을 NodePort(31935)로 직접 접근 (포트포워딩 불필���)
- 성능 지표: 하드코딩 목업 -> Prometheus 실시간 CPU usage/load average
- 파드 섹션: 빈 배열 -> 네임스페이스별 Pod 수 실시간 조회
- 리소스 게이지: 올바른 메트릭 키로 수정 (usage + total 비율 ���산)

### 5-5. 이벤트 페이지
- 하드코딩 목업 -> K8s 이벤트 실시간 조회 (`useGetAuditEvents` 훅)
- 클러스터/네임스페이스 필터, 실시간/일시정지 토글

### 5-6. E2E 테스트
- Playwright 설정 및 31개 테스트 추가
- 로그인, 네비게이���, 대시보드, 인프라 페이지
- 실행: `pnpm test:e2e`

### 5-7. 서비스 접속 (nip.io)
- Helm 차트 배포 시 Ingress host를 `{릴리즈이름}.192.168.201.171.nip.io`로 자동 설정
- DNS 설정/hosts 파일 수정 없이 즉시 접속 가능
- 검증 완료: gpu-jupyter 배포 -> K8s Pod(GPU 할당) -> Jupyter Lab 브라우저 접속

---

## 6. 알려진 이슈 / 주의사항

1. **클라���언트 인증서 만료**: 2027-03-19. 갱신 필요.
2. **kube-state-metrics 중복**: 클러스터에 2개 설치됨 -> `application.yaml`에서 `max by`로 중복 제거 처리됨.
3. **GPU 노드**: `ai-platform` 노드 1개만 GPU(RTX 3060) 보유. 다른 worker 노드에는 GPU 없음.
4. **이벤트 페이지**: "전체" 네임스페이스 선택 시 실제로는 `ai-pass3`만 조회됨 (백엔드 API가 단일 namespace만 지원).
5. **Helm 릴리즈 메트릭**: `helm_chart_info`는 ai-pass Prometheus(NodePort 31935)에만 있음.
6. **nip.io 의존**: 현재 서비스 접속은 nip.io(외부 DNS 서비스)에 의존. 내부 DNS에 `*.aipaas -> 192.168.201.171` 설정 시 `INGRESS_DOMAIN` 상수 변경으로 전환 가능.
7. **Prometheus duration 파라미터**: 백엔드 `timeRangeCreate`에서 10000 미만은 분 단위, 이상은 초 단위로 처리됨. 프론트엔드에서 `duration=10800`(3시간, 초)으로 호출.

---

## 7. 개발 명령어 참조

```bash
# 프론트엔드
cd ai-paas-web
pnpm dev              # 개발 ���버 (http://localhost:5173)
pnpm build            # 프로덕션 빌드
pnpm test             # Vitest 단위 테스트
pnpm test:e2e         # Playwright E2E ���스트
pnpm test:e2e:ui      # Playwright UI 모드
pnpm test:e2e:report  # E2E 테스트 리포트
pnpm lint             # ESLint

# 백엔드
cd any-cloud-management
./gradlew :anycloud:bootRun  # 개발 서버 (http://localhost:8888)
./gradlew build              # 빌드

# 데이터베이스
docker compose up -d
docker exec anycloud-db mariadb -u anycloud -panycloud aipaas -e "SELECT * FROM cluster;"

# Kubernetes
export KUBECONFIG=/tmp/test-kubeconfig.yaml
kubectl get pods -A
kubectl get nodes -o wide
kubectl get ingress -A                    # Ingress 목록
kubectl logs -n ai-pass3 <pod-name>       # Pod 로그

# GPU 확인
kubectl get pods -n gpu-operator          # GPU Operator Pod 상태
kubectl exec -n gpu-operator <nvidia-driver-pod> -- nvidia-smi
kubectl get node ai-platform -o jsonpath='{.status.allocatable.nvidia\.com/gpu}'

# Prometheus 직접 쿼리
curl -s 'http://192.168.201.171:31935/api/v1/query?query=DCGM_FI_DEV_GPU_TEMP'

# 배포된 서비스 접속
# http://{릴리즈이름}.192.168.201.171.nip.io
# Jupyter 토큰 확인:
kubectl exec -n ai-pass3 <pod-name> -- jupyter server list
```

---

## 8. 향후 개선 사항

1. **DNS 설정**: 내부 DNS에 `*.aipaas -> 192.168.201.171` 와일드카드 레코드 추가하면 짧은 URL 사용 가능
2. **GPU 노드 확장**: 추가 GPU 노드 연결 시 GPU Operator가 자동으로 Driver/DCGM 설치
3. **nvidia-smi exporter**: DCGM 대비 더 상세한 메트릭(팬 속도 등)을 원하면 추가 설치 가능
