-- 会话表（MySQL 8）
--
-- 为什么单独建一张表，而不是给 chat_record 加一列 user_id：
--
--   1. chat_record 是「一问一答一行」的账本，一个会话在那里有很多行。而「这个会话属于谁、
--      叫什么名字」是每个会话一份的属性——塞进账本就得在每一行上重复一遍，改个标题要
--      UPDATE 一整片行。
--   2. 标题（重命名）压根没有地方放：chat_record 里没有、也不该有「会话级」的字段。
--   3. 加列还要处理幂等：MySQL 8 没有 ADD COLUMN IF NOT EXISTS，而本脚本每次启动都会
--      被 spring.sql.init 重跑（mode: always），一句无条件的 ALTER 会在第二次启动时报
--      重复列。新表用 CREATE TABLE IF NOT EXISTS 就天然幂等，不用绕那个弯。
--
-- 与对话记忆的关系：仍然没有关系。SPRING_AI_CHAT_MEMORY 是喂给模型的，
-- chat_record 是给人看的账本，这张表是「会话列表」——三者用途不同，各存各的。
--
-- user_id 目前是浏览器生成的（localStorage 里存一份），因为这个项目还没有登录体系。
-- 接入登录之后把来源换成真实用户 ID 即可，表结构不用动。
CREATE TABLE IF NOT EXISTS chat_session
(
    conversation_id VARCHAR(64)  NOT NULL COMMENT '会话 ID，与 chat_record.conversation_id 对应',
    user_id         VARCHAR(64)  NOT NULL COMMENT '用户标识。当前由浏览器生成，接入登录后换成真实用户 ID',
    entry           VARCHAR(32)  NOT NULL COMMENT '从哪个入口进来的：SLIM / MANUS。两条链路的会话列表互不可见',
    title           VARCHAR(255) NOT NULL COMMENT '会话标题。默认取首轮提问的截断，用户可以改',
    created_at      DATETIME(3)  NOT NULL COMMENT '首次对话时间',
    updated_at      DATETIME(3)  NOT NULL COMMENT '最近一次对话时间，会话列表按它倒序',
    PRIMARY KEY (conversation_id),
    -- 「列出某个用户在某个入口下的会话，按最近使用倒序」是这张表唯一的查法
    KEY idx_chat_session_user_entry_updated (user_id, entry, updated_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '会话（侧边栏的每一项）';
