-- ============================================================================
-- 无人机管理平台 · 完整表结构
-- 数据库：drone
-- ============================================================================
-- 本文件是「最终状态」的完整建表脚本，新环境按下面顺序执行即可：
--     ① schema.sql    ← 本文件：建全部表
--     ② data.sql      ← 初始化测试数据（用户、角色、权限、设备）
--
-- 包含 12 张表：
--     user              用户        ┐
--     role              角色        │
--     permission        权限点      ├─ 权限体系（RBAC）
--     user_role         用户↔角色   │
--     role_permission   角色↔权限   ┘
--     drone             设备档案     ← 业务表：设备管理
--     task              任务主表     ┐
--     task_log          状态流水     │
--     task_report       任务报告     ├─ 业务表：任务管理
--     task_attachment   任务附件     │
--     task_evidence     执行证据     │  ← 设备上报的遥测等客观依据
--     task_alert        告警         ┘  ← 哪里不对劲、需要人看一眼（设计见 docs/告警体系设计.md）
--
-- 三件必须知道的事：
--   1. `role` 是 MySQL 8.0 的关键字，表名必须带反引号，否则报语法错误。
--   2. 关联表刻意不加外键约束（靠应用层保证），避免批量导数据、
--      后续分库分表时被约束卡住。
--   3. user 表上原有的 permission 单值字段已废弃 —— 一人一个角色的模型表达不了
--      「既是机务又兼管理员」，改由 user_role 表达多对多。演进过程见 migration/ 目录。
-- ============================================================================

-- 先删关联表，再删主表，否则可能被外键依赖挡住（虽然本项目没建外键，保持习惯）
DROP TABLE IF EXISTS `user_role`;
DROP TABLE IF EXISTS `role_permission`;
DROP TABLE IF EXISTS `role`;
DROP TABLE IF EXISTS `permission`;
DROP TABLE IF EXISTS `user`;
DROP TABLE IF EXISTS `drone`;
DROP TABLE IF EXISTS `task_alert`;
DROP TABLE IF EXISTS `task_evidence`;
DROP TABLE IF EXISTS `no_fly_zone`;
DROP TABLE IF EXISTS `task_checkin`;
DROP TABLE IF EXISTS `task_dispatch_block`;
DROP TABLE IF EXISTS `stat_daily`;
DROP TABLE IF EXISTS `route_point`;
DROP TABLE IF EXISTS `route`;
DROP TABLE IF EXISTS `survey_point`;
DROP TABLE IF EXISTS `task_attachment`;
DROP TABLE IF EXISTS `task_report`;
DROP TABLE IF EXISTS `task_log`;
DROP TABLE IF EXISTS `task`;

