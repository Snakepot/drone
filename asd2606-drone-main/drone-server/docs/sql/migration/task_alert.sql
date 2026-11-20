-- ============================================================================
-- 迁移脚本：新增告警表 task_alert
-- 适用：已经跑过 schema.sql（含 task_evidence）的库
-- 新库不用执行本脚本 —— schema.sql 里已经是最终状态
-- ============================================================================
-- 背景见 docs/告警体系设计.md：
--   过去订阅了 drone/+/event/+ 与 drone/+/status，却没有任何按类型分发的动作，
--   设备发一条「电量严重不足」只会被当成又一条遥测证据归档，没有人会被告知。
--   这张表就是告警的落脚点。
--
-- 为什么单独一张表、不往 task_evidence 上塞：
--   * 告警有**可变状态**（待处理 / 处理中 / 已处理 / 误报），
--     而 task_evidence 是只追加的流水 —— 一个变量的状态不能塞进一份只追加的流水。
--   * 告警的归属对象可能是**设备**而不是任务（机库自检报电池温度高，
--     不属于任何任务，但必须记下来、必须有人管），所以 task_id 允许为空。
--   * 两者的查询模式不同：证据按任务查（时间序），告警按状态+级别查。
-- ============================================================================

-- 建表可重复执行（IF NOT EXISTS），跑第二遍不会报错
CREATE TABLE IF NOT EXISTS `task_alert` (
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
  -- 去重键**刻意不带 seq**（与 task_evidence 不同）：
  --   证据是「采样」——同一秒内两条遥测是两条真实数据，必须都留下；
  --   告警是「状态判定」——同一秒内同一种问题就是一个问题，重复通知要去掉。
  -- device_code 允许为空，而 MySQL 的 UNIQUE 索引把多个 NULL 当成互不相同 ——
  --   所以人工上报的告警（device_code 为 NULL）**不会被去重**。
  --   这恰好是想要的：飞手一分钟内报两次「现场有干扰」是两次上报，不该合成一条。
  UNIQUE KEY `uk_alert_dedup` (`device_code`, `alert_code`, `observed_at`),
  KEY `idx_task_id` (`task_id`),
  -- 给「还有哪些没处理的 critical」用
  KEY `idx_status_severity` (`status`, `severity`),
  KEY `idx_device_time` (`device_code`, `observed_at`),
  -- 给 90 天清理任务用（条件是 status IN (...) AND reported_at < ?，见设计文档 13.3）
  KEY `idx_status_reported` (`status`, `reported_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '任务/设备告警';

-- ---------------------------------------------------------------------------
-- 执行后自检
-- ---------------------------------------------------------------------------
-- 期望：多出一张 task_alert，17 列、6 个索引
--   PRIMARY、uk_alert_dedup、idx_task_id、idx_status_severity、
--   idx_device_time、idx_status_reported
-- SHOW COLUMNS FROM `task_alert`;
-- SHOW INDEX FROM `task_alert`;
