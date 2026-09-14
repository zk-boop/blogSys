-- 社区种子数据的清理脚本 —— 「先清后插」的第一步。
--
-- 设计原则:**只删种子账号自己,以及它们的作品**。判断依据只有一份名单(下面的 @seed_usernames),
-- 不靠时间戳、不靠标题前缀、不靠 id 区间 —— 那些都会在重跑之后漂移。
--
-- 你原有的 4 个账号(admin / zzkk / alice / bob)与 5 篇文章不在名单里,一行都不会被碰。
--
-- 用法(脚本会用同样的方式调用它,你也可以手动跑):
--   mysql -uroot -p blog_sys < tools/seed/clear.sql
--
-- 为什么用变量 + FIND_IN_SET 而不是临时表:MySQL 不允许**同一条语句**里两次引用同一张
-- 临时表(ERROR 1137 Can't reopen table),而下面的计数与删除语句都要反复用到这份名单。
-- 会话变量没有这个限制,而且少了「建表/删表」两步。
--
-- 注意两条**有意为之**的连带删除,删除前的计数会打印出来:
--   1. 种子文章下的**所有**评论都会被删 —— 包括不是种子账号写的。文章本身要没了,
--      留下孤儿评论行只会更脏。所以:别在种子文章下面留你自己的评论(或者留下之后就认了)。
--   2. 清理之后**没有任何文章**的引用标签会被删掉(它们只可能是种子带出来的空壳);
--      仍被真实文章使用的标签一律不动 —— 标签是共享词汇表,不是种子独占的。

SET NAMES utf8mb4;

-- 唯一的名单。改这里必须同时改 tools/seed/community.json —— 脚本会核对两边一致,
-- 不一致就直接报错退出(同一份名单只允许有一个真源)。
--
-- 后面的 COLLATE 不是装饰:库里的列是 utf8mb4_unicode_ci,而客户端送进来的字面量是
-- utf8mb4_0900_ai_ci,不指定就会在 FIND_IN_SET 上直接报 1267(Illegal mix of collations)。
SET @seed_usernames = 'haitang,zhouyu,chenmo,linwan,suqing,wangkai,moyu,qingshan,xiaoman,heyan,nanfeng,yizhi,tongtong,shier' COLLATE utf8mb4_unicode_ci;

SET @seed_user_ids = (SELECT GROUP_CONCAT(id) FROM users WHERE FIND_IN_SET(username, @seed_usernames));
SET @seed_article_ids = (SELECT GROUP_CONCAT(id) FROM articles WHERE FIND_IN_SET(user_id, @seed_user_ids));

SELECT '=== 删除前 ===' AS stage;
SELECT
  (SELECT COUNT(*) FROM users WHERE FIND_IN_SET(username, @seed_usernames))                     AS seed_users,
  (SELECT COUNT(*) FROM articles WHERE FIND_IN_SET(id, @seed_article_ids))                     AS seed_articles,
  (SELECT COUNT(*) FROM comments WHERE FIND_IN_SET(article_id, @seed_article_ids))             AS comments_on_seed_articles,
  (SELECT COUNT(*) FROM comments WHERE FIND_IN_SET(article_id, @seed_article_ids)
      AND NOT FIND_IN_SET(user_id, @seed_user_ids))                                            AS comments_by_real_users_on_seed_articles,
  (SELECT COUNT(*) FROM likes WHERE FIND_IN_SET(article_id, @seed_article_ids))                AS likes_on_seed_articles,
  (SELECT COUNT(*) FROM favorites WHERE FIND_IN_SET(article_id, @seed_article_ids))            AS favorites_on_seed_articles,
  (SELECT COUNT(*) FROM users WHERE username IN ('admin', 'zzkk', 'alice', 'bob'))             AS your_accounts_still_here;

DELETE FROM favorites
 WHERE FIND_IN_SET(user_id, @seed_user_ids) OR FIND_IN_SET(article_id, @seed_article_ids);

DELETE FROM likes
 WHERE FIND_IN_SET(user_id, @seed_user_ids) OR FIND_IN_SET(article_id, @seed_article_ids);

DELETE FROM comments
 WHERE FIND_IN_SET(user_id, @seed_user_ids) OR FIND_IN_SET(article_id, @seed_article_ids);

DELETE FROM article_tags WHERE FIND_IN_SET(article_id, @seed_article_ids);

DELETE FROM articles WHERE FIND_IN_SET(id, @seed_article_ids);

DELETE FROM users WHERE FIND_IN_SET(username, @seed_usernames);

-- 只剩空壳的标签(没有任何文章引用)才删;仍被引用的标签一律不动
DELETE FROM tags WHERE id NOT IN (SELECT DISTINCT tag_id FROM article_tags);

SELECT '=== 删除后 ===' AS stage;
SELECT
  (SELECT COUNT(*) FROM users WHERE FIND_IN_SET(username, @seed_usernames))   AS seed_users_left,
  (SELECT COUNT(*) FROM users)                                               AS users_total,
  (SELECT COUNT(*) FROM articles)                                            AS articles_total,
  (SELECT COUNT(*) FROM comments)                                            AS comments_total,
  (SELECT COUNT(*) FROM users WHERE username IN ('admin', 'zzkk', 'alice', 'bob')) AS your_accounts_still_here;
