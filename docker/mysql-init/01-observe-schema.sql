-- ★ 一次定型，不可回退。与 specs/SCHEMA.md 第四节完全一致。
-- 改动此文件 = 回退。缺列请在阶段二走「加列」流程。
-- ★ SET NAMES 不能省：mysql 客户端默认字符集取自容器 locale，
--   POSIX/C locale 会退到 latin1，注释里的中文会写坏。
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS observe_event (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  event_id     CHAR(36)     NOT NULL COMMENT '事件唯一ID(uuid)',
  trace_id     CHAR(32)     DEFAULT NULL COMMENT '三层缝合线',
  session_id   CHAR(36)     NOT NULL COMMENT '会话ID',
  step_no      INT          NOT NULL COMMENT '会话内步序,从1开始',

  layer        VARCHAR(16)  NOT NULL COMMENT 'FRONTEND/BACKEND/RESOURCE',
  event_type   VARCHAR(24)  NOT NULL COMMENT 'PAGE_VIEW/CLICK/INPUT/SUBMIT/API/SQL/REDIS/MQ',
  node_key     VARCHAR(255) NOT NULL COMMENT '归一化指纹=图节点ID',
  node_label   VARCHAR(255) DEFAULT NULL COMMENT '图上显示名',

  parent_key   VARCHAR(255) DEFAULT NULL COMMENT '父节点指纹,用于建边',
  edge_type    VARCHAR(16)  DEFAULT NULL COMMENT '本事件引入的边类型',

  occurred_at  DATETIME(3)  NOT NULL COMMENT '发生时间(毫秒精度)',
  duration_ms  INT          DEFAULT NULL,

  status       VARCHAR(16)  NOT NULL DEFAULT 'OK' COMMENT 'OK/ERROR',
  error_msg    VARCHAR(512) DEFAULT NULL,

  tenant_id    BIGINT       DEFAULT NULL COMMENT '多租户维度',
  user_id      BIGINT       DEFAULT NULL,
  app_name     VARCHAR(64)  DEFAULT NULL COMMENT 'spring.application.name',

  raw_payload  JSON         DEFAULT NULL COMMENT '原始值(已脱敏),用于排查',

  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_event (event_id),
  KEY idx_session (session_id, step_no),
  KEY idx_trace (trace_id),
  KEY idx_node (node_key),
  KEY idx_time (occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='观测事件流(append-only)';
