-- ============================================================================
-- 迁移脚本：点位库（survey_point）+ 航线（route / route_point）+ 任务引用航线
-- 适用：已建好 task 表的库（即已跑过 schema.sql 或先前的迁移）
-- 新库不用执行本脚本 —— schema.sql 里已含这些表与列
-- ============================================================================
-- 背景见 docs/地图接入方案.md 第八章「第二步：点位库 + 航线」：
--   任务的「在哪」原本只有一句话（task.route_info），管理员排活全靠脑子记
--   「上次那个点在哪」。这一步把点位与航线变成系统里的数据。
--
-- 三张表的分工：
--   survey_point  点位库      —— 可复用的点（巡检点/起降点/机巢），客户给的 Excel 也导到这里
--   route         航线模板    —— 一条有序的航线，可以被多条任务引用
--   route_point   航线上的点  —— 有序，每点可带动作（拍照/悬停）
--
-- ⚠️ 坐标一律存 **WGS84**（设备与 GPS 的原始坐标系）。
--    转 GCJ-02 只发生在"给地图出图"的那一瞬间，理由见设计文档 2.3 节 ——
--    库里存两种坐标迟早会不同步。所以这几张表的 lng/lat 都写清是 WGS84。
--
-- ⚠️ 本脚本**不幂等**（建表没有 IF NOT EXISTS 的干净写法），重复执行会报表已存在。
--    执行前先 SHOW TABLES LIKE 'survey_point' 看一眼。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 点位库
-- ---------------------------------------------------------------------------
CREATE TABLE `survey_point` (
  `id`             bigint        NOT NULL AUTO_INCREMENT,
  `code`           varchar(50)   DEFAULT NULL COMMENT '点位编号，客户给的（可空）',
  `name`           varchar(100)  NOT NULL COMMENT '点位名称',
  `lng`            decimal(10,6) NOT NULL COMMENT '经度 WGS84',
  `lat`            decimal(10,6) NOT NULL COMMENT '纬度 WGS84',
  `alt`            decimal(8,2)  DEFAULT NULL COMMENT '建议作业高度（米，相对起飞点）',
  `category`       varchar(20)   NOT NULL DEFAULT 'survey' COMMENT 'survey 巡检 / takeoff 起降 / dock 机巢 / other',
  `remark`         varchar(500)  DEFAULT NULL,
  `create_by`      bigint        DEFAULT NULL,
  `create_by_name` varchar(50)   DEFAULT NULL,
  `create_time`    datetime      DEFAULT CURRENT_TIMESTAMP,
  `update_time`    datetime      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  -- code 允许为空，而 MySQL 的唯一索引**把多个 NULL 视为不同** ——
  -- 所以「不填编号的点位」不会互相冲突。这个性质在 task_alert.device_code
  -- 那里也用过（见 docs/告警体系设计.md 4.4）
  UNIQUE KEY `uk_point_code` (`code`),
  KEY `idx_category` (`category`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '监测点/点位库（坐标存 WGS84）';

-- ---------------------------------------------------------------------------
-- 2. 航线模板
-- ---------------------------------------------------------------------------
CREATE TABLE `route` (
  `id`             bigint       NOT NULL AUTO_INCREMENT,
  `name`           varchar(100) NOT NULL COMMENT '航线名称',
  `description`    varchar(500) DEFAULT NULL,
  -- 下面两个是**冗余**字段：由点位算出来存着，列表页不用为了显示"几个点、多长"
  -- 去把每个航线的点都查一遍（那正是 N+1）。改点时一起更新
  `point_count`    int          NOT NULL DEFAULT 0 COMMENT '冗余：航点数',
  `distance_m`     int          DEFAULT NULL COMMENT '冗余：总航程（米）',
  `create_by`      bigint       DEFAULT NULL,
  `create_by_name` varchar(50)  DEFAULT NULL,
  `create_time`    datetime     DEFAULT CURRENT_TIMESTAMP,
  `update_time`    datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '航线（模板）';

-- ---------------------------------------------------------------------------
-- 3. 航线上的点（有序）
-- ---------------------------------------------------------------------------
CREATE TABLE `route_point` (
  `id`            bigint        NOT NULL AUTO_INCREMENT,
  `route_id`      bigint        NOT NULL,
  `seq`           int           NOT NULL COMMENT '顺序，从 1 开始',
  `lng`           decimal(10,6) NOT NULL COMMENT '经度 WGS84',
  `lat`           decimal(10,6) NOT NULL COMMENT '纬度 WGS84',
  `alt`           decimal(8,2)   DEFAULT NULL COMMENT '作业高度（米，相对起飞点）',
  `action`        varchar(20)   NOT NULL DEFAULT 'none' COMMENT 'none 不动作 / photo 拍照 / hover 悬停',
  `hover_seconds` int           DEFAULT NULL COMMENT '悬停秒数（action=hover 时用）',
  -- 这个点是从点位库挑来的吗？记下来，方便反查"这个点位被哪些航线用了"
  `point_id`      bigint        DEFAULT NULL COMMENT '来自点位库时记下它的 id',
  PRIMARY KEY (`id`),
  -- 按航线取点时永远带上 seq 排序，索引就照这个建
  KEY `idx_route_seq` (`route_id`, `seq`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '航线上的点（有序）';

-- ---------------------------------------------------------------------------
-- 4. 任务引用航线 + **快照**
-- ---------------------------------------------------------------------------
ALTER TABLE `task`
  -- 引用了哪条航线模板（可空：临时任务仍然可以直接填 route_info 那句话）
  ADD COLUMN `route_id`        bigint        DEFAULT NULL COMMENT '引用的航线模板 id，可空',
  -- ★ 快照：任务保存航线时**复制一份**，之后模板怎么改都不影响这条任务。
  -- 不这么做的话，改一次模板会让所有历史任务"当时飞的是哪条线"变成谜 ——
  -- 与「报告要留版本」是同一个价值观：改过必须看得出来（设计文档 6.5）
  ADD COLUMN `route_snapshot`  json          DEFAULT NULL COMMENT '航线的快照（选航线时复制一份）',
  -- 起降点单独存：它是安全相关的（迫降落在哪），而且常常不在航线的点上
  ADD COLUMN `takeoff_lng`     decimal(10,6) DEFAULT NULL COMMENT '起降点经度 WGS84',
  ADD COLUMN `takeoff_lat`     decimal(10,6) DEFAULT NULL COMMENT '起降点纬度 WGS84',
  ADD COLUMN `takeoff_name`    varchar(100)  DEFAULT NULL COMMENT '起降点名称';

-- ---------------------------------------------------------------------------
-- 5. 权限点：点位与航线
-- ---------------------------------------------------------------------------
-- 为什么不复用 task:list / task:add：机务（maintainer）本来就有 task:list
-- 但没有 task:add，而**点位维护正是机务的活儿**。复用 task:add 会把他挡在门外，
-- 于是"能不能管点位"变成"能不能建任务"的副作用 —— 那是两件不同的事。
INSERT IGNORE INTO `permission` (`code`, `name`, `type`) VALUES
  ('route:list', '点位与航线查看', 'api'),
  ('route:edit', '点位与航线维护', 'api');

-- 管理员与机务都给（机务管设备与点位；航线规划也是他们日常）
INSERT IGNORE INTO `role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id
FROM `role` r JOIN `permission` p
WHERE r.code IN ('admin', 'maintainer')
  AND p.code IN ('route:list', 'route:edit');

-- ---------------------------------------------------------------------------
-- 执行后自检
-- ---------------------------------------------------------------------------
-- 期望：三张表都在，且 task 上多出 route_id / route_snapshot / takeoff_* 五列
-- SHOW TABLES LIKE 'route%';
-- SHOW COLUMNS FROM `task` LIKE 'route%';
-- SHOW COLUMNS FROM `task` LIKE 'takeoff%';
--
-- 期望：admin 与 maintainer 各拿到两个新权限点（共 4 行）
-- SELECT r.code AS role_code, p.code AS perm FROM permission p
--   JOIN role_permission rp ON rp.permission_id = p.id
--   JOIN `role` r ON r.id = rp.role_id
--   WHERE p.code LIKE 'route:%' ORDER BY r.code, p.code;
