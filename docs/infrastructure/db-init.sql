-- AI-PaaS DB 자동 초기화 스크립트
-- Docker 컨테이너 시작 시 자동 실행됨 (/docker-entrypoint-initdb.d/)
-- MariaDB는 DB가 비어있을 때만 이 스크립트를 실행함

CREATE DATABASE IF NOT EXISTS aipaas DEFAULT CHARACTER SET utf8mb4;

USE aipaas;

-- 클러스터 테이블
CREATE TABLE IF NOT EXISTS cluster (
  id VARCHAR(45) NOT NULL PRIMARY KEY,
  description VARCHAR(255),
  status VARCHAR(45),
  version VARCHAR(45),
  api_server_url VARCHAR(100) NOT NULL,
  api_server_ip VARCHAR(45),
  server_ca MEDIUMTEXT,
  client_ca MEDIUMTEXT,
  client_key MEDIUMTEXT,
  client_token MEDIUMTEXT,
  auth_type VARCHAR(20) DEFAULT 'token',
  monit_server_url VARCHAR(100),
  monit_status VARCHAR(20) DEFAULT 'NOT_CONFIGURED',
  monit_last_check TIMESTAMP NULL,
  monit_last_error VARCHAR(500),
  cluster_type VARCHAR(100) NOT NULL DEFAULT '',
  cluster_provider VARCHAR(100) NOT NULL DEFAULT '',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Helm 저장소 테이블
CREATE TABLE IF NOT EXISTS helm_repo (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  url VARCHAR(100) NOT NULL,
  username VARCHAR(100),
  password VARCHAR(100),
  ca_file LONGTEXT,
  insecure_skip_tls_verify TINYINT(1) NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- GPU 예약 테이블
CREATE TABLE IF NOT EXISTS gpu_reservation (
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

-- anycloud 유저에 aipaas DB 권한 부여 (docker-compose에서 MYSQL_DATABASE=aipaas로 생성 시)
GRANT ALL PRIVILEGES ON aipaas.* TO 'anycloud'@'%';
FLUSH PRIVILEGES;
