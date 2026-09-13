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
| 可见性的 DB 层测试 | 待决策(依赖事实已查明) | 谓词活在 SQL 里,单测的 mapper 是 mock,证明不了它。现状靠「谓词文本断言 + 一次性真实数据核对」。要能反复跑需要引入 H2(`MODE=MySQL`)或 Testcontainers。**2026-09-13 核实:两条路现在都不通** —— Docker 守护进程未运行(Testcontainers 直接失败);H2 本机只有 2.1.214/2.2.224/2.4.240,而 Boot 3.4.1 的 BOM 钉的是**不在本机**的 2.3.232(不写 `<version>` 就离线解析失败);且 `docs/schema.sql` 对 H2 `MODE=MySQL` 有 3 处硬阻塞(`CREATE DATABASE`、库级 `DEFAULT CHARACTER SET`、内联 `ON UPDATE CURRENT_TIMESTAMP` ×2)。 |
| 列表查询的连接顺序 | 待做 | 见 `architecture.md` §7.4:实测 213ms vs 零 DDL 加一个 hint 的 0.171ms。独立于可见性 |
| ~~AI 工具路径的身份与只读~~ | **已完成** | `architecture.md` §11。候选 02 |
| `docs/schema.sql` 漏 DROP `favorites` | 待做 | `DROP TABLE IF EXISTS` 前奏列了 6 张表,独漏第 7 张 `favorites`(建表在最后)。照 `README.md:46` 的流程重跑会因表已存在而失败。一次提交即可 |
| 前端:写接口 404 的文案 | 待做 | 三个写接口现在会返回 404,前端需要相应提示 |
| 前端:outlet route identity | 待做 | 点相关推荐 URL 变了正文不变;`Write.vue` 的 `onMounted` 注册了两次 |

## v5 已完成:AI 工具路径的身份与只读(候选 02)

把「谁在问」带过异步 seam,并让「只读」由构造保证。详见 `docs/architecture.md` §11。

| 项 | 说明 |
|---|---|
| 机制 | `chatExecutor` 加 TaskDecorator 把 `SecurityContext` 带到池线程(域层);`ChatController` 显式把 `Viewer` 传进 `ChatService`(工具层)。无新增依赖 |
| 授权 | `ToolRegistry.definitions(viewer)` 没有无参重载;`ChatService.executeTool` 二次判定,且「不可用」与「不存在」逐字同答复 |
| 修掉 | D1 任意登录用户可从 AI 读到 ADMIN 专属全站统计 · D2 AI 查详情会写 `view_count`(因而把自己问过的文章顶进热门榜) · 收藏/点赞状态静默答成 false |
| 对外行为变更 | 非管理员的 AI 工具清单里不再有 `getSiteStats`;AI 查详情不再改变浏览量。HTTP 路径行为未变 |
| 测试 | 94 → 111(新增真实线程池的身份传播测试、含对照;用真实 `SiteStatsTool` 钉受众;`InOrder` 钉「先记录浏览再读详情」) |

## v5 已完成:router outlet 的 route identity(候选 03)

详见 `docs/architecture.md` §12。

| 项 | 说明 |
|---|---|
| 机制 | 标识算在 `frontend/src/router/identity.js` 的纯函数里:该 outlet 自己那一层的记录 + params + query。父布局不因子记录变化而重挂载 |
| 修掉 | D7 URL 变了正文不变 · `Home.vue` 的补偿 watcher 删掉 · `Write.vue` 的 `onMounted` 注册两次(载入发两次请求) |
| 顺带修 | **编辑页打开且停留 20 秒会把已发布文章静默改成草稿**(载入即判脏 + 自动保存固定存草稿)。数据完整性缺陷,非评审报告所列 |
| 前端测试 | 从零到有:`npm test` = `node --test`,**零新增依赖**;10 个用例钉住重挂载边界 |
| 对外行为变更 | 搜索时首页会重挂载,因而会重取 `/api/tags` 与 `/api/articles/hot` |

