-- demo 业务表（"最高层"裸项目的业务，与 observe 无关，仅用于产生三层事件）
CREATE TABLE IF NOT EXISTS demo_order (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  order_no    VARCHAR(32)  NOT NULL,
  customer    VARCHAR(64)  NOT NULL,
  amount      DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  status      VARCHAR(16)  NOT NULL DEFAULT 'NEW',
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_no (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='demo 订单';

INSERT INTO demo_order (order_no, customer, amount, status) VALUES
  ('SO20260919001', '张三', 1280.00, 'NEW'),
  ('SO20260919002', '李四',  640.50, 'PAID'),
  ('SO20260919003', '王五', 3200.00, 'NEW')
ON DUPLICATE KEY UPDATE customer = VALUES(customer);
