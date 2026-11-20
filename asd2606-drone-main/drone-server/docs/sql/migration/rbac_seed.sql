-- ============================================================================
-- RBAC 初始数据：角色、权限点、角色权限分配，以及从 user.permission 迁移用户角色
-- 数据库：drone
-- ============================================================================
-- 执行前提：user 表仍保留 permission 字段（本文件不删它，删字段在 rbac_drop_legacy.sql）
--
-- 迁移前先确认没有空值，JOIN 迁移会漏掉 permission 为 NULL 的用户：
--   SELECT COUNT(*) FROM `user` WHERE permission IS NULL;
--   结果不为 0 时，这些用户不会被迁移，需要手工补 user_role。
-- ============================================================================

-- ---------- 1. 角色 ----------
INSERT INTO `role` (`code`, `name`, `description`) VALUES
  ('admin',      '管理员', '系统管理员，拥有全部权限'),
  ('pilot',      '飞手',   '执行飞行任务'),
  ('maintainer', '机务',   '设备维护与档案管理');

-- ---------- 2. 权限点 ----------
-- 菜单显隐不单独设权限点，由该模块的 list 权限推导
INSERT INTO `permission` (`code`, `name`, `type`) VALUES
  ('staff:list',    '员工查看',     'api'),
  ('staff:add',     '员工新增',     'api'),
  ('staff:edit',    '员工编辑',     'api'),
  ('staff:toggle',  '员工启用禁用', 'api'),
  ('device:list',   '设备查看',     'api'),
  ('device:detail', '设备详情',     'api'),
  ('device:add',    '设备新增',     'api'),
  ('device:edit',   '设备编辑',     'api');

-- ---------- 3. 角色权限分配 ----------
-- 管理员：全部权限
INSERT INTO `role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id
FROM `role` r
JOIN `permission` p
WHERE r.code = 'admin';

-- 机务：能看员工，设备档案全权
INSERT INTO `role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id
FROM `role` r
JOIN `permission` p
WHERE r.code = 'maintainer'
  AND p.code IN ('staff:list', 'device:list', 'device:detail', 'device:add', 'device:edit');

-- 飞手：只能看设备
INSERT INTO `role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id
FROM `role` r
JOIN `permission` p
WHERE r.code = 'pilot'
  AND p.code IN ('device:list', 'device:detail');

-- ---------- 4. 用户角色迁移 ----------
-- 把旧的 user.permission 当作角色 code，逐个映射过去
INSERT INTO `user_role` (`user_id`, `role_id`)
SELECT u.id, r.id
FROM `user` u
JOIN `role` r ON r.code = u.permission;

-- ---------- 校验 ----------
-- 期望：角色 3 条、权限 8 条、角色权限 8 + 5 + 2 = 15 条、用户角色与 user 行数一致（6 条）
-- SELECT
--   (SELECT COUNT(*) FROM `role`)            AS role_cnt,
--   (SELECT COUNT(*) FROM `permission`)      AS permission_cnt,
--   (SELECT COUNT(*) FROM `role_permission`) AS role_permission_cnt,
--   (SELECT COUNT(*) FROM `user_role`)       AS user_role_cnt,
--   (SELECT COUNT(*) FROM `user`)            AS user_cnt;