## v5 已完成:把上游解帧搬出传输层(候选 06)

详见 `docs/architecture.md` §13。

| 项 | 说明 |
|---|---|
| 机制 | 新增 `StreamReducer`(帧进、事件出,不认识网络)与 `ChatPayload`(请求体构造,纯函数);`OpenAiClient` 退回纯传输,只剩线格式切分与 HTTP 错误 |
| 修掉 | **D8** 错误帧被静默丢弃(限流/内容过滤 → 空白回答 + done)· **D9** 零参数工具调用被重新归类成「没有工具调用」,模型不知道它被跳过 · 被截断的流不再伪装成正常结束 |
| 顺带 | `HttpClient` 提成字段(此前每轮新建一个,一次对话最多 4 个且无 keep-alive) |
| 测试 | 111 → 126。D8/D9 各成一条毫秒级用例,不再依赖真实网络 |

## v5 已完成:让 session 只有一个归属(候选 05)

详见 `docs/architecture.md` §14。

| 项 | 说明 |
|---|---|
| 机制 | 新增 `src/session.js`,**不 import 任何东西** —— 状态/跳转/提示都是注入的 port,生产接线在 `session-instance.js`。两个真 adapter(axios、fetch)共用一个 seam |
| 修掉 | 401 反应三份实现 + 四处 view 复制(7 个地方能重定向,一处是死代码)· view 与两个 adapter 裸跳 `/login` 导致登录后回不到原页 · `ai.js` 里逐字重建的请求头 · `recommendApi` 停在 fetch module 里 |
| 测试 | 10 → 21;接线另做一次性浏览器核对(守卫、带查询串、view 三处) |

## v5 候选(来自 2026-09-13 架构评审,8 个候选里已完成 5 个)

完整论证与前后对照图见 `docs/architecture-review-2026-09-13.html`(HTML,含 Mermaid 图)。
以下是一行摘要,防止那份快照丢失时工作项也一起丢:

| # | 候选 | 强度 | 一句话 |
|---|---|---|---|
| 01 | ~~内容可见性收敛成一个 module~~ | Strong | **已完成**,见 `architecture.md` §10 |
| 02 | ~~让「谁在问、只读」穿过异步 seam~~ | Strong | **已完成**,见 `architecture.md` §11。AI 会话曾跑在没有 principal 的线程池上,导致 ADMIN 专属统计可被任意登录用户读到(D1,已实测),且「只读」承诺被 `incrViewCount` 违反(D2,已实测) |
| 03 | ~~给 router outlet 加 route identity~~ | Strong | **已完成**,见 `architecture.md` §12。outlet 无 `:key`,`/article/A → /article/B` 复用实例 → URL 变了正文不变 |
| 04 | 6 个 view 各自手搓的分页切片收成一个 deep module | Strong | 状态五元组 ×6、reload 函数体 ×7、分页块逐字相同 ×6;15 个 view 里只有 1 个 `catch`,「请求失败」当前不可表达 |
| 05 | ~~让 session 只有一个归属~~ | Strong | **已完成**,见 `architecture.md` §14。「带 token + 401 登出跳转」曾有 3 份实现 + 4 处 view 复制,7 处可重定向;`loginRequired()` 是死代码 |
| 06 | ~~把上游解帧搬出传输层~~ | Strong | **已完成**,见 `architecture.md` §13。唯一解帧的代码曾只能靠真实网络触达 → D8(错误帧被静默丢弃)与 D9(零参数工具调用可能被丢掉)长在无测试面的地方 |
| 07 | SSE seam 升到「对话行为」这一层 | Strong | 线格式知识被实现四次(`ChatService`、`SseWriterHttp`、测试的 CapturingWriter、`api/ai.js`),且 `done` 事件客户端没处理 |
| 08 | 给文章→列表项的投影一个家 | Worth exploring | `ArticleListItemVO` 组装两次,第二份漏了 `coverThumb` 且有 N+1;前端用 `coverThumb \|\| cover` 把缺失掩盖了 |
