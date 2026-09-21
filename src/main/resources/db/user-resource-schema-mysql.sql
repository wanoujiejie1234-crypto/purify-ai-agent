-- 资料库：智能体在对话里产出的文件（MySQL 8）
--
-- 为什么单独建一张表，而不是从 chat_record.answer 里正则抓链接：
-- 那样只能拿到 URL 本身，分不清它是「生成的 PDF」还是「随手抓的网页」，
-- 也拿不到文件名、大小、来源这些用户真正要看的信息；纯写文件（没有链接的那种）
-- 更是一条都抓不到。产出的那一刻由工具自己记一笔，这些信息才是准的。
--
-- 谁写：ResourceRecorder，被 PDFGenerationTool / ResourceDownloadTool /
--       FileOperationTool 在产出成功后调用。工具拿到的用户身份来自 ToolContext，
--       和用户画像同一套机制（模型看不到、也传不了）。
-- 谁读：ResourceController，只读当前用户自己的行。
--
-- 由 Spring Boot 的 spring.sql.init 在启动时执行（见 application.yml）。
-- 因此语句必须幂等：重复启动不能报错，更不能清掉已有数据，所以带 IF NOT EXISTS。
CREATE TABLE IF NOT EXISTS user_resource
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         VARCHAR(64)  NOT NULL COMMENT '产出归属的用户。和 user_profile 一样是 user.id 的十进制字符串',
    conversation_id VARCHAR(64)  NULL COMMENT '在哪次会话里产出的。可以为空：工具被调用时上下文里未必有会话 id',
    kind            VARCHAR(32)  NOT NULL COMMENT '产出方式：PDF（生成的 PDF）/ DOWNLOAD（下载回来的资源）/ WRITTEN（写出的文本文件）',
    title           VARCHAR(255) NOT NULL COMMENT '展示名，取文件名。不存路径',
    url             VARCHAR(1000) NULL COMMENT '可以直接打开的地址。WRITTEN 为空——那类文件没有对外映射，只能走后端的下载接口',
    storage_key     VARCHAR(500) NULL COMMENT '本地文件名（WRITTEN / DOWNLOAD）或 OSS 的 objectKey（PDF）。下载和删除时用它定位文件',
    size_bytes      BIGINT       NULL COMMENT '文件大小。拿不到时为 NULL，界面显示「—」而不是 0',
    mime_type       VARCHAR(100) NULL COMMENT 'MIME 类型，仅作展示',
    source_url      VARCHAR(1000) NULL COMMENT '下载类资源的来源地址，用来回答「这份东西是从哪来的」',
    created_at      DATETIME(3)  NOT NULL COMMENT '产出时间',
    PRIMARY KEY (id),
    -- 列表永远是「我的，最近的在前」，索引按 (user_id, created_at) 建
    KEY idx_user_resource_user_created (user_id, created_at),
    -- 按会话回看这次的产出时用得上
    KEY idx_user_resource_conversation (conversation_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '资料库：智能体产出的文件与链接';
