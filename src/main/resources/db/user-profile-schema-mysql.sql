-- 用户画像表（MySQL 8）
--
-- 为什么单独建表，而不是塞进对话记忆：
-- 对话记忆是「按会话」的——chatId 一换就是一段全新的对话，上一段说过的话就查不到了。
-- 而画像是「按人」的：用户上周说的身高体重，这周新开一个会话还得能读出来。
-- 两者生命周期不同，混在一张表里迟早要打架，所以各存各的。
--
-- 两张表分工：
--   user_profile                当前画像，一个人一行（主键就是用户标识）
--   user_profile_weight_history 体重流水，只追加不修改，用来回答「这周瘦了多少」
--
-- 由 Spring Boot 的 spring.sql.init 在启动时执行（见 application.yml 的 spring.sql.init）。
-- 因此每条语句都必须幂等：重复启动不能报错，更不能清掉已有数据，所以全部带 IF NOT EXISTS。
--
-- user_id 存的是 user.id 的十进制字符串（接入登录之前存的是浏览器生成的会话 UUID）。
-- 列类型保持 VARCHAR(64) 没有改：这张表在共享的 dev 库里已经建好、已经有数据了，
-- 而 MySQL 8 没有 MODIFY COLUMN IF ...，改类型要走 Java 侧的守卫，收益为零。
-- 19 位数字放进 64 个字符绰绰有余。
--
-- 登录之前那些按 UUID / "anonymous" 存的行从此读不到了（不会再有人用那个 key 来查），
-- 但一条都没删。要清的话自己执行（**不要**放进自动脚本，不可逆）：
--   DELETE FROM user_profile WHERE user_id NOT REGEXP '^[0-9]+$';
--   DELETE FROM user_profile_weight_history WHERE user_id NOT REGEXP '^[0-9]+$';
CREATE TABLE IF NOT EXISTS user_profile
(
    user_id         VARCHAR(64)  NOT NULL COMMENT '用户标识：user.id 的十进制字符串',
    age             INT          NULL COMMENT '年龄（岁）',
    height_cm       DECIMAL(5,1) NULL COMMENT '身高（厘米）',
    weight_kg       DECIMAL(5,1) NULL COMMENT '当前体重（公斤）',
    goal            VARCHAR(255) NULL COMMENT '减重目标，自由文本',
    activity_level  VARCHAR(32)  NULL COMMENT '活动水平，存 ActivityLevel 的枚举名',
    diet_preference VARCHAR(255) NULL COMMENT '饮食偏好，自由文本',
    avoid_food      VARCHAR(255) NULL COMMENT '忌口 / 过敏，自由文本',
    created_at      DATETIME     NOT NULL COMMENT '首次建档时间',
    updated_at      DATETIME     NOT NULL COMMENT '最近一次更新时间',
    PRIMARY KEY (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '用户画像（当前状态）';

CREATE TABLE IF NOT EXISTS user_profile_weight_history
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     VARCHAR(64)  NOT NULL COMMENT '用户标识',
    weight_kg   DECIMAL(5,1) NOT NULL COMMENT '当次记录的体重（公斤）',
    recorded_at DATETIME     NOT NULL COMMENT '记录时间',
    PRIMARY KEY (id),
    -- 「查某个用户最近 N 条」是这张表唯一的查询方式，索引按 (user_id, recorded_at) 建
    KEY idx_user_profile_weight_user_recorded (user_id, recorded_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '体重流水（只追加）';
