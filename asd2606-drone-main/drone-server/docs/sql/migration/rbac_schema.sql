-- ============================================================================
-- 无人机管理平台 · RBAC 表结构
-- 数据库：drone
-- ============================================================================
-- 说明：
--   1. `role` 是 MySQL 8.0 的关键字，表名必须带反引号，别去掉。
--   2. 两张关联表刻意不加外键约束（靠应用层保证），避免批量导数据、
--      后续分库分表时被约束卡住。
--   3. 执行顺序：rbac_schema.sql → rbac_seed.sql → rbac_drop_legacy.sql
-- ============================================================================

DROP TABLE IF EXISTS `user_role`;
DROP TABLE IF EXISTS `role_permission`;
DROP TABLE IF EXISTS `permission`;
DROP TABLE IF EXISTS `role`;

-- 角色表
CREATE TABLE `role` (
  `id`          bigint       NOT NULL AUTO_INCREMENT,
  `code`        varchar(50)  NOT NULL COMMENT '角色编码，代码里引用它，如 admin',
  `name`        varchar(50)  NOT NULL COMMENT '角色名称，如 管理员',
  `description` varchar(200) DEFAULT NULL COMMENT '说明',
  `status`      tinyint      NOT NULL DEFAULT 1 COMMENT '1 启用，0 禁用',
  `create_time` datetime     DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_code` (`code`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '角色表';

-- 权限表
CREATE TABLE `permission` (
  `id`          bigint       NOT NULL AUTO_INCREMENT,
  `code`        varchar(100) NOT NULL COMMENT '权限编码，格式 模块:操作，如 staff:add',
  `name`        varchar(50)  NOT NULL COMMENT '权限名称',
  `type`        varchar(20)  NOT NULL DEFAULT 'api' COMMENT 'api 接口；预留 menu/button 供后续细化',
  `parent_id`   bigint       NOT NULL DEFAULT 0 COMMENT '父级 ID，0 为顶层，便于按模块树形展示',
  `sort`        int          NOT NULL DEFAULT 0 COMMENT '排序',
  `create_time` datetime     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_permission_code` (`code`),
  KEY `idx_parent_id` (`parent_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '权限表';

-- 用户角色关联表
CREATE TABLE `user_role` (
  `id`          bigint   NOT NULL AUTO_INCREMENT,
  `user_id`     bigint   NOT NULL COMMENT '用户 ID',
  `role_id`     bigint   NOT NULL COMMENT '角色 ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role` (`user_id`, `role_id`),
  KEY `idx_role_id` (`role_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户角色关联表';

-- 角色权限关联表
CREATE TABLE `role_permission` (
  `id`            bigint   NOT NULL AUTO_INCREMENT,
  `role_id`       bigint   NOT NULL COMMENT '角色 ID',
  `permission_id` bigint   NOT NULL COMMENT '权限 ID',
  `create_time`   datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_permission` (`role_id`, `permission_id`),
  KEY `idx_permission_id` (`permission_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '角色权限关联表';
