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
| ~~RSS 订阅~~ | **已完成** | `controller/RssController.java` 的 `GET /rss`:RSS 2.0、最新 20 篇。订阅源不发送 `Authorization`,viewer 恒为匿名,所以草稿与被封禁作者都不在其中 —— 这个答案直接来自 `visibility.articles()`。接口口径见 `docs/api.md` |
| 文章审核流 | 待做 | 依赖 status 字段扩展 |
| ~~用户头像上传~~ | **已完成** | 后端 `controller/UploadController.saveAvatar`(`type=avatar`,居中裁 1:1 再缩到 256x256 的 jpg);前端 `components/ImageCropUpload.vue` 只负责裁剪与上传(`views/Profile.vue` 接它)。上传规则见 `docs/api.md` |

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
| ~~`docs/schema.sql` 漏 DROP `favorites`~~ | **已完成** | `DROP TABLE IF EXISTS` 此前列了 6 张表,独漏第 7 张 `favorites`(建表在最后)。照 `README.md:46` 的流程重跑会因表已存在而失败。现已补进 `docs/schema.sql:11`,紧挨同为「文章×用户」关联表的 `likes` |
| 前端:写接口 404 的文案 | 待做 | 三个写接口现在会返回 404,前端需要相应提示 |
| ~~前端:outlet route identity~~ | **已完成** | 点相关推荐 URL 变了正文不变;`Write.vue` 的 `onMounted` 注册了两次。标识收进 `frontend/src/router/identity.js` 的纯函数 `outletKey`,详见 `docs/architecture.md` §12 |
| `hot` 接口的 `size` 没有下限 | 待做(2026-09-14 修文档时发现) | `ArticleService.hot` 只把 `size` 钳到**上限 20**,不看下限:`?size=-1` 会拼出 `LIMIT -1` 直接 500,`?size=0` 返回空列表。它是**公开**接口,所以这是一次「畸形请求得到 500 而不是 400」。一行 `Math.max(1, …)` 就能收掉,但它是一次行为变更(新增校验),没有顺手改 |
| RSS 的站点地址写死在代码里 | 待做(2026-09-14 发现) | `RssController.SITE_URL` 硬编码 `http://localhost:8080`,条目的 `link`/`guid` 全按它拼 —— 换域名必须改代码并重新部署。应做成配置项(如 `blogsys.site-url`),让「站点对外地址」只有一处 |
| backlog 各节的测试数不是当前值 | 说明(2026-09-14) | 下文各节的「测试 x → y」记的都是**当时**的累计值,不要拿它们对总数。当前:后端 **185** 个 `@Test`、前端 **97** 个用例 |

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

## v5 已完成:把 SSE seam 升到「对话行为」这一层(候选 07)

详见 `docs/architecture.md` §15。

| 项 | 说明 |
|---|---|
| 机制 | `ChatEgress`(行为层 seam) + `SseProtocol`(线格式唯一归属,纯函数) + `SseWriterHttp`(只剩写字节);前端侧归 `api/aiEvents.js`;`SseWriter` 删除 |
| 修掉 | 同一份线格式知识曾被实现四次 · **前端把 `done` 解析了却从不处理** ⇒ 被截断的回答与完整回答长得一模一样 |
| 测试 | 后端 126 → 133 · 前端 21 → 32;另做跨语言集成核对(真实字节按 7 字符碎片喂给前端解析器) |
| 契约 | `docs/api.md` 那张表升格为跨语言契约的单一出处 |

## v5 已完成:给文章→列表项的投影一个家(候选 08)

详见 `docs/architecture.md` §16。

| 项 | 说明 |
|---|---|
| 机制 | 新增 `ArticleListItems`:`of(articles)` 批量(作者与标签各一次查询) · `fill(article, vo)` 单篇;详情与列表两条路径都穿过它 |
| 修掉 | 推荐器漏掉 `coverThumb`(前端 `coverThumb \|\| cover` 掩盖了它)· 推荐器在候选循环里逐篇查作者(N+1,上限 100) |
| 测试 | 133 → 140;两条专门钉住「查询次数与条数无关」与「每条都带 coverThumb」 |
| 真实核对 | 推荐接口现在返回 `coverThumb`;所用临时标签关联已逐字还原 |

## v5 已完成:6 个 view 的分页切片收成一个模块(候选 04)

详见 `docs/architecture.md` §17。

