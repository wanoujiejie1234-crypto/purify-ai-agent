-- 对话记忆持久化表（MySQL 8 版本）
--
-- 官方 jar 里只提供了 schema-mariadb.sql / schema-postgresql.sql / schema-sqlserver.sql / schema-hsqldb.sql，
-- 其中 schema-mariadb.sql 使用了 `CREATE INDEX IF NOT EXISTS`，MySQL 8 不支持该语法，直接使用会建表失败。
-- 因此这里按官方表结构（列名、类型语义保持一致）改写为 MySQL 8 可执行的 DDL。
--
-- 与官方脚本的两点差异，均为 MySQL 8 兼容性/正确性考虑：
--   1. 索引写在 CREATE TABLE 内部（MySQL 8 没有 CREATE INDEX IF NOT EXISTS）。
--   2. 时间列使用 TIMESTAMP(3) 保留毫秒。官方用的是 TIMESTAMP（秒级），
--      而 repository 的查询是 `ORDER BY timestamp`，秒级精度会让同一秒内的多条消息顺序不稳定。
--      Java 侧通过 setTimestamp 写入，读取时不取该列，因此精度提升无副作用。
CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
    conversation_id VARCHAR(36) NOT NULL COMMENT '会话 ID，UUID 长度为 36',
    content         TEXT        NOT NULL COMMENT '消息内容',
    type            VARCHAR(10) NOT NULL COMMENT '消息类型：USER / ASSISTANT / SYSTEM / TOOL',
    `timestamp`     TIMESTAMP(3) NOT NULL COMMENT '消息写入时间',
    CONSTRAINT SPRING_AI_CHAT_MEMORY_TYPE_CHECK
        CHECK (type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL')),
    KEY SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX (conversation_id, `timestamp`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT 'Spring AI 对话记忆表';
