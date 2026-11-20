-- ============================================================================
-- 迁移脚本：task_attachment 补列（取证元信息 + 告警归属）
-- 适用：已建好 task_attachment 表的库（新库不用执行，schema.sql 里已含这些列）
-- ============================================================================
-- 背景：附件与存储这一层原本是「报告线」的东西，因为飞手端要能在
-- **现场异常上报时带一张照片**，被提前到了告警线第五步。设计与全部理由见
-- docs/任务收尾与执行报告.md 第七章（本脚本只落地其中的一部分，见文末自检）。
--
-- ⚠️ 本脚本**不幂等**（DDL 加列没有 IF NOT EXISTS），重复执行会报
--    #1060 Duplicate column name。这是加列的常态 —— 执行前先看一眼
--    SHOW COLUMNS FROM `task_attachment`，确认这几列还不存在。
--
-- alert_id 为什么加在这张表上、而不是新建一张 alert_attachment：
--   「文件怎么存、怎么校验、怎么下载」三件事，挂任务的附件和挂告警的附件
--   一模一样，只有「谁引用它」不同。分两张表就要写两套 DAO、两套上传校验、
--   两套下载鉴权，而它们的差别只有一个字段。
--
-- task_id 为什么仍然 NOT NULL：
--   本批的上报一定发生在某条任务的上下文里（飞手先选任务再上报），
--   所以每张附件都有归属任务；alert_id 是**额外的**归属，可以为空
--   （报告线的照片就只挂任务、不挂告警）。
-- ============================================================================

ALTER TABLE `task_attachment`
    -- 归属到某条告警。NULL = 只挂任务（报告线的照片与飞行日志）
    ADD COLUMN `alert_id`    bigint       DEFAULT NULL COMMENT '所属告警，NULL 表示只挂任务（报告线用）',
    -- 完整性 + 去重 + 将来迁对象存储时的对账依据
    ADD COLUMN `sha256`      char(64)     DEFAULT NULL COMMENT '文件内容的 SHA-256（十六进制小写）',
    -- 光有扩展名不够可靠（file_type 是业务分类，不是 MIME）；下载时要设正确的 Content-Type
    ADD COLUMN `mime_type`   varchar(100) DEFAULT NULL COMMENT 'MIME 类型，如 image/jpeg',
    -- local / oss：迁移期两种并存，靠它决定去哪个存储读
    ADD COLUMN `storage`     varchar(20)  NOT NULL DEFAULT 'local' COMMENT '存储位置：local 本地磁盘 / oss 对象存储',
    -- 存 key 不存 URL —— URL 会变、会过期（预签名），key 才是稳定的定位
    ADD COLUMN `storage_key` varchar(500) DEFAULT NULL COMMENT '存储内的 key（相对路径或对象 key）',
    -- 展示顺序：报告里照片要有次序，不是按上传时间乱排
    ADD COLUMN `sort_no`     int          NOT NULL DEFAULT 0 COMMENT '展示顺序';

-- 按告警查附件是列表页的常规动作（告警详情要显示飞手拍的照片），
-- 没有索引会全表扫 —— 而这张表只会越来越大
ALTER TABLE `task_attachment`
    ADD INDEX `idx_alert_id` (`alert_id`);

-- ---------------------------------------------------------------------------
-- 执行后自检
-- ---------------------------------------------------------------------------
-- 期望：多出 alert_id / sha256 / mime_type / storage / storage_key / sort_no 六列，
--       且 storage 的默认值是 local
-- SHOW COLUMNS FROM `task_attachment`;
--
-- 期望：老数据都能落到「本地存储」这一侧，不会因为新增列丢了已有文件
-- SELECT storage, COUNT(*) FROM `task_attachment` GROUP BY storage;