| 项 | 说明 |
|---|---|
| 机制 | 新增 `src/useList.js`(状态 + `phase` 四态)与 `src/components/ListPager.vue`;取数函数只回答「这一页怎么取」,各 view 的筛选由闭包带走 |
| 修掉 | **「请求失败」不可表达** —— 15 个 view 只有 1 个 catch、零个错误态,失败被渲染成「还没有文章,快来写第一篇吧」;另修 UserProfile 把资料失败说成「用户不存在」、`http.js` 两种错误形状 |
| 测试 | 32 → 45;真实核对用 `Network.setBlockedURLs` 掐断接口,确认界面说「失败」而不是「没有数据」 |

## v5 已完成:两个小缺陷 D11 / D12

详见 `docs/architecture.md` §18。

| 项 | 说明 |
|---|---|
| D11 | 缺失静态资源由 500 改回 404(新增 `NoResourceFoundException` 处理器)。未知非静态路径仍 401 —— 那是既有鉴权策略,改它是个新决定 |
| D12 | LIKE 转义规则从 `ArticleService` 的私有方法提升为 `common/LikePattern`,补上后台三处漏掉的调用 |
| 测试 | 140 → 145 |

## v5 待办:C 节的七项(评审报告 C 节,尚未开工)

> **C 节七项全部完成**(2026-09-13)。剩下四条卡在设计决定上的,已按用户授权(「你定」)
> 逐条选定理由并实施:**§28**(截断与信封)、**§29**(池尺寸)、**§30**(心跳与取消)。

| # | 项 | 说明 |
|---|---|---|
| C1 | ~~viewer 应该作为依赖被接受~~ | **已完成**(§23)。读侧两处身份读取(一处 `catch → false`、一处直接判 `auth == null`)统一问 `ViewerSource`;匿名成为普通路径,测试不必再伪造登录。**注意**:报告引的行号已漂移,实际只剩两处;写侧 `SecurityUtil` 刻意不动 |
| C2 | ~~上传资产的命名约定散在三处~~ | **已完成**(§20)。`saveOriginal` 现在也写缩略图(让命名约定成为事实)、`writeThumb` 抽出来、`type` 白名单化。**残留**:解码不了的原图仍会 404 —— 关掉它需要「把缩略图 URL 存进文章」(表结构变更,需显式决策)或「读取时查文件是否存在」(投影不该做 I/O) |
| C3 | ~~工具结果没有信封~~ | **8/8**(§24、§27、§28)。最后两条的决定:上限从「切序列化后的文本」搬到工具层唯一出口,超预算就**整条丢弃数组尾部**并让 `count` 跟着改(永远不切开一个 token);`json()` 序列化失败改为**抛出**,走 `ChatService` 统一的失败路径。`ChatStreamListener` 已决定不做 |
| C4 | ~~对话生命周期与取消不在内核里~~ | **5/5**(§25、§26、§29、§30)。池改成 `core=max=4`、`queue=16`(同时 20 场在飞,与此前总容量一致);取消的信号只有一个 —— **写失败**,心跳(每 10 秒一个 `: ping` 注释帧、首次延迟 0)是让它按期发生的装置;轮间加一次存活检查,停止的生效上界从「剩下的所有轮次」缩到「当前这一轮」。**新发现**(§31):容器层还有两条 ERROR 长在这条路径上,未改,理由记在那里 |
| C5 | ~~用户显示的 fallback 约 20 份~~ | **已完成**(§21)。新增 `utils/person.js`(`displayName`/`displayAvatar`),替换 10 个文件的全部调用点;两处刻意保留(表单预览、表单默认值)。另记一条硬约束:进 `node --test` 的模块必须写 `.js` 扩展名 |
| C6 | ~~懒加载失败兜底横跨三处~~ | **已完成**(§22)。新增 `router/loadError.js`:用 vue-router 自己的 `isNavigationFailure` 取代裸启发式、单槽注册表改成订阅表(第二个订阅者不再静默顶掉第一个)、`app.config.errorHandler` 补上归属。**新结论**:ESM 模块表会缓存失败的 import ⇒ 只有整页刷新能恢复,这解释了为什么有三条重试路径(它们没有合并,见 §22.4) |
| C7 | ~~「登录后刷新用户」从未被调用~~ | **已完成**(§19)。`App.vue` 启动时调 `fetchMe()`;它的 `catch` 不再 `logout()` —— 401 的反应归 session 独家拥有,而网络故障不该把人踢出去。两条断言都做了对照 |

