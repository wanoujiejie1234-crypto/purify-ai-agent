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
-- user_id 存的是 user.id 的十进制字符串。在这之前它是浏览器生成的一个 UUID
-- （存在 localStorage 里）——表结构一个字没改，只是值的来源换成了登录态，正如当初注释所料。
--
-- 这张表也是**会话归属的唯一依据**：chat_record 没有、也不该有 user_id 列，
-- 所以「这个会话是不是你的」这个问题一律通过这里回答（见 SessionAccess）。
--
-- 登录之前那些按 UUID / "anonymous" 存的行从此读不到了，但一条都没删。要清的话自己执行
-- （**不要**放进自动脚本：它会连带删掉那些会话的聊天记录，不可逆）：
--   DELETE FROM chat_session WHERE user_id NOT REGEXP '^[0-9]+$';
CREATE TABLE IF NOT EXISTS chat_session
(
    conversation_id VARCHAR(64)  NOT NULL COMMENT '会话 ID，与 chat_record.conversation_id 对应',
    user_id         VARCHAR(64)  NOT NULL COMMENT '用户标识：user.id 的十进制字符串',
    entry           VARCHAR(32)  NOT NULL COMMENT '从哪个入口进来的：SLIM / MANUS。两条链路的会话列表互不可见',
    title           VARCHAR(255) NOT NULL COMMENT '会话标题。默认取首轮提问的截断，用户可以改',
    created_at      DATETIME(3)  NOT NULL COMMENT '首次对话时间',
    updated_at      DATETIME(3)  NOT NULL COMMENT '最近一次对话时间，会话列表按它倒序',
    PRIMARY KEY (conversation_id),
    -- 「列出某个用户在某个入口下的会话，按最近使用倒序」是这张表唯一的查法
    KEY idx_chat_session_user_entry_updated (user_id, entry, updated_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '会话（侧边栏的每一项）';
