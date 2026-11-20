-- ============================================================================
-- 新增「智能体对话」权限点，供 drone-agent 的 /chat 校验
-- 数据库：drone
-- ============================================================================
-- 背景：drone-agent 是独立服务，复用 drone-server 的 RBAC 权限码。
-- 它调 /internal/user/{id}/authorities 取权限，再核对目标接口声明的权限码，
-- 所以这里必须先把 agent:chat 这个码登记进 permission 表，并分配给角色。
--
-- 幂等：可重复执行。已存在则不重复插入。
-- 默认只授予管理员；确定要给飞手/机务用，再把对应角色加进下面的 IN 列表。
-- ============================================================================

-- ---------- 1. 权限点 ----------
INSERT INTO `permission` (`code`, `name`, `type`)
SELECT 'agent:chat', '智能体对话', 'api'
WHERE NOT EXISTS (SELECT 1 FROM `permission` WHERE `code` = 'agent:chat');

-- ---------- 2. 授予管理员 ----------
INSERT INTO `role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id
FROM `role` r
JOIN `permission` p ON p.code = 'agent:chat'
WHERE r.code IN ('admin')
  AND NOT EXISTS (
    SELECT 1 FROM `role_permission` rp
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- ---------- 校验 ----------
-- SELECT r.code AS role_code, p.code AS permission_code
-- FROM `role` r
-- JOIN `role_permission` rp ON rp.role_id = r.id
-- JOIN `permission` p ON p.id = rp.permission_id
-- WHERE p.code = 'agent:chat';
