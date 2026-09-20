-- 用户与认证（MySQL 8）
--
-- 两张表分工：
--   user        账号本身：谁能登录、密码是什么、什么角色
--   verify_code 邮箱验证码：注册和找回密码共用一套，用 purpose 区分
--
-- 由 Spring Boot 的 spring.sql.init 在启动时执行（见 application.yml 的 spring.sql.init），
-- 每次启动都会重跑。因此每条语句都必须幂等：重复启动不能报错，更不能清掉已有数据，
-- 所以全部带 IF NOT EXISTS。
--
-- ⚠ 表名 user 是 MySQL 的内置函数名（USER()）。MySQL 8 并不保留它，不带反引号也能建、
--   也能查，但把它和函数混在一起看很容易读错。本文件里所有语句一律写成 `user`。
--
-- 另外三样东西不在这个脚本里，由 SchemaGuard 在启动时按需补：
--   1. role 列            —— 见下面 CREATE TABLE 里的说明
--   2. uk_user_username   —— 唯一索引
--   3. uk_user_email      —— 唯一索引
-- 原因：MySQL 8 没有 ADD COLUMN IF NOT EXISTS / CREATE INDEX IF NOT EXISTS，而本脚本每次
-- 启动都要重跑，写一句无条件的 ALTER 会在第二次启动时报重复列 / 重复键名。新表用
-- CREATE TABLE IF NOT EXISTS 天然幂等（上面那三条已经写在 CREATE TABLE 里了），
-- 只有「库里已经有一张建好的 user 表」这种情形才需要 SchemaGuard 去补，
-- 而那是个需要先查 information_schema 才能决定做不做的动作。
CREATE TABLE IF NOT EXISTS `user`
(
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    username        VARCHAR(50)  NOT NULL COMMENT '用户名，登录用',
    -- 存 BCrypt 哈希（60 字符），永远不存明文。255 是给以后换算法留的余量：
    -- BCrypt 写死 60 就够了，但 argon2 的编码串要长得多，改列类型比改算法麻烦
    password        VARCHAR(255) NOT NULL COMMENT '密码，BCrypt 哈希',

    nickname        VARCHAR(50)  NULL COMMENT '昵称',
    avatar          VARCHAR(500) NULL COMMENT '头像URL',

    -- 可空，但唯一。MySQL 的 UNIQUE 索引允许多个 NULL，所以「有账号没邮箱」是合法的
    -- （种子账号 root_agent 就是这种），而「两个账号共用一个邮箱」不合法。
    -- 这个唯一性是找回密码那条链路的前提：它是按 email 反查账号的，
    -- 重复邮箱会让「改谁的密码」变成一个不确定的问题
    email           VARCHAR(100) NULL COMMENT '邮箱，唯一（找回密码靠它反查账号）',
    phone           VARCHAR(20)  NULL COMMENT '手机号',

    gender          TINYINT      NULL COMMENT '性别：0未知 1男 2女',
    birthday        DATE         NULL COMMENT '出生日期',

    -- 取值是 UserRole 的枚举名：SUPER（超级用户） / NORMAL（普通用户）。
    -- 用字符串而不是 0/1：多一个角色时不用改列类型，看库时也不用对着数字猜
    role            VARCHAR(20)  NOT NULL DEFAULT 'NORMAL' COMMENT '角色：SUPER / NORMAL',

    -- 0 禁用 1 正常。登录时会检查它：禁用是管理员主动做的处置，
    -- 和 is_deleted（账号被删）是两件事，报给用户的提示也不一样
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0禁用 1正常',

    last_login_time DATETIME     NULL COMMENT '最后登录时间',
    last_login_ip   VARCHAR(50)  NULL COMMENT '最后登录IP',

    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    -- 逻辑删除。登录查询会过滤它，所以被软删的账号登不进来；
    -- 而 RootAgentInitializer 是唯一一个**不过滤**它的地方——理由见那个类
    is_deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1已删除',

    PRIMARY KEY (id),
    -- 用户名必须唯一：否则注册能造出两个同名账号，而登录是按用户名查的，
    -- 「查出来是哪一行」就变成了一个取决于插入顺序的不确定问题。
    -- root_agent 的种子查询同样按用户名查，重名会让它查出两行
    UNIQUE KEY uk_user_username (username),
    UNIQUE KEY uk_user_email (email)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '用户';

-- 邮箱验证码。注册和找回密码共用这一张表，用 purpose 区分。
--
-- 为什么不放内存：重启即失效，本地开发时改一次代码就得重新收一次邮件；
-- 而「60 秒内不能重发」这条限流需要一个跨请求、跨重启都算数的记录。
-- 项目已经有 MySQL，为这点数据引 Redis 不划算。
CREATE TABLE IF NOT EXISTS verify_code
(
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键，同时也是「哪一条最新」的排序依据',
    -- 入库前统一小写去空格（只在 VerifyCodeRepository 一处做）：
    -- A@x.com 和 a@x.com 在收信上是同一个地址，各存一份的话，
    -- 找回密码时「按邮箱取最新一条」会随机命中其中之一
    email      VARCHAR(100) NOT NULL COMMENT '收件邮箱，统一小写去空格',
    purpose    VARCHAR(32)  NOT NULL COMMENT '用途：REGISTER / RESET_PASSWORD',
    -- 明文存。这不是疏忽：验证码本身就是给用户看的短期凭据，
    -- 它的防护来自「10 分钟过期 + 5 次尝试上限 + 用过即废」这三条，而不是来自哈希
    code       CHAR(6)      NOT NULL COMMENT '6 位数字',
    expires_at DATETIME     NOT NULL COMMENT '过期时间，签发后 10 分钟',
    attempts   INT          NOT NULL DEFAULT 0 COMMENT '已校验次数，超过上限直接作废',
    used       TINYINT      NOT NULL DEFAULT 0 COMMENT '0=未用 1=已用。消费是 CAS，见仓储层',
    created_at DATETIME(3)  NOT NULL COMMENT '签发时间，60 秒限流按它算',
    PRIMARY KEY (id),
    -- 这张表只有一种查法：「取某个 (email, purpose) 的最新一条」。
    -- 把 id 一起放进索引，那个查询就是一次索引扫描，不用回表排序
    KEY idx_verify_code_email_purpose (email, purpose, id),
    -- 给顺手清理过期行用的（见 VerifyCodeRepository 的 housekeeping）
    KEY idx_verify_code_expires (expires_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '邮箱验证码';

-- ============================================================================
-- 手工清理（**不要**放进自动脚本，需要时自己复制出来执行）
--
-- 接入登录之前，聊天会话和用户画像是按浏览器随机生成的 UUID 存的。现在用户标识换成了
-- 真实的用户 id（十进制数字串），那些老行再也读不到了——不是坏了，是没人能再指向它们。
--
-- 留着不删是有意的：这几行数据不值钱，而这是一条不可逆的 DELETE，
-- 对象还是一台共享的远程库。要清就自己确认过再执行下面这几句。
--   DELETE FROM chat_session  WHERE user_id NOT REGEXP '^[0-9]+$';  -- 会连带删掉这些会话的聊天记录
--   DELETE FROM user_profile  WHERE user_id NOT REGEXP '^[0-9]+$';
--   DELETE FROM user_profile_weight_history WHERE user_id NOT REGEXP '^[0-9]+$';
-- ============================================================================