-- ---------------------------------------------------------------------------
-- 用户表
-- ---------------------------------------------------------------------------
CREATE TABLE `user` (
  `id`          bigint       NOT NULL AUTO_INCREMENT,
  `username`    varchar(50)  NOT NULL COMMENT '登录账号，实际存放手机号',
  `name`        varchar(50)  DEFAULT NULL COMMENT '真实姓名',
  `password`    varchar(100) NOT NULL COMMENT '密码，当前为明文，生产环境需改为哈希',
  `email`       varchar(100) DEFAULT NULL,
  `phone`       varchar(20)  DEFAULT NULL,
  `age`         int          DEFAULT NULL,
  `gender`      varchar(10)  DEFAULT NULL,
  `status`      varchar(20)  NOT NULL DEFAULT 'active' COMMENT '账号状态，"0" 视为禁用',
  `state`       varchar(20)  NOT NULL DEFAULT 'idle' COMMENT '在线状态：idle 空闲 / busy 作业中 / grounded 已停飞。派发任务时联动改成 busy，撤回后改回 idle',
  `create_time` datetime     DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户表';

-- ---------------------------------------------------------------------------
-- 角色表
-- ---------------------------------------------------------------------------
CREATE TABLE `role` (
  `id`          bigint       NOT NULL AUTO_INCREMENT,
  `code`        varchar(50)  NOT NULL COMMENT '角色编码，代码里引用它，如 admin',
  `name`        varchar(50)  NOT NULL COMMENT '角色名称，如 管理员',
  `description` varchar(200) DEFAULT NULL COMMENT '说明',
  `status`      tinyint      NOT NULL DEFAULT 1 COMMENT '1 启用，0 禁用；禁用后其权限自动失效',
  `create_time` datetime     DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_code` (`code`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '角色表';

-- ---------------------------------------------------------------------------
-- 权限表
-- ---------------------------------------------------------------------------
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

-- ---------------------------------------------------------------------------
-- 用户角色关联表
-- ---------------------------------------------------------------------------
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

-- ---------------------------------------------------------------------------
-- 角色权限关联表
-- ---------------------------------------------------------------------------
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

-- ---------------------------------------------------------------------------
-- 设备表
-- ---------------------------------------------------------------------------
-- 命名说明：业务上叫「设备管理」，库里这张表叫 drone（无人机）。
-- 是历史命名，两者指的是同一批数据，接口路径用 /device/**。
--
-- 两个字段类型上的历史遗留（暂时保持原样，改类型要动已有数据）：
--   * flight_count / flight_hours 存的是 varchar 而不是数字类型，
--     严格说应该用 int / decimal，否则没法直接做 SUM 之类的聚合。
--   * status 用的是英文编码（idle/assigned/flying/...），中文展示在前端与导出时转换。
CREATE TABLE `drone` (
  `id`           bigint       NOT NULL AUTO_INCREMENT,
  `code`         varchar(50)  NOT NULL COMMENT '设备编号，业务主键，如 UAV-001',
  `name`         varchar(100) NOT NULL COMMENT '设备名称',
  `model`        varchar(100) DEFAULT NULL COMMENT '型号',
  `type`         varchar(50)  DEFAULT NULL COMMENT '类型：multi-rotor 多旋翼 / fixed-wing 固定翼',
  `purchase_date` date        DEFAULT NULL COMMENT '采购日期',
  `location`     varchar(200) DEFAULT NULL COMMENT '当前位置',
  `status`       varchar(20)  NOT NULL DEFAULT 'idle' COMMENT '工作状态：idle 待命 / assigned 已派出（派了任务未起飞）/ flying 飞行中 / charging 充电中 / offline 离线 / maintenance 维护中',
  `flight_count` varchar(20)  DEFAULT '0' COMMENT '累计飞行次数',
  `flight_hours` varchar(20)  DEFAULT '0' COMMENT '累计飞行时长（小时）',
  `create_time`  datetime     DEFAULT CURRENT_TIMESTAMP,
  `update_time`  datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '设备（无人机）表';

-- ===========================================================================
-- 任务管理（设计说明见 docs/任务业务设计.md）
-- ===========================================================================
-- 四张表的分工：
--     task             一件事的「当前状态」，每一行就是一个任务
--     task_log         这件事「怎么一步步变过来的」，每流转一次写一行
--     task_report      飞手干完之后的交代，一个任务最多一份（uk_task_id）
--     task_attachment  照片 / 视频 / 飞行日志等文件清单
--
-- 设计上的两个共同选择：
--   1. 状态字段一律用英文编码（pending/dispatched/...），中文展示放在前端转换。
--      这样加语言、改文案都不用动数据。
--   2. 主表冗余了 drone_code / pilot_name / create_by_name 这类「名字」字段。
--      写的时候多存一次，读列表页时就不用 join 三张表 —— 用一点存储换查询简单。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 任务主表
-- ---------------------------------------------------------------------------
-- status 的取值与流转规则（合法路径见 docs/任务业务设计.md）：
--     pending     待派发  ← 创建后的初始状态
--     dispatched  已派发  ← 指派了飞手和设备
--     executing   执行中
--     finished    已完成
--     failed      已失败
--     cancelled   已取消
--
-- pilot_id / drone_id 在创建时是 NULL，等派发环节才写入 ——
-- 「创建」只回答要做什么，「派发」才回答谁来做、用哪台设备。
CREATE TABLE `task` (
  `id`               bigint       NOT NULL AUTO_INCREMENT,
  `task_no`          varchar(32)  NOT NULL COMMENT '任务编号，格式 TK-yyyyMMdd-序号，如 TK-20260918-001',
  `task_name`        varchar(100) NOT NULL COMMENT '任务名称',
  `priority`         varchar(10)  NOT NULL DEFAULT 'P2' COMMENT '优先级：P0 紧急 / P1 高 / P2 中 / P3 低',
  `status`           varchar(20)  NOT NULL DEFAULT 'pending' COMMENT '状态，见上方流转说明',
  `plan_start_time`  datetime     DEFAULT NULL COMMENT '计划开始时间',
  `plan_end_time`    datetime     DEFAULT NULL COMMENT '计划结束时间',
  `actual_start_time` datetime    DEFAULT NULL COMMENT '实际开始时间，执行时写入',
  `actual_end_time`  datetime     DEFAULT NULL COMMENT '实际结束时间',
  -- 执行依据（与 status 正交的第二个维度，设计见 docs/任务业务设计.md 第十章）：
  --   status 回答「业务走到哪一步」，evidence_state 回答「这一步凭什么这么说」。
  --   所以「执行中 + 仅飞手声明」是一个合法组合，意思是"飞手说他在飞，但没东西能证明"。
  `evidence_state`   varchar(20)  NOT NULL DEFAULT 'none' COMMENT '执行依据：none 无 / declared 仅飞手声明 / confirmed 已证实 / stale 声明已过期',
  `evidence_type`    varchar(20)  DEFAULT NULL COMMENT '最新一条支撑证据的类型：telemetry 遥测 / flight_log 飞行日志 / witness 第二人确认 / manual 自行声明',
  `evidence_at`      datetime     DEFAULT NULL COMMENT '最新一条证据的时刻',
  `drone_id`         bigint       DEFAULT NULL COMMENT '指派的设备 ID，派发时写入',
  `drone_code`       varchar(50)  DEFAULT NULL COMMENT '设备编号冗余，便于列表直接展示',
  `pilot_id`         bigint       DEFAULT NULL COMMENT '指派的飞手 ID，派发时写入',
  `pilot_name`       varchar(50)  DEFAULT NULL COMMENT '飞手姓名冗余',
  `route_info`       text         COMMENT '任务内容 / 航线描述',
  `description`      text         COMMENT '补充说明',
  `create_by`        bigint       DEFAULT NULL COMMENT '创建人 ID，取自登录态',
  `create_by_name`   varchar(50)  DEFAULT NULL COMMENT '创建人姓名冗余',
  `create_time`      datetime     DEFAULT CURRENT_TIMESTAMP,
  `update_time`      datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  -- 编号唯一：并发创建时靠这个索引挡住重复编号，Service 捕获冲突后重取一次
  UNIQUE KEY `uk_task_no` (`task_no`),
  KEY `idx_status` (`status`),
  KEY `idx_pilot_id` (`pilot_id`),
  KEY `idx_drone_id` (`drone_id`),
  KEY `idx_plan_start_time` (`plan_start_time`),
  -- 统计用（见 docs/业务分析方案.md 第零步）：今日新增与趋势走 create_time，
  -- 超期走 (status, plan_end_time) —— 两个条件各自不同，只能各建一条
  KEY `idx_create_time` (`create_time`),
  KEY `idx_status_plan_end` (`status`, `plan_end_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '任务主表';

-- ---------------------------------------------------------------------------
-- 任务状态流转日志
-- ---------------------------------------------------------------------------
-- 为什么不直接在主表加个「最后修改人」了事？
-- 因为任务详情页要画一条时间轴：「谁在什么时候把状态从 A 改成了 B，附言是什么」。
-- 主表的 update_time 只能告诉你最后一次，中间过程全丢了。
--
-- 这张表只插不改不删（append-only），是排查「任务卡住了」最有用的一张表。
CREATE TABLE `task_log` (
  `id`            bigint       NOT NULL AUTO_INCREMENT,
  `task_id`       bigint       NOT NULL COMMENT '所属任务',
  `action`        varchar(30)  NOT NULL COMMENT '动作：create/dispatch/withdraw/start/finish/fail/cancel',
  `from_status`   varchar(20)  DEFAULT NULL COMMENT '变更前状态，创建时为 NULL',
  `to_status`     varchar(20)  DEFAULT NULL COMMENT '变更后状态',
  `operator_id`   bigint       DEFAULT NULL COMMENT '操作人 ID',
  `operator_name` varchar(50)  DEFAULT NULL COMMENT '操作人姓名冗余',
  `operator_role` varchar(50)  DEFAULT NULL COMMENT '操作时的角色，便于回溯是谁做的决定',
  `note`          varchar(500) DEFAULT NULL COMMENT '附言：失败原因、取消理由等',
  `create_time`   datetime     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_task_id` (`task_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '任务状态流转日志';

-- ---------------------------------------------------------------------------
-- 任务报告
-- ---------------------------------------------------------------------------
-- 与 task 是 1:1：一个任务最多交一份报告，靠 uk_task_id 保证。
-- 报告不落主表，是因为它字段多且大部分任务在「还没执行完」时根本没这份数据，
-- 塞进主表会让主表一半的列长期为空。
-- ---------------------------------------------------------------------------
-- 点位库 / 航线（设计见 docs/地图接入方案.md 第八章第二步）
--   坐标一律存 **WGS84**；转 GCJ-02 只发生在给地图出图的那一刻
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
  -- code 可空，而唯一索引把多个 NULL 视为不同 —— 不填编号的点位不会互相冲突
  UNIQUE KEY `uk_point_code` (`code`),
  KEY `idx_category` (`category`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '监测点/点位库（坐标存 WGS84）';

CREATE TABLE `route` (
  `id`             bigint       NOT NULL AUTO_INCREMENT,
  `name`           varchar(100) NOT NULL COMMENT '航线名称',
  `description`    varchar(500) DEFAULT NULL,
  `point_count`    int          NOT NULL DEFAULT 0 COMMENT '冗余：航点数（避免列表页 N+1）',
  `distance_m`     int          DEFAULT NULL COMMENT '冗余：总航程（米）',
  `create_by`      bigint       DEFAULT NULL,
  `create_by_name` varchar(50)  DEFAULT NULL,
  `create_time`    datetime     DEFAULT CURRENT_TIMESTAMP,
  `update_time`    datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '航线（模板）';

CREATE TABLE `route_point` (
  `id`            bigint        NOT NULL AUTO_INCREMENT,
  `route_id`      bigint        NOT NULL,
  `seq`           int           NOT NULL COMMENT '顺序，从 1 开始',
  `lng`           decimal(10,6) NOT NULL COMMENT '经度 WGS84',
  `lat`           decimal(10,6) NOT NULL COMMENT '纬度 WGS84',
  `alt`           decimal(8,2)  DEFAULT NULL COMMENT '作业高度（米，相对起飞点）',
  `action`        varchar(20)   NOT NULL DEFAULT 'none' COMMENT 'none 不动作 / photo 拍照 / hover 悬停',
  `hover_seconds` int           DEFAULT NULL COMMENT '悬停秒数（action=hover 时用）',
  `point_id`      bigint        DEFAULT NULL COMMENT '来自点位库时记下它的 id',
  PRIMARY KEY (`id`),
  KEY `idx_route_seq` (`route_id`, `seq`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '航线上的点（有序）';

-- ---------------------------------------------------------------------------
-- 禁飞区（静态校验用，见 docs/地图接入方案.md 6.3 / 第八章第三步）
--   几何用 JSON 存、不引入空间列的理由见迁移脚本 docs/sql/migration/no_fly_zone.sql
-- ---------------------------------------------------------------------------
CREATE TABLE `no_fly_zone` (
  `id`             bigint       NOT NULL AUTO_INCREMENT,
  `name`           varchar(100) NOT NULL COMMENT '区域名称，如「某机场净空区」',
  `source`         varchar(20)  NOT NULL DEFAULT 'custom' COMMENT 'official 官方空域 / custom 自建',
  `shape`          varchar(20)  NOT NULL DEFAULT 'polygon' COMMENT 'polygon 多边形 / circle 圆',
  `geometry`       json         NOT NULL COMMENT 'polygon: [[lng,lat],...]；circle: {lng,lat,radius}（WGS84）',
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
  KEY `idx_enabled` (`enabled`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '禁飞区（几何存 WGS84）';

-- ---------------------------------------------------------------------------
-- 待派发任务的归因点（见 docs/业务分析方案.md 2.7）
-- ---------------------------------------------------------------------------
CREATE TABLE `task_dispatch_block` (
  `id`               bigint      NOT NULL AUTO_INCREMENT,
  `task_id`          bigint      NOT NULL COMMENT '待派发任务',
  `checked_at`       datetime    NOT NULL COMMENT '这次采样是什么时候打的（本地时间）',
  `reason`           varchar(20) NOT NULL COMMENT 'blocked_pilot / blocked_drone / blocked_both / idle',
  -- 记下当时的候选数，是为了回答「还差多少」——只记一个「没人」，看不出是差 1 个人还是差 5 个
  `pilot_candidates` int         NOT NULL DEFAULT 0 COMMENT '当时可派的飞手数',
  `drone_candidates` int         NOT NULL DEFAULT 0 COMMENT '当时待命且不冲突的设备数',
  PRIMARY KEY (`id`),
  -- 查「某条任务被卡了多久、先后是什么原因」时按 (task_id, checked_at) 走
  KEY `idx_block_task` (`task_id`, `checked_at`),
  -- 分析按时间出图（「这周的积压主要是缺设备」）时按 checked_at 走
  KEY `idx_block_time` (`checked_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '待派发任务的归因点（定时采样，见 docs/业务分析方案.md 2.7）';

CREATE TABLE `task_report` (
  `id`            bigint       NOT NULL AUTO_INCREMENT,
  `task_id`       bigint       NOT NULL COMMENT '所属任务',
  `version`       int          NOT NULL DEFAULT 1 COMMENT '版本号，从 1 开始；每次修订 +1',
  `is_latest`     tinyint      NOT NULL DEFAULT 1 COMMENT '1 当前版本 / 0 历史版本',
  `result`        varchar(20)  NOT NULL COMMENT '结论：success 成功 / partial 部分完成 / failed 失败',
  `summary`       text         COMMENT '执行总结',
  `problem_log`   text         COMMENT '遇到的问题与处置方式',
  `flight_hours`  decimal(6,2) DEFAULT NULL COMMENT '本次飞行时长（小时）',
  `flight_count`  int          DEFAULT NULL COMMENT '本次飞行架次',
  `reporter_id`   bigint       DEFAULT NULL COMMENT '上报人 ID',
  `reporter_name` varchar(50)  DEFAULT NULL COMMENT '上报人姓名冗余',
  `create_time`   datetime     DEFAULT CURRENT_TIMESTAMP,
  `update_time`   datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  -- 一个任务可以有多份报告（多版本），但同一版本号不能重复。
  -- 为什么允许改：报告是**交付物**（交客户、作结算依据），飞手笔误、
  -- 管理员要求补充、客户提异议都会导致要改 —— 原地覆盖的话
  -- 「改了什么、谁改的、什么时候改的」一个都答不上来。
  -- 详见 docs/任务收尾与执行报告.md 第六章
  UNIQUE KEY `uk_task_version` (`task_id`, `version`),
  KEY `idx_task_latest` (`task_id`, `is_latest`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '任务报告（多版本，is_latest 标出当前版本）';

-- ---------------------------------------------------------------------------
-- 任务附件
-- ---------------------------------------------------------------------------
-- 独立成表而不是往任务里塞一列，因为一个任务可能传多张照片、多段视频。
-- 这里只存文件的元信息（名字、路径、大小），文件本体放在磁盘或对象存储上；
-- 数据库存路径、不存二进制，是这类需求的标准做法。
CREATE TABLE `task_attachment` (
  `id`             bigint       NOT NULL AUTO_INCREMENT,
  `task_id`        bigint       NOT NULL COMMENT '所属任务',
  `alert_id`       bigint       DEFAULT NULL COMMENT '所属告警，NULL 表示只挂任务（报告线用）。见 docs/告警体系设计.md 第十四章第五步',
  `file_name`      varchar(200) NOT NULL COMMENT '原始文件名（只用于展示，绝不参与拼路径）',
  `file_path`      varchar(500) NOT NULL COMMENT '本地存储时代的相对路径',
  `storage`        varchar(20)  NOT NULL DEFAULT 'local' COMMENT '存储位置：local 本地磁盘 / oss 对象存储',
  `storage_key`    varchar(500) DEFAULT NULL COMMENT '存储内的 key（相对路径或对象 key）。存 key 不存 URL —— URL 会变、会过期',
  `sha256`         char(64)     DEFAULT NULL COMMENT '文件内容的 SHA-256（十六进制小写）',
  `mime_type`      varchar(100) DEFAULT NULL COMMENT 'MIME 类型，如 image/jpeg',
  `file_type`      varchar(50)  DEFAULT NULL COMMENT '类型：image 照片 / video 视频 / log 飞行日志 / other',
  `file_size`      bigint       DEFAULT NULL COMMENT '文件大小（字节）',
  `sort_no`        int          NOT NULL DEFAULT 0 COMMENT '展示顺序',
  `description`    varchar(500) DEFAULT NULL COMMENT '说明',
  `upload_by`      bigint       DEFAULT NULL COMMENT '上传人 ID',
  `upload_by_name` varchar(50)  DEFAULT NULL COMMENT '上传人姓名冗余',
  `create_time`    datetime     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_task_id` (`task_id`),
  KEY `idx_alert_id` (`alert_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '任务附件';

-- ---------------------------------------------------------------------------
-- 任务执行证据
-- ---------------------------------------------------------------------------
-- 解决的问题：任务状态里的「执行中」过去只是飞手点出来的一句话，
-- 没有任何客观依据。这张表存的就是「依据本身」——
-- 设备上报的遥测、飞行结束后的日志、旁人的确认等等。
--
-- 它和 task_log 是同一个思路：事件流而非单个字段。
-- 一次飞行会有多段证据（起飞遥测、途中心跳、结束日志），
-- 只留最新一条会丢掉过程，所以一条条追加、不做物理删除。
--
-- 四个刻意的设计：
--   * task_id 允许为空：设备上报时可能并没有正在执行的任务（试飞、维护），
--     但那条遥测仍是客观事实，值得存下来，只是不参与任务的证据判定。
--   * 唯一键含 seq：设备重传（或 QoS 1 重复投递）时 seq 与 observed_at 都不变，
--     唯一键正好挡住；而同一秒内两条真正不同的数据 seq 不同，都能存下。
--     若只用 observed_at 当键，会把后一条误判成重复丢掉。
--   * observed_at 与 reported_at 分开：前者是设备说的时刻，后者是我们收到的时刻。
--     设备时钟可能不准，日志还可能是事后补传的，混成一个字段就判断不了新鲜度。
--   * valid 而不是删行：证据事后可能被推翻，置 0 保留痕迹便于追查。
CREATE TABLE `task_evidence` (
  `id`          bigint      NOT NULL AUTO_INCREMENT,
  `task_id`     bigint      DEFAULT NULL COMMENT '所属任务，为空表示这条数据没有关联到任何任务',
  `device_code` varchar(50) NOT NULL COMMENT '设备编号，对应 drone.code',
  `type`        varchar(20) NOT NULL COMMENT '证据类型：telemetry 实时遥测 / flight_log 飞行日志 / witness 第二人确认 / manual 自行声明',
  `source`      varchar(50) DEFAULT NULL COMMENT '来源：device 设备 / app 飞手端 / operator 人工，便于事后追责',
  `attach_id`   bigint      DEFAULT NULL COMMENT '证据是文件时指向 task_attachment',
  `payload`     text        COMMENT '证据内容，存 JSON 文本（用 text 而不是 json 类型：后者要配 TypeHandler 才能干净映射，这个阶段不值得）',
  `seq`         bigint      DEFAULT NULL COMMENT '设备自增序号，参与唯一键去重',
  `observed_at` datetime    NOT NULL COMMENT '证据描述的那个时刻（不等于上报时刻）',
  `reported_at` datetime    DEFAULT CURRENT_TIMESTAMP COMMENT '我们收到它的时刻',
  `valid`       tinyint     NOT NULL DEFAULT 1 COMMENT '1 有效 / 0 已被推翻',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_evidence_dedup` (`device_code`, `type`, `observed_at`, `seq`),
  KEY `idx_task_id` (`task_id`),
  KEY `idx_device_time` (`device_code`, `observed_at`),
  -- 统计用：按时间计数/趋势。没有它时 MySQL 只能对 (device_code, observed_at)
  -- 做「跳扫」（Using index for skip scan）—— 数据量小时看不出，上千万行差一个量级
  KEY `idx_observed_at` (`observed_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '任务执行证据';

-- ---------------------------------------------------------------------------
-- 任务/设备告警
-- ---------------------------------------------------------------------------
-- 解决的问题：设备发一条「电量严重不足」，过去只会被当成又一条遥测证据归档，
-- 没有任何人被告知。这张表就是告警的落脚点（设计见 docs/告警体系设计.md）。
--
-- 为什么和 task_evidence 分开，而不是在它上面加一个 type='alert'：
--   * 告警有**可变状态**（待处理 / 处理中 / 已处理 / 误报），
--     而 task_evidence 是只追加的流水 —— 一个变量的状态不能塞进一份只追加的流水。
--   * 告警的归属对象可能是**设备**而不是任务（机库自检报电池温度高，
--     不属于任何任务，但必须记下来、必须有人管），所以 task_id 允许为空。
--   * 两者的查询模式不同：证据按任务查（时间序），告警按状态+级别查。
--
-- 去重键**刻意不带 seq**（与 task_evidence 正好相反）：
--   证据是「采样」——同一秒内两条遥测是两条真实数据，必须都留下；
--   告警是「状态判定」——同一秒内同一种问题就是一个问题，重复的要去掉。
-- ---------------------------------------------------------------------------
-- 到点打卡（见 docs/地图接入方案.md 7.2；迁移脚本 docs/sql/migration/task_checkin.sql）
-- ---------------------------------------------------------------------------
CREATE TABLE `task_checkin` (
  `id`            bigint        NOT NULL AUTO_INCREMENT,
  `task_id`       bigint        NOT NULL COMMENT '所属任务',
  `seq`           int           NOT NULL COMMENT '目标航点在航线里的序号（从 1 开始）',
  -- 目标点位也存下来（而不是每次去快照里翻）：
  -- 快照是当时的航线，而这条记录要能独立回答「他当时是奔哪个点去的、离多远」
  `target_lng`    decimal(10,6) NOT NULL COMMENT '目标点位经度 WGS84',
  `target_lat`    decimal(10,6) NOT NULL COMMENT '目标点位纬度 WGS84',
  `lng`           decimal(10,6) NOT NULL COMMENT '打卡位置上经度 WGS84',
  `lat`           decimal(10,6) NOT NULL COMMENT '打卡位置上纬度 WGS84',
  `accuracy_m`    decimal(8,2)  DEFAULT NULL COMMENT '手机定位精度（米）—— 越大越不可信，展示时要带上',
  -- 偏差由**服务端**算，不信客户端传的数：客户端那个数只能用来「提示他确认一下」，
  -- 存进库里的必须是服务端拿目标点与打卡点自己算的
  `deviation_m`   int           NOT NULL COMMENT '离目标点位的距离（米，服务端算）',
  `observed_at`   datetime      NOT NULL COMMENT '打卡那一刻（客户端给的：离线补传时就是当时那一刻）',
  `reported_at`   datetime      DEFAULT CURRENT_TIMESTAMP COMMENT '我们收到它的时刻（补传时晚于 observed_at）',
  `reporter_id`   bigint        DEFAULT NULL,
  `reporter_name` varchar(50)   DEFAULT NULL,
  `remark`        varchar(200)  DEFAULT NULL COMMENT '飞手填的说明，如「点位被树挡住，在边上拍的」',
  `create_time`   datetime      DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  -- **补传去重**：离线时打卡先存本地、联网后重发，同一条可能来好几遍。
  -- 按「任务 + 航点 + 时刻」唯一 —— 这三样一样就是同一次打卡，重发直接忽略。
  -- （时刻取客户端给的那个，而不是我们收到的时刻：后者每次重发都会变，去重就失效了）
  UNIQUE KEY `uk_checkin_dedup` (`task_id`, `seq`, `observed_at`),
  KEY `idx_checkin_task` (`task_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '到点打卡（坐标存 WGS84，只算飞手自述）';

CREATE TABLE `task_alert` (
  `id`                    bigint       NOT NULL AUTO_INCREMENT,
  `task_id`               bigint       DEFAULT NULL COMMENT '所属任务；为空表示这次告警与任何任务无关（试飞、维护、机库自检）',
  `device_code`           varchar(50)  DEFAULT NULL COMMENT '设备编号；人工上报的告警可能不涉及设备',
  `alert_code`            varchar(50)  NOT NULL COMMENT '告警编码，如 bat.critical（取值见 docs/告警体系设计.md 第二章）',
  `severity`              varchar(10)  NOT NULL COMMENT 'info / warning / critical',
  `message`               varchar(500) DEFAULT NULL COMMENT '人可读的一句话，列表与推送直接用它，不用前端拼',
  `payload`               text         COMMENT '原始内容（报文原文或人填的表单），存 JSON 文本',
  `source`                varchar(20)  NOT NULL DEFAULT 'device' COMMENT '来源：device 设备 / app 飞手端 / operator 人工',
  `observed_at`           datetime     NOT NULL COMMENT '告警描述的那个时刻（设备说的或人填的）',
  `reported_at`           datetime     DEFAULT CURRENT_TIMESTAMP COMMENT '我们收到的时刻',
  `status`                varchar(20)  NOT NULL DEFAULT 'pending' COMMENT 'pending 待处理 / handling 处理中 / resolved 已处理 / ignored 误报',
  `handled_by`            bigint       DEFAULT NULL COMMENT '处理人 ID',
  `handled_by_name`       varchar(50)  DEFAULT NULL COMMENT '处理人姓名冗余',
  `handled_at`            datetime     DEFAULT NULL COMMENT '处理完成时刻',
  `handle_note`           varchar(500) DEFAULT NULL COMMENT '处置说明：做了什么、结论是什么',
  `pilot_action_required` tinyint      NOT NULL DEFAULT 0 COMMENT '1 表示这条告警需要飞手在现场动手（见设计文档第十二章）',
  `action_hint`           varchar(200) DEFAULT NULL COMMENT '给飞手看的动作提示，如「立即返航并就近降落」',
  PRIMARY KEY (`id`),
  -- device_code 允许为空，而 MySQL 的 UNIQUE 索引把多个 NULL 当成互不相同 ——
  --   所以人工上报的告警（device_code 为 NULL）不会被去重，
  --   这恰好是想要的：飞手一分钟内报两次「现场有干扰」是两次上报，不该合成一条。
  UNIQUE KEY `uk_alert_dedup` (`device_code`, `alert_code`, `observed_at`),
  KEY `idx_task_id` (`task_id`),
  KEY `idx_status_severity` (`status`, `severity`),
  KEY `idx_device_time` (`device_code`, `observed_at`),
  KEY `idx_status_reported` (`status`, `reported_at`),
  -- 统计用：告警趋势与 MTTR 都按 observed_at 开区间查
  KEY `idx_observed_at` (`observed_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '任务/设备告警';

-- ---------------------------------------------------------------------------
-- 预聚合日表（见 docs/业务分析方案.md 第三步 / 4.3 ③）
-- ---------------------------------------------------------------------------
-- 为什么需要它：`task_evidence` 是唯一会无限增长的表（1 台设备 1 条/秒），
-- 而「按天看趋势」这类查询要在几百万行上分组。实测（4.5）：
--   · 明细表上按天分组（近一年 197 万行）：0.58 s，读 197 万行
--   · 同一张表换成这张汇总表（3650 行）：    0.04 s，读 3650 行
--
-- 为什么是「窄表」（一天一指标一行），而不是每个指标一列：
--   加一个指标不用改表结构；代价是查询要带 metric 条件（走 idx_metric_date 就够快）。
--
-- ⚠️ 粒度靠 `dimension` 表达，而且**不能只存「天」**：
--   `COUNT(DISTINCT)` 不可加 —— 只存「这天 5 台、明天 5 台」加不出「这两天一共几台」。
--   存到 (天 × 设备) 这一层，「每天多少条」= SUM(value)、「每天多少台活跃」= COUNT(*)，
--   两个都能加出来。这一条是 4.5 实测后才定下来的。
CREATE TABLE `stat_daily` (
  `id`              bigint       NOT NULL AUTO_INCREMENT,
  `stat_date`       date         NOT NULL COMMENT '哪一天（按应用本地时间的那天，边界由 Java 传）',
  `metric`          varchar(50)  NOT NULL COMMENT '指标名，如 evidence_count',
  `dimension`       varchar(80)  NOT NULL DEFAULT '' COMMENT '维度取值（设备编号等）；空串表示「不分维度」',
  `value`           decimal(18,4) NOT NULL COMMENT '值；用 decimal 是为了同时装「条数」与「小时」两类指标',
  `caliber_version` int          NOT NULL DEFAULT 1 COMMENT '口径版本：口径改了要 +1 并整段重算，否则新旧数据混在一条趋势里会断层',
  `updated_at`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后一次算它的时刻（重算会刷新，便于排查「这天算过没有」）',
  PRIMARY KEY (`id`),
  -- 唯一键让「按日期重算」变成幂等的 upsert（重算同一天不会翻倍）
  UNIQUE KEY `uk_stat_daily` (`stat_date`, `metric`, `dimension`, `caliber_version`),
  -- 查询永远是「某个指标 + 一段日期」，所以按 (metric, stat_date) 建
  KEY `idx_metric_date` (`metric`, `stat_date`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '按天预聚合（业务分析第三步）';