| ~~阅读体验包(详情页)~~ | **已完成**(§34) | 详情页补作者卡、上下篇(新端点 `GET /api/articles/{id}/neighbors`,与列表同可见性口径)、字数与阅读时长、摘要;相关推荐补封面/摘要/相关度;把 `summary`/`coverThumb`/`recommendScore` 这三个算好没人看的字段全部用上。评论仍为全量(分页留待单独一轮)。测试:后端 195、前端 104 |

### AI 对话留档(2026-09-14,§32)

| 项 | 说明 |
|---|---|
| 对话记录刷新/切页就丢 | **已完成**(§32)。先确认了它**不是回归**(从功能落地起就是个组件内的 `ref([])`,全库无 keep-alive),然后落进 `sessionStorage`(每标签页一份),载荷带 `userId` 防串号,`pending` → `revive` → 「回答未完成」保证半截回答不像完整回答 |
| 顺带 | 发出去的历史裁到最近 20 条(此前整份发,而后端 `@Size(max=50)`,聊长了必然 400);`api/ai.js` 不再把浏览器的英文 `err.message` 端给用户 |
| 测试 | 前端 68 → 97;真机 14 项(含三条对照) |

### 仍未覆盖的三处(明写在这里,不假装没有)

| 项 | 说明 |
|---|---|
| 前端 SFC / `modules` 层没有自动化测试 | `AiChat.vue` 里那些接线(心跳停表、留档落盘、「回答未完成」)只有纯模块被 `node --test` 覆盖;视图本身靠真机看过一遍(§32.5 的 14 项,脚本在 `%TEMP%`、没进仓库)。要真覆盖需要 vitest + @vue/test-utils —— 一个依赖决定 |
| 非分页列表仍无失败态 | `Dashboard.vue`、`admin/Tags.vue` 不在候选 04 的六个 view 里(§17.5) |
| 对话只活在当前标签页 | 关掉标签页就没了(`sessionStorage` 的选择见 §32.2)。换 `localStorage` 是一行;跨设备的历史需要服务端存 —— 那是新的表与接口,不在本轮 |


## v5 候选(来自 2026-09-13 架构评审,8 个候选里已完成 8 个)

完整论证与前后对照图见 `docs/architecture-review-2026-09-13.html`(HTML,含 Mermaid 图)。
以下是一行摘要,防止那份快照丢失时工作项也一起丢:

| # | 候选 | 强度 | 一句话 |
|---|---|---|---|
| 01 | ~~内容可见性收敛成一个 module~~ | Strong | **已完成**,见 `architecture.md` §10 |
| 02 | ~~让「谁在问、只读」穿过异步 seam~~ | Strong | **已完成**,见 `architecture.md` §11。AI 会话曾跑在没有 principal 的线程池上,导致 ADMIN 专属统计可被任意登录用户读到(D1,已实测),且「只读」承诺被 `incrViewCount` 违反(D2,已实测) |
| 03 | ~~给 router outlet 加 route identity~~ | Strong | **已完成**,见 `architecture.md` §12。outlet 无 `:key`,`/article/A → /article/B` 复用实例 → URL 变了正文不变 |
| 04 | ~~6 个 view 各自手搓的分页切片收成一个 deep module~~ | Strong | **已完成**,见 `architecture.md` §17。状态五元组 ×6、load 函数体 ×7、分页块 ×6;15 个 view 里只有 1 个 `catch`,「请求失败」当前不可表达 |
| 05 | ~~让 session 只有一个归属~~ | Strong | **已完成**,见 `architecture.md` §14。「带 token + 401 登出跳转」曾有 3 份实现 + 4 处 view 复制,7 处可重定向;`loginRequired()` 是死代码 |
| 06 | ~~把上游解帧搬出传输层~~ | Strong | **已完成**,见 `architecture.md` §13。唯一解帧的代码曾只能靠真实网络触达 → D8(错误帧被静默丢弃)与 D9(零参数工具调用可能被丢掉)长在无测试面的地方 |
| 07 | ~~SSE seam 升到「对话行为」这一层~~ | Strong | **已完成**,见 `architecture.md` §15。线格式知识曾被实现四次,且 `done` 事件客户端没处理 |
| 08 | ~~给文章→列表项的投影一个家~~ | Worth exploring | **已完成**,见 `architecture.md` §16。投影曾被写两次,第二份漏了 `coverThumb` 且有 N+1;前端用 `coverThumb \|\| cover` 把缺失掩盖了 |
