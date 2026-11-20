-- ============================================================================
-- 迁移脚本：禁飞区（no_fly_zone）
-- 适用：已建好其它表的库
-- 新库不用执行本脚本 —— schema.sql 里已含这张表与两个权限点
-- ============================================================================
-- 背景见 docs/地图接入方案.md 6.3 节与第八章「第三步：禁飞区（静态）」：
--   这一步做的是**事前的静态校验** —— 创建任务和派发任务时，
--   把「任务要飞的那些点」逐个拿去和禁飞区比，命中就拦下（软拦 + 留痕）。
--
--   ⚠️ 它**拦不住已经起飞的飞机**。事中（每一条遥测判定并告警）是第四步的事，
--      详见设计文档 6.3.3。这条边界必须写清楚，否则会有人以为系统能拦飞机。
--
-- ⚠️ 坐标一律存 **WGS84**（与设备、点位库、航线一致）。
--    管理员是在高德的地图上画的（GCJ-02），入库前转成 WGS84，
--    读出来给地图时再转回去 —— 见 CoordinateUtil 的两个方向。
--
-- ⚠️ 本脚本**不幂等**，重复执行会报表已存在。执行前先看一眼：
--    SHOW TABLES LIKE 'no_fly_zone';
-- ============================================================================

CREATE TABLE `no_fly_zone` (
  `id`             bigint       NOT NULL AUTO_INCREMENT,
  `name`           varchar(100) NOT NULL COMMENT '区域名称，如「某机场净空区」',
  `source`         varchar(20)  NOT NULL DEFAULT 'custom' COMMENT 'official 官方空域 / custom 自建',
  `shape`          varchar(20)  NOT NULL DEFAULT 'polygon' COMMENT 'polygon 多边形 / circle 圆',
  -- 几何用 JSON 存，不引入空间列（MySQL 的 SRID、环闭合、坐标顺序三个坑都要人记）：
  --   polygon: [[lng,lat], [lng,lat], ...]（不重复首点，闭合由读的一方自己补）
  --   circle : {"lng":..., "lat":..., "radius": 米}
  -- 顶点数只有几十个，判定是「取出来在 Java 里算」，不需要数据库索引
  `geometry`       json         NOT NULL COMMENT 'polygon: [[lng,lat],...]；circle: {lng,lat,radius}（WGS84）',
  -- 区域内限高：NULL = 完全禁飞；填了数字 = 限高区（只拦「高度已知且超过它」的点）
  `max_alt`        int          DEFAULT NULL COMMENT '区域内限高（米），NULL 表示完全禁飞',
  `effective_from` datetime     DEFAULT NULL COMMENT '生效开始，NULL 表示长期有效',
  `effective_to`   datetime     DEFAULT NULL COMMENT '生效结束，NULL 表示长期有效',
  `enabled`        tinyint      NOT NULL DEFAULT 1 COMMENT '1 启用 / 0 停用',
  `remark`         varchar(500) DEFAULT NULL,
  `create_by`      bigint       DEFAULT NULL,
  `create_by_name` varchar(50)  DEFAULT NULL,
  `create_time`    datetime     DEFAULT CURRENT_TIMESTAMP,
  `update_time`    datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  -- 校验时只取「启用的」区域（绝大多数查询都带这个条件）
  KEY `idx_enabled` (`enabled`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '禁飞区（几何存 WGS84）';

-- ---------------------------------------------------------------------------
-- 权限点：禁飞区
-- ---------------------------------------------------------------------------
-- 为什么又是两个独立权限点、不复用 route:list/edit：
-- 「维护点位与航线」和「划定禁飞区」是两件事 —— 前者是日常作业，
-- 后者是**改动一条安全控制线**。合成一个权限点，等于谁要能画航线就必须能改禁飞区。
--
-- 授予范围与 route:* 一致（管理员 + 机务）：机务是现场运行的负责人，
-- 也是唯一会接到「这块场地不许飞」通知的人。真要收紧成管理员专属，
-- 把 role_permission 里 maintainer 那两行删掉即可，代码不用动。
INSERT IGNORE INTO `permission` (`code`, `name`, `type`) VALUES
  ('zone:list', '禁飞区查看', 'api'),
  ('zone:edit', '禁飞区维护', 'api');

INSERT IGNORE INTO `role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id
FROM `role` r JOIN `permission` p
WHERE r.code IN ('admin', 'maintainer')
  AND p.code IN ('zone:list', 'zone:edit');

-- ---------------------------------------------------------------------------
-- 执行后自检
-- ---------------------------------------------------------------------------
-- 期望：表在，且两行权限点各授给了 admin 与 maintainer（共 4 行）
-- SELECT r.code AS role_code, p.code AS perm FROM permission p
--   JOIN role_permission rp ON rp.permission_id = p.id
--   JOIN `role` r ON r.id = rp.role_id
--   WHERE p.code LIKE 'zone:%' ORDER BY r.code, p.code;
