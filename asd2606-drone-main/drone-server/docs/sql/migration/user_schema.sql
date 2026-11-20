-- ============================================================================
-- 无人机管理平台 · 用户表结构
-- 数据库：drone    字符集：utf8mb4 / utf8mb4_0900_ai_ci
-- ============================================================================
-- 说明：
--   1. password 目前为明文存储，仅用于开发阶段，生产环境须改为哈希（如 BCrypt）。
--   2. status 字段的表定义默认值是 'active'，但现有数据取值为 '1' / '0'；
--      drone-server 的 UserServiceImpl 按「'0' 视为禁用」处理，语义待最终确认。
--   3. username 实际存放手机号。
-- ============================================================================

DROP TABLE IF EXISTS `user`;

CREATE TABLE `user` (
  `id`          bigint       NOT NULL AUTO_INCREMENT,
  `username`    varchar(50)  NOT NULL,
  `name`        varchar(50)  DEFAULT NULL COMMENT '真实姓名',
  `password`    varchar(100) NOT NULL,
  `email`       varchar(100) DEFAULT NULL,
  `phone`       varchar(20)  DEFAULT NULL,
  `age`         int          DEFAULT NULL,
  `gender`      varchar(10)  DEFAULT NULL,
  `status`      varchar(20)  NOT NULL DEFAULT 'active',
  `state`       varchar(20)  NOT NULL DEFAULT 'idle',
  `permission`  varchar(50)  DEFAULT NULL,
  `create_time` datetime     DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
