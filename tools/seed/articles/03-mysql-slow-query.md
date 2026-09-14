上周三下午两点四十,监控在群里弹了一条消息。文章列表接口的 P99 从平时的 40 毫秒跳到了 213 毫秒。它没有超时,没有报错,只是慢 —— 慢到列表页点进去要先白一下。这种数字比故障更烦人,因为没有人会为它半夜爬起来。

顺带说一句,这个问题不是我发现的。我们那条告警规则写的是 P99 超过 150 毫秒并持续五分钟,P99 从 40 爬到 213 花了大概三天。三天里一个投诉都没有 —— 读者不会为"稍微有点慢"的列表页写工单,他们只是少点几次。所以如果问我这条告警值不值,我觉得值,虽然它响的时候我很想把它关掉。

接口本身简单得不像能出事:按标签筛文章,按点赞数倒序,取前 20 条。库里的规模是 article 表 120 万行,article_tag 表 260 万行,tag 表 3,200 行。这条 SQL 是我半年前写的,当时还觉得挺得意,一次查询就把标签、文章、点赞数全带出来了:

```sql
SELECT a.id, a.title, a.summary, a.created_at, COUNT(l.id) AS like_count
FROM tag t
JOIN article_tag at ON at.tag_id = t.id
JOIN article a ON a.id = at.article_id
LEFT JOIN likes l ON l.article_id = a.id
WHERE t.name = 'mysql'
  AND a.status = 'PUBLISHED'
GROUP BY a.id
ORDER BY like_count DESC, a.created_at DESC
LIMIT 20;
```

写的时候我心里的计划很清楚:先按名字在 tag 表里取一行,顺着 article_tag 拿到这个标签下的两百多篇文章,回表取标题,最后统计点赞。理论上几十毫秒的事。

第一次 `EXPLAIN`,执行计划跟我脑子里那套完全不是一回事:第一张表是 `article`,type 是 `index`,rows 估到 480,213,Extra 里明明白白写着 `Using temporary; Using filesort`。也就是说它先把四十多万篇文章捞出来建临时表,再排序,最后才去看标签对不对得上。

那份计划往下读,还有更具体的东西。`article` 那行用的索引是 `idx_status_created`,key_len 短得刚好只覆盖到 status;`article_tag` 对每一行做一次 `ref` 查找,预估要读 26 万次;`likes` 被放在最后,`Using join buffer` 摆在那儿,意思是它准备在内存里拼一张大表。唯一没被用上的,恰恰是我最想让它用的那个索引。

我按顺序试了这几件事,时间都记了下来:

1. 先看 `EXPLAIN`,确认行数是 480,213 而不是两百多,并注意到 `Using temporary`。这一步十分钟,其实已经指出了方向。
2. 查 `SHOW INDEX FROM article_tag`,发现 `(tag_id, article_id)` 和 `(article_id, tag_id)` 两个联合索引都在 —— "索引没建"这个假设当场破产。
3. 转头怀疑 likes 表,给它补了一个 `(article_id)` 索引。没用。因为这个索引去年就建过了,我忘了。这四十分钟是纯粹白花的。
4. 重建统计信息,`ANALYZE TABLE article, article_tag` 之后再跑,计划倒是变了,结果从 213 毫秒变成 260 毫秒 —— 换了个方向继续错。
5. 换 `EXPLAIN FORMAT=JSON` 看 `cost_info`,才发现 `tag` 那一行的 `rows_examined_per_scan` 是 1,而 `article` 是 480,213。驱动表被优化器翻过来了,翻得毫不犹豫。

至于原因,我猜了两轮才对。前一周我给 article 加过一个 `idx_status_created(status, created_at)`,本意是救另一个后台页面。优化器看到这个索引能把 `status = 'PUBLISHED'` 过掉一大半,就认定从 article 出发更划算 —— 它算漏了后面那句 `GROUP BY a.id` 要在四十多万行上建临时表排序的成本。索引是好的,统计信息也是新的,只是它权衡的东西跟我关心的东西不是同一件。

