# 멀티클러스터 모니터링 — 향후 개선 계획

> **상태**: Draft / 계획 단계 (실 구현 전)
> **범위**: `setup.sh` 멀티 컨텍스트 처리 + 멀티클러스터 메트릭 아키텍처 후보 + 도입 시나리오
> **선결 조건**: Phase 1 (cluster.monit_status 추적, 헬스체크, 진단 엔드포인트) 이미 완료

이 문서는 현재 단일 클러스터 모니터링 셋업을 진짜 멀티클러스터로 확장할 때를 위한 설계 메모입니다. **지금 코드 변경은 없으며**, 추후 필요 시점에 참조할 자료입니다.

---

## 1. 현재 setup.sh의 한계 (확인된 사실)

`scripts/setup.sh`는 컨텍스트 N개를 카운트하고 DB `cluster` 테이블에 N개 row를 만들지만, 실제 모니터링 인프라(Prometheus / Ingress / helm-exporter / GPU 리소스)는 **kubectl current-context 한 클러스터에만** 설치합니다.

| 단계 | 멀티 컨텍스트 처리? | 라인 |
|---|---|---|
| 컨텍스트 카운트 | ✅ | L66 |
| `kubectl get nodes` 사전 검증 | ❌ current-context만 | L70 |
| `cluster` 테이블 upsert (컨텍스트별 row) | ✅ | L197-230 |
| `monit_server_url` 값 | ❌ **모든 row에 `http://localhost:9090` 동일 하드코딩** | L213 |
| Namespace 생성 | ❌ current-context만 | L248 |
| **Prometheus helm install** | ❌ current-context만 | L255-265 |
| Ingress / ServiceMonitor | ❌ current-context만 | L273~ |
| helm-exporter | ❌ current-context만 | L366~ |
| GPU ResourceQuota / pricing CM | ❌ current-context만 | L319~ |

→ 결과: 컨텍스트가 N개 등록되어 있어도 selector에서 어떤 클러스터를 선택해도 같은 Prometheus(`localhost:9090`)에 도달 → 같은 데이터.

---

## 2. 옵션 비교 — 어떤 토폴로지로 갈 것인가

추후 도입 시 선택 가능한 3가지 토폴로지. 트레이드오프는 워크로드/팀 규모에 따라 달라집니다.

### 옵션 A — Pull 분산 (각 클러스터 Prometheus를 PaaS 백엔드가 직접 호출)

```
[Cluster A] kube-prometheus-stack ── Ingress(prom-A.example.com) ──┐
[Cluster B] kube-prometheus-stack ── Ingress(prom-B.example.com) ──┼──► PaaS 백엔드 (N개 호출 fanout)
[Cluster C] kube-prometheus-stack ── Ingress(prom-C.example.com) ──┘
```

- 장점: 추가 인프라 없음. 기존 백엔드 코드 거의 그대로
- 단점:
  - cross-cluster 집계는 백엔드가 N번 호출 후 수동 합산 (PromQL `topk`/`histogram_quantile` 클러스터 경계 못 넘음)
  - 폐쇄망/사설 클러스터(NAT 뒤) 인바운드 도달성 문제
  - N 증가 시 백엔드 부하 선형 증가
- **적합 케이스**: 클러스터 ≤ 5개, 단일 네트워크 영역, cross-cluster 통합 뷰 불필요

### 옵션 B — Push 중앙 집중 (각 Prometheus가 remote_write로 중앙 TSDB로 푸시)

```
[Cluster A] Prometheus ─remote_write─┐
[Cluster B] Prometheus ─remote_write─┼─► Thanos Receive / Mimir / VictoriaMetrics ─► Object Storage (S3)
[Cluster C] Prometheus ─remote_write─┘                                                    │
                                                                                          ▼
                                                                            Thanos Querier / vmselect
                                                                                          │
                                                                                          ▼
                                                                                    PaaS 백엔드 (단일 엔드포인트)
```

- 장점:
  - 단일 PromQL 엔드포인트 → cross-cluster 집계 자유
  - 네트워크 방향이 아웃바운드(클러스터 → 중앙) → NAT/방화벽 친화, 폐쇄망 OK
  - 중앙에서 retention/HA/다운샘플링 일원화
  - 백엔드 cluster.monit_server_url 컬럼 자체가 불필요해짐 (cluster 라벨로 분리)
