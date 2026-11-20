-- ============================================================================
-- 迁移脚本：新增告警的权限点，并授权给管理员与机务
-- 适用：已建好 task_alert 表的库（即已跑过 migration/task_alert.sql）
-- 新库不用执行本脚本 —— data.sql 里已经包含这两个权限点
--
-- ⚠️ 更正（2026-09-24）：上面这句话原来是**错的**，data.sql 里并没有这两个权限点，
--    于是「新库不用跑本脚本」变成了一个陷阱：照 schema.sql + data.sql 建出来的库里
--    一条 alert:* 都没有，管理员打不开告警页。已经把它们补进 data.sql 的权限清单了
--    （admin 那条「拿全部权限」就写在权限插入之后，所以会自动包含它们）。
--    本节权当是给**已经上线的库**补权限的那一份。
-- ============================================================================
-- 背景见 docs/告警体系设计.md 第九章：
--   alert:list   告警查看 —— 看得到设备告警详情（设备编号、故障内容）
--   alert:handle 告警处理 —— 认领、标记已处理、标记误报
-- 两个都给管理员与机务。飞手**不给**：他只能看派给自己的任务上挂着的告警，
-- 而且不能处理（「自己报的问题自己关掉」等于没有监督，见设计文档第十二章）。
--
-- 为什么 admin 也要显式授一次（种子脚本里 admin 是「拿全部权限」的写法）：
--   那条「拿全部」只在新库初始化时跑过一次。这里是给**已经上线的库**补权限，
--   当时那条语句早就执行完了，不会因为新增权限而自动生效 ——
--   这是 RBAC 的正确行为（新权限默认不给），但上线时最容易漏，所以写清楚。
-- ============================================================================

-- 1. 权限点本身。INSERT IGNORE：permission.code 上有唯一键，重复执行不会报错
INSERT IGNORE INTO `permission` (`code`, `name`, `type`) VALUES
  ('alert:list',   '告警查看', 'api'),
  ('alert:handle', '告警处理', 'api');

-- 2. 授权给管理员
--    INSERT IGNORE：role_permission 上有 uk_role_permission(role_id, permission_id)，
--    所以重复执行不会产生重复授权（这一点很要紧 —— 重复授权会让「某角色有几个权限」算错）
INSERT IGNORE INTO `role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id
FROM `role` r
JOIN `permission` p
WHERE r.code = 'admin'
  AND p.code IN ('alert:list', 'alert:handle');

-- 3. 授权给机务（设备出问题时他们是要动手的那批人）
INSERT IGNORE INTO `role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id
FROM `role` r
JOIN `permission` p
WHERE r.code = 'maintainer'
  AND p.code IN ('alert:list', 'alert:handle');

-- ---------------------------------------------------------------------------
-- 执行后自检
-- ---------------------------------------------------------------------------
-- 期望：两条权限点，且 admin 与 maintainer 各被授了这两条
-- SELECT p.code, r.code AS role_code FROM permission p
--   JOIN role_permission rp ON rp.permission_id = p.id
--   JOIN `role` r ON r.id = rp.role_id
--   WHERE p.code LIKE 'alert:%' ORDER BY p.code, r.code;