> 索引没被用上,不代表索引不存在。更常见的情况是优化器认为用它的代价更高,而这个"认为"是拿一份你从没看过的账单算出来的。

真正的改法有两步,一步是结构,一步是写法。

结构上,我把 `LEFT JOIN likes` 和 `GROUP BY` 整块拿掉了,点赞数冗余成 article 表上的一列 `like_count`,在点赞和取消点赞的时候顺手更新。这一刀砍掉的不是几毫秒,是四十多万行的临时表。写法上,我不再指望优化器理解我的意图,直接把顺序钉死:

```sql
SELECT STRAIGHT_JOIN
       a.id, a.title, a.summary, a.created_at, a.like_count
FROM tag t
JOIN article_tag at ON at.tag_id = t.id
JOIN article a ON a.id = at.article_id
WHERE t.name = 'mysql'
  AND a.status = 'PUBLISHED'
ORDER BY a.like_count DESC, a.created_at DESC
LIMIT 20;
```

改完第一次跑出来是 0.4 毫秒,我以为看错了,又跑了 100 次取中位数,0.171 毫秒。从 213 到 0.171,差不多一千二百倍。这个倍数不值得炫耀,它只说明原来那条 SQL 蠢得相当彻底 —— 排序的对象从四十八万行变成了两百一十七行,剩下的工作就是二十次主键回表。

中间我还试过两条路,都否掉了。一条是先查子查询取出这个标签下的文章 id,外面再套一层按点赞数排序。`EXPLAIN` 显示它少读了一半的行,可排序对象还是那两百多篇文章,降到 60 毫秒上下,离能用差得远。另一条是把列表整个塞进缓存,几分钟过期一次。缓存确实快,但它会把"点完赞刷新看不到自己的赞"变成常态,而我今年最想要的就是点赞能立刻看见。

新写法没有立刻上线。先把新 SQL 挂在一台灰度实例上跑了二十分钟,压测脚本打了两千次,中位数 0.171 毫秒,P99 是 0.9 毫秒。那条尾巴不是查询本身,是四次里有三次撞上了连接池排队。这个数字反而让我放心了:瓶颈已经不在 SQL 上。

代价是有的。冗余字段意味着每次点赞都要多写一次 article 表,还意味着它随时可能跟 likes 表对不上。所以我加了两个保险:点赞和取消点赞放在同一个事务里,外加每晚三点跑一次对账,把所有的 `like_count` 重算一遍并打出差异日志。对账上线的第一晚就报了 17 篇文章数量不符,查下去是去年一次批量删除文章时漏了更新。

这个意外收获让我对"冗余"这件事的看法松动了一点:它不只是拿一致性换性能,顺便还帮你把不一致找出来。当然,前提是你真去看那份日志。

那条 `idx_status_created` 我最后留着,毕竟它确实救过后台页面。但我在建表脚本里给它补了一行注释,写清楚它是为什么加的、现在有哪几条 SQL 依赖它。以前我加索引从不写注释,过两个月就只记得"这里好像有用",再遇到类似的执行计划,又得从头猜一遍。

至于排查本身,我复盘了一遍,最贵的不是写 SQL 的那两个小时,是第 3 步和第 4 步。两次都是我先有了结论,再去找证据,而证据其实一直摆在第一次 `EXPLAIN` 的输出里 —— 驱动表是 `article`,行数是四十八万。下次再遇到这种"慢得莫名其妙"的查询,我打算先只做一件事:把执行计划的第一张表和它的 rows 抄在纸上,别急着改索引。还有一条记在本子上的:MySQL 8 有 `EXPLAIN ANALYZE`,它会把预估行数和真实行数并排摆在眼前,而我从头到尾只用了 `EXPLAIN`。如果哪天真碰上一个"预估很准但还是慢"的计划,大概就是它上场的时候了。