- 단점: 중앙 TSDB와 오브젝트 스토리지 운영 학습 필요
- **적합 케이스**: 클러스터 5개 이상, cross-cluster 집계 필요, 폐쇄망 클러스터 포함, 장기 보존 필요

### 옵션 C — Federation (Prometheus 자체 federation)

- 중앙 Prometheus가 각 클러스터 Prometheus의 일부 메트릭을 pull
- 단순하지만 cardinality 폭발 시 한계
- **적합 케이스**: 임시 실험. 권장하지 않음

---

## 3. 옵션 B 도입 시 — 중앙 TSDB 후보 비교

2025년 기준 사실상 표준은 **Thanos / Grafana Mimir / VictoriaMetrics** 3가지. 각각의 특징 (외부 리서치 기반):

### 3-1. Thanos

기존 Prometheus 인스턴스 옆에 **사이드카**를 붙여 메트릭을 객체 스토리지(S3/GCS 등)로 업로드하고, 중앙 Querier가 클러스터 전반에 걸쳐 쿼리를 수행합니다. 기존 Prometheus 환경을 점진적으로 확장하는 데 적합.

- **점진적 마이그레이션** 친화 (기존 Prometheus 그대로 두고 사이드카만 추가)
- 사이드카 또는 Receiver 두 가지 모드 지원
- 글로벌 쿼리 뷰 + S3 저장
- 참고: [Scaling Prometheus with Thanos (cloudraft.io)](https://www.cloudraft.io/blog/scaling-prometheus-with-thanos), [Thanos vs Mimir 비교 (Grafana 커뮤니티)](https://community.grafana.com/t/thanos-vs-mimir-choosing-the-right-prometheus-extension/157751)

### 3-2. Grafana Mimir

Cortex의 후속. 일관된 해싱과 객체 스토어로 자동 샤딩하여 대규모/내구성을 확보. 엄격한 테넌트 분리와 수평 확장이 필요한 엔터프라이즈 환경에서 강점.

- **그린필드 배포**에 적합 (기존 Prometheus 의존도 낮음)
- 강력한 멀티 테넌시 (`X-Scope-OrgID` 헤더 기반)
- 더 나은 성능과 더 단순한 아키텍처를 제공하지만 인프라 변경이 더 큼
- 참고: [Grafana Mimir HA deduplication 문서](https://grafana.com/docs/mimir/latest/configure/configure-high-availability-deduplication/)

### 3-3. VictoriaMetrics

표준 Prometheus `remote_write` API로 데이터를 받음 (Thanos는 비표준 사이드카 필요). **20배 더 나은 압축률**, 낮은 리소스 사용량, 높은 cardinality에서도 안정적.

- 단일 바이너리(single-binary) 배포 가능 → 운영 부담 최소
- 또는 cluster 모드(vminsert/vmstorage/vmselect)로 수평 확장
- 참고: [VictoriaMetrics 공식 문서](https://docs.victoriametrics.com/), [Thanos vs VictoriaMetrics vs Mimir 성능 비교 (onidel.com)](https://onidel.com/blog/prometheus-storage-comparison-2025)

### 3-4. 의사결정 가이드

| 상황 | 권장 |
|---|---|
| 이미 Prometheus 운영 중, 점진 확장 | **Thanos** |
| 그린필드, 멀티 테넌시 강력하게 필요 | **Mimir** |
| 운영 단순성 + 압축률 + cardinality 부담 | **VictoriaMetrics** |
| 클러스터 ≤ 3개, 통합 뷰 불필요 | 옵션 A (Pull 분산) — 굳이 도입 안 함 |

→ 본 프로젝트(AI-PaaS) 규모와 운영팀 크기를 고려하면 **VictoriaMetrics single-binary**가 가장 낮은 학습 곡선과 운영 비용으로 시작 가능. 5개 이상 클러스터/장기 보존 요구 시 cluster 모드로 전환.

배경 자료:
- [Choosing the right Kubernetes monitoring stack in 2026 (Spectro Cloud)](https://www.spectrocloud.com/blog/choosing-the-right-kubernetes-monitoring-stack)
- [Evaluating Large Scale Solutions for Multi Tenant Metrics System (CECG)](https://www.cecg.io/blog/evaluating-large-scale-solutions-for-multi-tenant-metrics-system)
- [Flipkart Scales Prometheus to 80M Metrics (InfoQ)](https://www.infoq.com/news/2025/10/flipkart-prometheus-80million/)

---

## 4. 클러스터 라벨 + HA 디듀플리케이션

옵션 B로 가면 **`cluster` 라벨**이 모든 시계열의 첫 번째 축이 됩니다. 잘못 설계하면 cardinality 폭발 + dedup 실패가 발생합니다.

### 4-1. external_labels 표준

각 클러스터 Prometheus의 `prometheus.yml` 또는 helm values:

```yaml
prometheus:
  prometheusSpec:
    externalLabels:
      cluster: ai-platform-k8s   # 클러스터 식별
      __replica__: replica-0     # HA 페어일 때 인스턴스 식별
    remoteWrite:
      - url: https://central-vm.example.com/api/v1/write
        basicAuth:
          username: { name: vm-creds, key: user }
          password: { name: vm-creds, key: pass }
```

- `cluster` 라벨은 **동일 데이터를 스크랩하는 그룹**을 식별
- `__replica__` 라벨은 그룹 안의 개별 인스턴스 (HA 페어)
- 중앙 TSDB는 `cluster` 별로 leader replica를 선출하여 `__replica__`를 제거 후 저장 → 단일 시계열로 보임

(참고: [New Relic Prometheus HA 문서](https://docs.newrelic.com/docs/infrastructure/prometheus-integrations/install-configure/prometheus-high-availability-ha/), [Mimir HA dedup 문서](https://grafana.com/docs/mimir/latest/configure/configure-high-availability-deduplication/), [Cortex HA pair handling](https://cortexmetrics.io/docs/guides/ha-pair-handling/))

### 4-2. 주의점

- `__replica__`는 `external_labels`에 두면 `remote_read`가 깨짐. **`remote_write` 섹션에만** 두는 것이 권장
- 모든 PromQL은 `{cluster="..."}` 셀렉터를 가져야 정확한 결과 (백엔드가 자동 주입하도록 `PrometheusQueryService.resolve()` 리팩토링 필요)
- 라벨 표준을 어기면 cardinality 폭발: `team`, `env` 같은 추가 라벨은 신중히

---

## 5. setup.sh 멀티 컨텍스트 리팩토링 (옵션 A 한정)

옵션 A를 채택하면 setup.sh가 컨텍스트마다 동일 스택을 배포하도록 수정해야 합니다. 옵션 B로 가면 setup.sh 자체가 사라지고 GitOps(ArgoCD ApplicationSet)로 대체됩니다.

### 5-1. 핵심 변경점

1. **모든 `kubectl` / `helm` 명령에 `--context "$ctx"` 명시** — current-context 의존 제거
2. **컨텍스트 순회 루프** — `CONTEXTS=$(kubectl config get-contexts -o name)` 로 N번 반복
3. **`monit_server_url`을 컨텍스트별로 받음** — 환경변수 `MONIT_URL_<ctx>` 또는 함수 호출 (예: `https://prometheus-${ctx}.example.com`)
4. **도달 불가 클러스터는 skip + WARN** — 한 클러스터 실패가 전체 setup을 죽이지 않음
5. **`CONTEXTS` 환경변수로 대상 한정 가능** — `CONTEXTS=ai-platform-k8s ./setup.sh` 로 단일 클러스터만 셋업 (옵션 B 호환)

### 5-2. 스크립트 골격

```bash
# 1. 명시적 컨텍스트 목록
CONTEXTS="${CONTEXTS:-$(kubectl config get-contexts -o name)}"

# 2. 컨텍스트마다 외부 모니터링 URL 매핑
get_monit_url() {
  local ctx="$1"
  local var="MONIT_URL_$(echo "$ctx" | tr -c 'A-Za-z0-9' '_')"
  if [ -n "${!var:-}" ]; then echo "${!var}"
  else echo "https://prometheus-${ctx}.example.com"
  fi
}

# 3. 컨텍스트마다 install_one()
install_one() {
  local ctx="$1"
  echo ">>> 클러스터 [$ctx] 셋업"
  kubectl --context "$ctx" get nodes >/dev/null 2>&1 \
    || { warn "$ctx 도달 불가, 건너뜀"; return 0; }

  kubectl --context "$ctx" create namespace "$NAMESPACE" 2>/dev/null || true

  if helm --kube-context "$ctx" status "$PROM_RELEASE" -n "$NAMESPACE" >/dev/null 2>&1; then
    ok "[$ctx] Prometheus 이미 설치"
  else
    helm --kube-context "$ctx" install "$PROM_RELEASE" \
      prometheus-community/kube-prometheus-stack \
      --namespace "$NAMESPACE" \
      --set "prometheus.prometheusSpec.externalLabels.cluster=$ctx" \
      -f "$INFRA_DIR/ai-pass-prometheus-values.yaml" \
      --wait --timeout 3m
    ok "[$ctx] Prometheus 설치 완료"
  fi

  apply_extras "$ctx"  # Ingress, helm-exporter, GPU 등도 --context $ctx
}

# 4. cluster 테이블 upsert
upsert_cluster_row() {
  local ctx="$1"
  local monit_url; monit_url="$(get_monit_url "$ctx")"
  docker exec -i ... mariadb ... <<SQL
INSERT INTO cluster (id, ..., monit_server_url, ...)
VALUES ('$ctx', ..., '$monit_url', ...)
ON DUPLICATE KEY UPDATE monit_server_url='$monit_url', ...;
SQL
}

# 5. main loop
for ctx in $CONTEXTS; do
  install_one "$ctx"
  upsert_cluster_row "$ctx"
  # Phase 1에서 추가한 즉시 헬스체크 트리거
  curl -sX POST "http://localhost:8888/api/v1/system/cluster/$ctx/monit-health-check"
done
```

### 5-3. Ingress 호스트 정책

```yaml
# kube-prometheus-stack values
prometheus:
  ingress:
    enabled: true
    ingressClassName: nginx
    hosts:
      - prometheus-{{ .ctx }}.example.com   # 컨텍스트별 다름
    tls:
      - hosts: [prometheus-{{ .ctx }}.example.com]
        secretName: prom-{{ .ctx }}-tls
    annotations:
      nginx.ingress.kubernetes.io/auth-type: basic
      nginx.ingress.kubernetes.io/auth-secret: prom-basic-auth
```

→ 각 클러스터의 Ingress host = DB의 `monit_server_url`. **cluster.id ↔ Ingress host ↔ external_label** 3자가 일치해야 함.

### 5-4. 이 접근의 한계

- 신규 클러스터가 추가될 때마다 setup.sh 재실행 필요
- 클러스터 정책 차이 (예: GPU quota를 클러스터별로 다르게)를 스크립트가 제대로 표현 못 함
- 스크립트 + DB 두 가지 진실의 원천 (drift 발생 가능)

→ 5개 이상 클러스터를 다룬다면 **GitOps**로 전환하는 것이 안전 (다음 섹션).

---

## 6. GitOps 기반 fleet 모니터링 (옵션 A/B 공통, 권장)

`setup.sh`를 영구적으로 대체하는 정공법. ArgoCD ApplicationSet으로 모든 클러스터에 모니터링 스택을 선언적으로 배포합니다.

### 6-1. ApplicationSet (Cluster generator)

```yaml
apiVersion: argoproj.io/v1alpha1
kind: ApplicationSet
metadata:
  name: kube-prometheus-stack
  namespace: argocd
spec:
  generators:
    - clusters: {}                # ArgoCD에 등록된 모든 클러스터를 대상
  template:
    metadata:
      name: 'kube-prom-{{name}}'
    spec:
      project: monitoring
      source:
        repoURL: https://prometheus-community.github.io/helm-charts
        chart: kube-prometheus-stack
        targetRevision: 60.0.0
        helm:
          values: |
            prometheus:
              prometheusSpec:
                externalLabels:
                  cluster: {{name}}
                  __replica__: $(POD_NAME)
                remoteWrite:
                  - url: https://central-vm.example.com/api/v1/write
            ingress:
              enabled: true
              hosts:
                - prometheus-{{name}}.example.com
      destination:
        server: '{{server}}'
        namespace: monitoring
      syncPolicy:
        automated: { prune: true, selfHeal: true }
```

20개 클러스터 × 5개 애드온 = 100개 ArgoCD Application이 단일 ApplicationSet 파일로 자동 생성됨. 신규 클러스터 등록 시 ApplicationSet은 그대로 두고 ArgoCD에 클러스터만 추가하면 됨.

(참고: [Codefresh — Structuring ArgoCD Repositories with ApplicationSets](https://codefresh.io/blog/how-to-structure-your-argo-cd-repositories-using-application-sets/), [InfraCloud — Multicluster GitOps with ArgoCD](https://www.infracloud.io/blogs/multicluster-gitops-argocd/), [DigitalOcean — Manage Multi-Cluster Deployments with ArgoCD](https://www.digitalocean.com/community/tutorials/application-deployments-multi-cluster-kubernetes), [ArgoCD ApplicationSet Use Cases (공식 문서)](https://argo-cd.readthedocs.io/en/stable/operator-manual/applicationset/Use-Cases/))

### 6-2. 모니터링 외 베이스라인

같은 ApplicationSet 패턴으로 다음을 모든 클러스터에 표준 배포:
- `kube-prometheus-stack` — Prometheus + node-exporter + kube-state-metrics + AlertManager
- `helm-exporter` — 헬름 릴리즈 인벤토리
- `nvidia/gpu-operator` — DCGM exporter 포함, GPU 클러스터 한정 (cluster generator filter 사용)
- `vector` 또는 `loki` — 로그 (감사 로그 페이지의 향후 확장)

---

## 7. 클러스터 라이프사이클 도구 (장기, 옵션)

신규 클러스터 자체를 자동 프로비저닝하고 PaaS에 자동 등록하려면 fleet 관리 도구 도입을 검토:

### 7-1. Cluster API (CAPI)

선언적으로 클러스터 lifecycle을 관리. `Cluster` / `MachineDeployment` 등 CRD로 desired state 정의 → controller가 reconcile.

- **2026/01 v1.12 릴리즈**에서 in-place updates와 chained upgrades 도입 ([Kubernetes 블로그](https://kubernetes.io/blog/2026/01/27/cluster-api-v1-12-release/))
- ClusterClass로 클러스터 템플릿 표준화

### 7-2. Crossplane

Kubernetes를 외부 인프라 API의 control plane으로 사용. Composition으로 CAPI Cluster + VPC + IAM을 한 묶음으로 정의 → 단일 claim으로 fully bootstrapped 클러스터 생성 가능.

### 7-3. AWS Multi-Cluster GitOps 패턴

AWS의 [Part 2: Multi-Cluster GitOps — Cluster fleet provisioning and bootstrapping](https://aws.amazon.com/blogs/containers/part-2-multi-cluster-gitops-cluster-fleet-provisioning-and-bootstrapping/) 가이드는 CAPI + ArgoCD로 cluster fleet을 부트스트랩하는 패턴을 기술. 클러스터 생성 즉시 ArgoCD가 모니터링 애드온을 자동 설치.

### 7-4. Azure Kubernetes Fleet Manager

Azure 환경 한정. Hub cluster를 control plane으로 두고 멀티 AKS를 통합 관리 ([Microsoft Learn 문서](https://learn.microsoft.com/en-us/azure/kubernetes-fleet/concepts-multi-cluster-workload-management)).

(참고: [Best Multi-Cluster Kubernetes Tools in 2026 (acecloud.ai)](https://acecloud.ai/blog/best-multi-cluster-kubernetes-tools/), [Komodor — Mastering Kubernetes Fleet Management](https://komodor.com/blog/mastering-kubernetes-fleet-management-for-multi-cluster-success/), [Fleet-Ready by Design (aokumo.io)](https://aokumo.io/blog/building-fleet-ready-platform/))

---

## 8. OpenTelemetry Collector 사이드 (선택, 미래 대비)

2025년 컨센서스: PromQL/Grafana를 좋아해도 **OTel Collector를 앞단에 두라**. 벤더 선택권 + 미래 대비 auto-instrumentation + 데이터 송출 전 scrub rule 적용 한 곳에서 가능.

```
[Cluster] App → OTel Collector(DaemonSet) → ┬─► Prometheus(metrics)
                                            ├─► Loki(logs)
                                            └─► Tempo(traces)
                                                       ↓
                                              중앙 TSDB / 로그 / 트레이스 백엔드
```

본 프로젝트가 메트릭 외에 로그/트레이스로 확장한다면 OTel Collector를 베이스라인 컴포넌트로 추가하는 것을 검토.

---

## 9. 도입 단계 (제안)

| Phase | 목표 | 주요 작업 | 트리거 시점 |
|---|---|---|---|
| **Phase 1 ✅ (완료)** | 단일 클러스터 데이터 신뢰성 | `monit_status` 추적, 헬스체크, 진단 엔드포인트, mock 폴백 제거 | 이미 완료 |
| **Phase 2 (단기)** | setup.sh 멀티 컨텍스트 | 5장의 리팩토링. 컨텍스트별 `--context`, `monit_server_url` 매핑, 헬스체크 자동 트리거 | 클러스터 2~3개 추가 시점 |
| **Phase 3 (중기)** | GitOps 전환 | ArgoCD + ApplicationSet으로 베이스라인 표준 배포. setup.sh deprecated | 클러스터 5개 이상 또는 정책 일관성 요구 시 |
| **Phase 4 (중기~장기)** | 중앙 TSDB | VictoriaMetrics single-binary 도입 → Prometheus remote_write → 백엔드는 단일 엔드포인트 | cross-cluster 집계 요구 시 또는 폐쇄망 클러스터 등장 시 |
| **Phase 5 (장기)** | Fleet management | CAPI/Crossplane + ArgoCD로 클러스터 자체 생성 자동화. 자체 등록 마법사 | PaaS 제품화 + 외부 사용자에게 클러스터 등록 개방할 때 |

각 단계는 비파괴적 — 이전 단계의 코드/설정을 점진적으로 대체합니다.

---

## 10. 즉시 적용 가능한 작은 개선 (지금 코드 변경 없이 SQL/문서만)

Phase 2 도입 전이라도 Phase 1 위에 다음 것들은 SQL/CLI만으로 가능:

1. **잘못된 cluster row 정리**
   ```sql
   DELETE FROM cluster
   WHERE monit_server_url IS NULL
      OR monit_server_url LIKE '%@@%'
      OR monit_server_url = '';
   ```

2. **각 컨텍스트의 진짜 Prometheus URL 수동 입력**
   ```sql
   UPDATE cluster SET monit_server_url='https://prom-A.example.com' WHERE id='cluster-A';
   UPDATE cluster SET monit_server_url='https://prom-B.example.com' WHERE id='cluster-B';
   ```
   백엔드의 1분 헬스체크가 자동으로 `monit_status`를 갱신합니다.

3. **모니터링 안 하는 클러스터는 row 삭제** — selector에서 사라짐
   ```sql
   DELETE FROM cluster WHERE id='unused-cluster';
   ```

4. **클러스터에 helm으로 직접 베이스라인 설치** (옵션 A 임시 운영)
   ```bash
   for ctx in $(kubectl config get-contexts -o name); do
     helm --kube-context "$ctx" install kps prometheus-community/kube-prometheus-stack \
       -n monitoring --create-namespace \
       --set prometheus.prometheusSpec.externalLabels.cluster=$ctx
   done
   ```

---

## 11. 참고 자료

### Prometheus / 중앙 TSDB
- [Scaling Prometheus with Thanos (cloudraft.io)](https://www.cloudraft.io/blog/scaling-prometheus-with-thanos)
- [Thanos vs Mimir 비교 (Grafana 커뮤니티)](https://community.grafana.com/t/thanos-vs-mimir-choosing-the-right-prometheus-extension/157751)
- [Thanos vs VictoriaMetrics vs Mimir 성능 비교 (onidel.com)](https://onidel.com/blog/prometheus-storage-comparison-2025)
- [VictoriaMetrics 공식 문서](https://docs.victoriametrics.com/)
- [VictoriaMetrics FAQ](https://docs.victoriametrics.com/faq/)
- [Choosing the right Kubernetes monitoring stack in 2026 (Spectro Cloud)](https://www.spectrocloud.com/blog/choosing-the-right-kubernetes-monitoring-stack)
- [Evaluating Large Scale Solutions for Multi Tenant Metrics System (CECG)](https://www.cecg.io/blog/evaluating-large-scale-solutions-for-multi-tenant-metrics-system)
- [Flipkart Scales Prometheus to 80M Metrics (InfoQ)](https://www.infoq.com/news/2025/10/flipkart-prometheus-80million/)
- [Prometheus Alternatives & Competitors (Uptrace)](https://uptrace.dev/comparisons/prometheus-alternatives)

### HA / Deduplication / external_labels
- [Prometheus High Availability (New Relic 문서)](https://docs.newrelic.com/docs/infrastructure/prometheus-integrations/install-configure/prometheus-high-availability-ha/)
- [Configure Grafana Mimir HA Deduplication (Grafana 문서)](https://grafana.com/docs/mimir/latest/configure/configure-high-availability-deduplication/)
- [Cortex HA pair handling 가이드](https://cortexmetrics.io/docs/guides/ha-pair-handling/)
- [Deduping HA Prometheus Samples in Cortex (Grafana Labs 블로그)](https://grafana.com/blog/2019/10/03/deduping-ha-prometheus-samples-in-cortex/)
- [AWS Managed Prometheus — HA dedup 문서](https://docs.aws.amazon.com/prometheus/latest/userguide/AMP-ingest-dedupe.html)

### GitOps / ArgoCD ApplicationSet
- [Codefresh — Structuring ArgoCD Repositories with ApplicationSets](https://codefresh.io/blog/how-to-structure-your-argo-cd-repositories-using-application-sets/)
- [InfraCloud — Multicluster GitOps with ArgoCD](https://www.infracloud.io/blogs/multicluster-gitops-argocd/)
- [DigitalOcean — Manage Multi-Cluster Deployments with ArgoCD](https://www.digitalocean.com/community/tutorials/application-deployments-multi-cluster-kubernetes)
- [ArgoCD ApplicationSet Use Cases (공식)](https://argo-cd.readthedocs.io/en/stable/operator-manual/applicationset/Use-Cases/)
- [Sam De Wolf — Managing multi-AKS-cluster deployments with GitOps and ArgoCD](https://dewolfs.github.io/managing-multi-aks-cluster-with-gitops/)
- [Installing Prometheus on Kubernetes with ArgoCD (Pete @ Medium)](https://pete8s.medium.com/installing-prometheus-on-kubernetes-with-argocd-a4e99580543d)

### Fleet management / CAPI / Crossplane
- [Cluster API v1.12: In-place Updates and Chained Upgrades (Kubernetes 블로그)](https://kubernetes.io/blog/2026/01/27/cluster-api-v1-12-release/)
- [AWS — Multi-Cluster GitOps Cluster fleet provisioning and bootstrapping](https://aws.amazon.com/blogs/containers/part-2-multi-cluster-gitops-cluster-fleet-provisioning-and-bootstrapping/)
- [Crossplane — Universal Control Plane (Medium)](https://medium.com/@neoph.za/crossplane-transform-your-kubernetes-cluster-into-a-universal-control-plane-92c6a3af37df)
- [Komodor — Mastering Kubernetes Fleet Management](https://komodor.com/blog/mastering-kubernetes-fleet-management-for-multi-cluster-success/)
- [Best Multi-Cluster Kubernetes Tools in 2026 (acecloud.ai)](https://acecloud.ai/blog/best-multi-cluster-kubernetes-tools/)
- [Fleet-Ready by Design: Evolving the Kubernetes Platform Stack (aokumo.io)](https://aokumo.io/blog/building-fleet-ready-platform/)
- [Kubernetes Cluster Management with ClusterAPI, Crossplane, Projectsveltos (ITNEXT)](https://itnext.io/kubernetes-cluster-management-and-cloud-automation-with-clusterapi-crossplane-and-projectsveltos-a20594be51b5)
- [Azure Kubernetes Fleet Manager (Microsoft Learn)](https://learn.microsoft.com/en-us/azure/kubernetes-fleet/concepts-multi-cluster-workload-management)

---

## 12. 결정해야 할 것 (도입 시점에)

이 문서를 다시 꺼내볼 때 답해야 할 질문들:

1. 클러스터 수가 몇 개인가? (≤3 → 옵션 A로 충분, ≥5 → 옵션 B 필수 검토)
2. 모든 클러스터가 같은 네트워크 영역인가? (사설망/폐쇄망이 있다면 옵션 B 강제)
3. cross-cluster 통합 카드(전체 GPU 활용률 등)가 필요한가? (필요 → 옵션 B)
4. 운영팀이 새로운 컴포넌트(Thanos/Mimir/VM) 학습할 여유가 있는가? (없다 → VictoriaMetrics single-binary부터)
5. 클러스터 자체를 자동 생성/회수해야 하는가? (있다 → CAPI/Crossplane 검토)
6. 메트릭 외에 로그/트레이스로 확장 계획이 있는가? (있다 → OTel Collector 베이스라인)

위 답에 따라 Phase 2~5를 단계적으로 진행. 지금은 Phase 1 결과로 단일 클러스터 + 명확한 헬스 상태 표시까지로 충분.
