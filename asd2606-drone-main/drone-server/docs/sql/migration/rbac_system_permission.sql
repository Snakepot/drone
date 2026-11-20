-- ============================================================================
-- 权限管理功能所需的权限点
-- 数据库：drone
-- ============================================================================
-- 说明：权限点与接口一一对应，由代码/SQL 定义，管理界面只读展示。
-- 将来新增接口时，在这里补一条 permission 记录，并授予相应角色。
--
-- 执行前提：已执行过 rbac_schema.sql 与 rbac_seed.sql
-- 注意：本脚本可重复执行前需先确认没有重复插入，permission.code 上有唯一索引，
--       重复执行会报 Duplicate entry，属预期保护。
-- ============================================================================

INSERT INTO `permission` (`code`, `name`, `type`) VALUES
  ('system:rbac:list', '权限管理查看', 'api'),
  ('system:role:save', '角色维护',     'api'),
  ('system:role:grant', '角色授权',    'api'),
  ('system:user:grant', '员工授权',    'api');

-- 把 system:* 全部授予管理员。其他角色默认不给，需要时在权限管理界面里配。
INSERT INTO `role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id
FROM `role` r
JOIN `permission` p ON p.code LIKE 'system:%'
WHERE r.code = 'admin';

-- 校验：权限点应为 12 条，其中 admin 拥有 12 条
-- SELECT
--   (SELECT COUNT(*) FROM `permission`) AS perm_cnt,
--   (SELECT COUNT(*) FROM `role_permission` rp JOIN `role` r ON r.id = rp.role_id
--     WHERE r.code = 'admin') AS admin_perm_cnt;
