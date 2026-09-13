# v2 状态与 v3 Backlog

## v2 已完成

| 功能 | 说明 |
|---|---|
| 嵌套评论 | 回复挂在对应一级评论下,支持展开/收起 |
| 个人主页/他人主页 | `/user/:id`,含公开文章列表 |
| 标题/内容搜索 | `keyword` LIKE 匹配,标题+内容 |
| 草稿箱 | `status=0`,仅作者/管理员可见,个人中心可管理 |
| 图片上传 | 5MB 限制,本地存储,正文/封面均可上传 |
| 博客封面 | `articles.cover` 字段,卡片与详情展示 |

## v3 已完成

| 功能 | 说明 |
|---|---|
| AI 助手 | `/ai` 聊天页,SSE 流式;Agent 工具:搜文章/看详情/查用户/站内统计/热文/推荐,OpenAI 兼容 API(`AI_API_KEY`) |
| 相关推荐 | 标签重叠 + 标题/摘要关键词打分,无 AI 依赖,详情页展示 |

## v3 候选

| 功能 | 状态 | 说明 |
|---|---|---|
| 全文搜索升级 | 待做 | 当前 LIKE,v3 可换 Elasticsearch |
| 私信 | 待做 | 独立表 |
| RSS 订阅 | 待做 | 读取文章列表即可实现 |
| 文章审核流 | 待做 | 依赖 status 字段扩展 |
| 用户头像上传 | 待做 | 当前 URL 输入/DiceBear 默认 |

## 开发原则

1. v2 新增 `articles.cover` 一列,其余结构沿用 v1
2. 任何新需求不得要求修改既有表结构(已建字段除外)

## v4 已完成:可见性收敛

新增 `com.blogsys.visibility`,把「这份内容此刻对这个 viewer 可见吗」收成一处。
详见 `docs/architecture.md` §10 与 `docs/adr/0001`、`0002`。

| 项 | 说明 |
|---|---|
| 模块 | `visibility.articles()` 查询拥有者(无「不过滤」变体) · 权限令牌 `VisibleArticle` · `canSeeAuthor` · `restrictToVisibleArticles` |
| 词汇 | 新增 `UserStatus` 枚举;全库 `Integer.valueOf(1)` 的封禁判定清零 |
| 修掉 | D3 RSS · D4 管理员豁免不一致 · D5 标签计数 · D6 收藏三缺陷 · 推荐接口自相矛盾 · 草稿评论 200+空数组 · 三个写路径在不可见文章上成功 |
| 对外行为变更 | 点赞/收藏/评论在不可见文章上由成功变 **404**(ADR-0001);管理员公开列表可见被封禁内容与草稿 |
| 刻意不做 | `/uploads` 图片 URL 仍公开(ADR-0002);`AdminService.stats` 计数仍为原始汇报值 |
| 测试 | 28 → 94 |

## v4 待办

| 项 | 状态 | 说明 |
|---|---|---|
| 可见性的 DB 层测试 | 待决策 | 谓词活在 SQL 里,单测的 mapper 是 mock,证明不了它。现状靠「谓词文本断言 + 一次性真实数据核对」。要能反复跑需要引入 H2(`MODE=MySQL`)或 Testcontainers —— 这是一次显式的依赖决策,尚未做 |
| 列表查询的连接顺序 | 待做 | 见 `architecture.md` §7.4:实测 213ms vs 零 DDL 加一个 hint 的 0.171ms。独立于可见性 |
| AI 工具路径的身份与只读 | 待做 | `architecture.md` §5.1 的 D1/D2。可见性模块已就位,正是它需要工具穿过的那个 seam |
| 前端:写接口 404 的文案 | 待做 | 三个写接口现在会返回 404,前端需要相应提示 |
| 前端:outlet route identity | 待做 | 点相关推荐 URL 变了正文不变;`Write.vue` 的 `onMounted` 注册了两次 |
