-- 聊天记录表（MySQL 8）
--
-- 为什么已经有 SPRING_AI_CHAT_MEMORY 了还要单独建一张表：
-- 那两张表解决的是两个不同的问题，混用哪一个都会别扭。
--
--   SPRING_AI_CHAT_MEMORY  是「对话记忆」：模型下一轮要看到的东西。
--                          按消息存，一轮问答是两条（USER / ASSISTANT），
--                          而且它归 Spring AI 的仓储管——读回来时工具调用会被丢掉
--                          （见 AgentMemory 的注释），所以它只适合放纯文本的对话。
--
--   chat_record            是「聊天记录」：给人看的账本。
--                          一轮问答一行，问题和答复挨在一起，直接查就能看，
--                          还带着这一轮是怎么收尾的（智能体可能停下来问用户、也可能中止）。
--                          谁都不读它来喂模型，所以怎么存都不影响对话本身。
--
-- 两条链路写同一张表，用 scene 区分（轻语文本 / 轻语看图 / PurifyManus 智能体）。
-- 智能体那条链路的对话记忆仍然在进程内（AgentMemory）：它的 ReAct 历史里含工具调用，
-- 落库再读回来会断，这一点没有变；这里落的只是「用户问了什么、最后答了什么」。
--
-- 由 Spring Boot 的 spring.sql.init 在启动时执行（见 application.yml）。
-- 因此语句必须幂等：重复启动不能报错，更不能清掉已有记录，所以带 IF NOT EXISTS。
CREATE TABLE IF NOT EXISTS chat_record
(
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    conversation_id VARCHAR(64) NOT NULL COMMENT '会话 ID。比记忆表的 36 宽：chatId 是客户端传的，未必是 UUID',
    scene           VARCHAR(32) NOT NULL COMMENT '来自哪条链路：SLIM / SLIM_IMAGE / MANUS',
    question        TEXT        NOT NULL COMMENT '用户这一轮说的话',
    answer          TEXT        NULL COMMENT '这一轮的回应。智能体反问用户时，记的是它问的那句话',
    state           VARCHAR(32) NULL COMMENT '智能体这一轮的收尾状态（AgentState 的枚举名）：FINISHED / WAITING_FOR_USER / ABORTED / BLOCKED / ERROR。SlimApp 一律不填（轻语命中敏感词时流以异常收尾，那一轮整个不落库）',
    steps           INT         NULL COMMENT '智能体这一轮走了几步。SlimApp 不填',
    created_at      DATETIME(3) NOT NULL COMMENT '写入时间',
    PRIMARY KEY (id),
    -- 「按会话倒着看最近几轮」是这张表最常见的查法，索引按 (conversation_id, created_at) 建；
    -- 只要「最近 N 条记录」时走的是主键，不需要额外索引
    KEY idx_chat_record_conversation_created (conversation_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '聊天记录（一问一答一行）';
