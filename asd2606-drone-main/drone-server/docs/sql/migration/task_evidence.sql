-- ============================================================================
-- 迁移脚本：给任务模块加上「执行证据」
-- 适用：已经建好 task / task_log / task_report / task_attachment 的库
-- 新库不用执行本脚本 —— schema.sql 里已经是最终状态
-- ============================================================================
-- 背景见 docs/无人机数据来源.md 与 docs/MQTT与设备接入.md：
--   「执行中」过去只是飞手点出来的一句话，没有任何客观依据。
--   加上证据这一维度后：
--     - task.evidence_state 记录「这一步有没有依据」（仅声明 / 已证实 / 已过期）
--     - task_evidence       记录「依据本身」（一条条遥测、日志、旁证）
--   状态机一个箭头都没改，只是多了一个正交的维度。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 证据流水表
-- ---------------------------------------------------------------------------
-- 几个刻意的设计：
--   * task_id 允许为空：设备上报时可能并没有正在执行的任务（试飞、维护），
--     但那条遥测仍然是客观事实，值得存下来，只是不参与任何任务的证据判定。
--   * 唯一键含 seq：设备重传（或 QoS 1 重复投递）时 seq 与 observed_at 都不变，
--     唯一键挡住重复；而同一秒内两条真正不同的数据 seq 不同，都能存下。
--     只用 observed_at 当键会把后一条误判成重复丢掉。
--   * observed_at 与 reported_at 必须分开：前者是设备说的时刻，
--     后者是我们收到的时刻。设备时钟可能不准、日志还可能是事后补传的。
--   * valid 而不是物理删除：证据事后可能被推翻（比如发现是伪造的），
--     置 0 保留痕迹，也便于追查。
CREATE TABLE IF NOT EXISTS `task_evidence` (
  `id`            bigint      NOT NULL AUTO_INCREMENT,
  `task_id`       bigint      DEFAULT NULL COMMENT '所属任务，为空表示这条数据没有关联到任何任务',
  `device_code`   varchar(50) NOT NULL COMMENT '设备编号，对应 drone.code',
  `type`          varchar(20) NOT NULL COMMENT '证据类型：telemetry 实时遥测 / flight_log 飞行日志 / witness 第二人确认 / manual 自行声明',
  `source`        varchar(50) DEFAULT NULL COMMENT '来源：device 设备 / app 飞手端 / operator 人工',
  `attach_id`     bigint      DEFAULT NULL COMMENT '证据是文件时指向 task_attachment',
  `payload`       text        COMMENT '证据内容，存 JSON 文本',
  `seq`           bigint      DEFAULT NULL COMMENT '设备自增序号，参与唯一键去重',
  `observed_at`   datetime    NOT NULL COMMENT '证据描述的那个时刻（不等于上报时刻）',
  `reported_at`   datetime    DEFAULT CURRENT_TIMESTAMP COMMENT '我们收到它的时刻',
  `valid`         tinyint     NOT NULL DEFAULT 1 COMMENT '1 有效 / 0 已被推翻',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_evidence_dedup` (`device_code`, `type`, `observed_at`, `seq`),
  KEY `idx_task_id` (`task_id`),
  KEY `idx_device_time` (`device_code`, `observed_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '任务执行证据';

-- ---------------------------------------------------------------------------
-- 2. task 表上的证据摘要字段
-- ---------------------------------------------------------------------------
-- 注意：建表的 IF NOT EXISTS 可以重复执行，**下面的 ALTER 不行** ——
-- 跑第二遍会报 Duplicate column name（列已存在），那是正常的，忽略即可。
-- 之所以不写成 IF NOT EXISTS 的形式，是因为 MySQL 8 的 ADD COLUMN 不支持它
-- （MariaDB 支持，但本项目的脚本要对 MySQL 8 也成立）。
--
-- 为什么在主表再存一份摘要：列表页要显示「执行中 · 仅声明 / 已证实」，
-- 每次都去 join 证据表取最新一条不划算。用一点冗余换查询简单，
-- 和主表冗余 pilot_name、drone_code 是同一个取舍。
ALTER TABLE `task`
  ADD COLUMN `evidence_state` varchar(20) NOT NULL DEFAULT 'none'
      COMMENT '执行依据：none 无 / declared 仅飞手声明 / confirmed 已证实 / stale 声明已过期' AFTER `actual_end_time`,
  ADD COLUMN `evidence_type`  varchar(20) DEFAULT NULL
      COMMENT '最新一条支撑证据的类型：telemetry / flight_log / witness / manual' AFTER `evidence_state`,
  ADD COLUMN `evidence_at`    datetime    DEFAULT NULL
      COMMENT '最新一条证据的时刻' AFTER `evidence_type`;

-- ---------------------------------------------------------------------------
-- 3. 执行后自检
-- ---------------------------------------------------------------------------
-- 期望：能看到 task_evidence 表，且 task 表多出三列
-- SHOW TABLES LIKE 'task%';
-- SHOW COLUMNS FROM `task` LIKE 'evidence%';
--
-- 存量数据的处理说明：已有的任务 evidence_state 一律是 none。
-- 其中「执行中」的那些，按新语义应该补成 declared（历史上它们本就是飞手
-- 点了「开始执行」产生的，没有任何依据）。要补的话执行：
-- UPDATE `task` SET evidence_state = 'declared' WHERE status = 'executing' AND evidence_state = 'none';
-- 本脚本不自动执行这一句 —— 改存量数据应当由人确认后手动做。
