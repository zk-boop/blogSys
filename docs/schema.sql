-- blogSys 数据库初始化脚本(MySQL 8)
--
-- ⚠️ **这是初始化脚本,不是升级脚本**:它会把下面这 7 张表 DROP 掉再重建。
--    只能在**空库**上跑,或者在确认可以清空这份数据时跑。库里有要紧数据时不要跑它。
--    它会创建并使用名为 `blog_sys` 的库(见下面两行)—— 想换成别的库名,改那两行。
--
-- 用法: mysql -uroot -p123456 < docs/schema.sql
-- 已有数据的升级路径:本项目当前不做自动迁移,schema 变更随版本说明手工执行。

CREATE DATABASE IF NOT EXISTS blog_sys DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE blog_sys;

-- 关闭外键检查,避免历史遗留表的外键导致 DROP 失败
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS likes;
DROP TABLE IF EXISTS favorites;
DROP TABLE IF EXISTS comments;
DROP TABLE IF EXISTS article_tags;
DROP TABLE IF EXISTS tags;
DROP TABLE IF EXISTS articles;
DROP TABLE IF EXISTS users;

-- 用户表
CREATE TABLE users (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    username    VARCHAR(50)  NOT NULL,
    password    VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希',
    nickname    VARCHAR(50)  NOT NULL,
    avatar      VARCHAR(255) NOT NULL DEFAULT '',
    role        VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT 'USER / ADMIN',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=正常 1=封禁',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB COMMENT '用户表';

-- 文章表
CREATE TABLE articles (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_id       BIGINT       NOT NULL,
    title         VARCHAR(200) NOT NULL,
    content       LONGTEXT     NOT NULL COMMENT 'Markdown 原文',
    summary       VARCHAR(500) NOT NULL DEFAULT '',
    cover         VARCHAR(255) NOT NULL DEFAULT '' COMMENT '封面图 URL',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1=已发布, 0=草稿',
    view_count    INT          NOT NULL DEFAULT 0,
    like_count    INT          NOT NULL DEFAULT 0,
    comment_count INT          NOT NULL DEFAULT 0,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user (user_id),
    KEY idx_created (created_at)
) ENGINE = InnoDB COMMENT '文章表';

-- 标签表
CREATE TABLE tags (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    name       VARCHAR(50) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_name (name)
) ENGINE = InnoDB COMMENT '标签表';

-- 文章-标签关联表
CREATE TABLE article_tags (
    article_id BIGINT NOT NULL,
    tag_id     BIGINT NOT NULL,
    PRIMARY KEY (article_id, tag_id)
) ENGINE = InnoDB COMMENT '文章-标签关联表';

-- 评论表(v2 预留 parent_id 支持嵌套)
CREATE TABLE comments (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    article_id BIGINT       NOT NULL,
    user_id    BIGINT       NOT NULL,
    parent_id  BIGINT       NULL COMMENT '预留:v2 嵌套评论,一级评论为 NULL',
    content    VARCHAR(1000) NOT NULL,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_article (article_id),
    KEY idx_user (user_id)
) ENGINE = InnoDB COMMENT '评论表';

-- 点赞表
CREATE TABLE likes (
    id         BIGINT   NOT NULL AUTO_INCREMENT,
    article_id BIGINT   NOT NULL,
    user_id    BIGINT   NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_article_user (article_id, user_id)
) ENGINE = InnoDB COMMENT '点赞表';

-- 收藏表
CREATE TABLE favorites (
    id         BIGINT   NOT NULL AUTO_INCREMENT,
    user_id    BIGINT   NOT NULL,
    article_id BIGINT   NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_article (user_id, article_id),
    KEY idx_user (user_id)
) ENGINE = InnoDB COMMENT '收藏表';

SET FOREIGN_KEY_CHECKS = 1;
