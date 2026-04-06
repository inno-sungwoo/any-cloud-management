-- Docker Desktop 시연용 DB 초기화
-- 클러스터 + Helm 저장소 등록
-- 사용법: start-demo.sh에서 플레이스홀더 치환 후 실행

-- 클러스터 등록 (인증서 값은 start-demo.sh가 동적으로 치환)
UPDATE cluster SET
  api_server_url = '@@API_SERVER_URL@@',
  api_server_ip = '127.0.0.1',
  server_ca = '@@SERVER_CA@@',
  client_ca = '@@CLIENT_CA@@',
  client_key = '@@CLIENT_KEY@@',
  monit_server_url = 'http://prometheus.aipaas',
  description = 'Docker Desktop K8s (시연용)',
  status = 'ACTIVE'
WHERE id = 'innogrid-aikube';

-- ChartMuseum URL을 로컬로 변경
-- 백엔드가 호스트에서 실행되므로 localhost 사용
-- (백엔드가 K8s Pod로 이관되면 host.docker.internal 또는 Service DNS로 변경)
UPDATE helm_repo SET url = 'http://localhost:8880' WHERE id = '4';
