# blogSys 架构现状说明

> 生成于 2026-09-13，基线 `master` @ `1721796` + 一处未提交改动（`frontend/src/views/AiChat.vue`）。
>
> **2026-09-13 更新**：§4 的三条规则已收敛进 `com.blogsys.visibility` 模块，
> D3/D4/D5/D6 与草稿评论、推荐接口自相矛盾、三个写路径均已修复。
> 见文末 **§10 可见性重构：结果**。本文档其余部分是重构前的现状记录，仍然有效。
> 用途：复习底图，以及后续开发的坐标系。凡标 `file:line` 的都是实读代码确认过的位置，不是印象。
> 标 **【已实测】** 的条目是在本次会话中通过运行中的服务复现过的，不是推断。
>
> 配套文档：架构评审报告（deepening 候选与前后对照图）在系统临时目录，
> `architecture-review-20260913-075816.html`。

本文使用一套固定词汇，后文不再解释：

| 词 | 含义 |
|---|---|
| **module** | 任何有 interface 和 implementation 的东西：函数、类、包、跨层切片 |
| **interface** | 调用者为了用对它必须知道的一切：签名、不变量、调用顺序、错误模式、配置、性能特征 |
| **depth** | interface 上的杠杆率：调用者或测试每学一单位 interface，能撬动多少行为 |
| **deep / shallow** | 行为多而 interface 小 = deep；interface 和实现一样复杂 = shallow |
| **seam** | module 的 interface 所在的位置（Feathers） |
| **adapter** | 坐在 seam 上满足 interface 的具体实现 |
| **locality** | 维护者收益：改动、缺陷、知识、验证都收敛在一处 |

---

## 1. 一分钟速览

| 项 | 值 |
|---|---|
| 后端 | Spring Boot 3.4.1 / Java 17 / MyBatis-Plus 3.5.7 / Spring Security + JWT / MySQL 8 |
| 前端 | Vue 3.5（`<script setup>`）+ Vite 8 + Element Plus + Pinia + Axios + markdown-it |
| 规模 | 后端主代码 3,859 行 / 测试 768 行；前端 `src` 4,266 行 |
| 提交 | 40 次，2026-08-02 → 2026-08-09 |
| 测试 | 28 个后端单测（全 Mockito，**实跑全绿**），**无**前端测试，**无** CI |
| 文档 | `docs/api.md`（接口）、`docs/backlog.md`（v2/v3 状态与候选）、`docs/lessons.md`（踩坑记录）、`docs/schema.sql` |

**本地启动**（2026-09-13 已实测通过）：

```bash
# 依赖：本地 MySQL80 服务已在 3306 运行，库 blog_sys 已存在
cd backend  && mvn spring-boot:run      # → :8080，profile=local
cd frontend && npm run dev              # → :5173，代理 /api 与 /uploads 到 8080
```

`application.yml:13` 的密码占位为空，真实密码在 `application-local.yml`（已被 `.gitignore:25` 忽略，未跟踪，**没有**入库）。
启动后端时需保证 `AI_API_KEY`（或 `AI_LLM_API_KEY`）在进程环境里，否则 AI 助手会返回「AI 服务未配置」（`ChatService.java:36-39`）。

**运行时拓扑**：

```
浏览器 ──:5173──> Vite dev server ──proxy /api, /uploads──> Spring Boot :8080 ──JDBC──> MySQL :3306
                       │                                            │
                       └── 静态资源与 SPA 兜底                        └── 上传文件落盘 backend/uploads/
```

---

## 2. 后端模块地图

| 包 / module | interface | 现状 |
|---|---|---|
| `controller/**` | REST 端点，参数校验，调 service | 薄，符合预期；但**有一处规则藏在 controller 里**（§4.1） |
| `service/ArticleService` | 15 个 public 方法 | **429 行，全库最大、改动最多（10 次提交）**；混合了分页查询、可见性判定、标签同步、VO 装配、封禁过滤、封面缩略图推导 —— 且**零测试** |
| `service/CommentService` | 评论树、创建、删除 | 238 行；可见性逻辑与 `ArticleService` 有逐字重复 |
| `service/RecommendService` | `recommend(articleId)` | 178 行纯算法打分，输入输出干净，**是当前最 deep 的 module**；但列表项装配是 `ArticleService` 的复制品（§8） |
| `service/{Auth,User,Like,Tag,Admin}Service` | 各自领域 | 小且清晰；`AuthService` + 其测试是全库质量最高的一对 |
| `security/{JwtUtil,JwtAuthFilter,SecurityUtil,LoginUser}` | 认证与身份判定 | `SecurityUtil` 是 final + 私有构造 + 全静态，**任何 adapter 都满足不了它** —— 这是 §4.2 的根因 |
| `mapper/**` | MyBatis-Plus `BaseMapper` | 7 个空接口，无自定义 SQL |
| `common/GlobalExceptionHandler` | 异常 → HTTP 状态 + `Result` 包装 | 见 §7.1，兜底分支有实际缺陷 |
| `ai/**` | 见 §5 | 结构最好的子系统，但**「只读」承诺已被代码违反** |

**`mapper` 层是虚假的 seam**：7 个 mapper 接口全部只有 9 行、无自定义方法，SQL 由 MyBatis-Plus 的 `Wrappers` 在 service 里现场拼装。查询逻辑并不在 mapper 层，而是在 service 层内联 —— 这是后文多条发现的共同根因。

---

## 3. 前端模块地图

| module | 现状 |
|---|---|
| `api/http.js`（46 行） | Axios 实例 + 拦截器。**但它同时知道 UI 和路由**：401 时调 store、`router.push('/login')`、`ElMessage.error`（`http.js:19-44`） |
| `api/index.js`（63 行） | 纯端点映射，7 次提交的热点，无逻辑 —— 健康 |
| `api/ai.js`（97 行） | 手写 SSE 解析，独立于 Axios；但它重实现了 token 注入与 401 处理（`:7-20`），还把走 `http` 的 `recommendApi` 装了进来（`:95-97`） |
| `router/index.js`（60 行） | 路由表 + 守卫 + **一个模块级单槽 `loadErrorHandler`**（`:47-58`），由 `App.vue:49-60` 反向注册 |
| `stores/user.js`（39 行） | token/user/isLoggedIn/isAdmin；**`fetchMe()`（`:24`）全库无调用者**，`isAdmin` 永远来自 localStorage 快照 |
| `views/**`（15 个） | 每个 view 自带 `loading` / `page` / `total` / 错误提示的全套编排；**15 个 view 里只有 1 个 `catch`，零个错误态** |
| `components/**`（5 个） | `ArticleCard`、`CommentItem`、`ImageCropUpload`、`Lightbox` |
| `utils/*` | `format.js` 10 行、`theme.js` 15 行、`avatar.js` 28 行、`markdown.js` 55 行 |

**分页列表编排在 6 个 view 里各写一遍**（`page` 出现次数）：`admin/Users` 10、`admin/Articles` 10、`admin/Comments` 10、`Home` 9、`Profile` 7、`UserProfile` 5。

**路由级模块加载失败兜底横跨三处**：`router/index.js:47-58`（单槽 handler）+ `App.vue:49-60`（注册 + 1.2 秒后自动重试）+ `App.vue:120-127`（错误 UI）。重试手法是 `router.replace({path:'/', query:{t:Date.now()}})` 后立刻 `replace` 回当前路径（`App.vue:41-45`）—— 这是为绕开 Vite 运行中重新预构建的 workaround，见 `docs/lessons.md`。`App.vue` 共 10 次提交，其中 4 次是这套机制。

---

## 4. 核心发现：三条贯穿性规则，目前都没有家

**平台有三条业务规则，被以 3–4 种不同机制、散落在 7–9 个地方重复表达，而每条规则本身没有对应的 module。**
这不是整洁度问题 —— 重复已经产生了可观察的错误行为。

### 4.1 内容可见性（封禁作者的内容对非管理员不可见）

同一条规则，五种表达方式：

| 表达方式 | 位置 |
|---|---|
| 内联原始 SQL 子查询 | `ArticleService.java:57` `ACTIVE_USERS_SQL = "SELECT id FROM users WHERE status = 0"`，用于 `:63`（首页+搜索）、`:97`（热门） |
| 单实体布尔判定 | `ArticleService.java:193` `isAuthorBanned`，用于 `:127`（详情），且写成 `&& !SecurityUtil.isAdmin()` |
| 批量 id 集合过滤 | `ArticleService.java:182-191` `bannedUserIdsOf`，用于 `:173-177`（收藏） |
| **逐字复制的第二份** | `RecommendService.java:115-121`，与上一条函数体相同 |
| **逐字复制的第三份** | `CommentService.java:58-70` `filterBanned`，评论版 |
| 登录闸门 / 登录拒绝 / 主页拒绝 / 封禁保护 | `JwtAuthFilter.java:39` · `AuthService.java:47` · `UserService.java:47` · `AdminService.java:79` |
| **为副作用取数** | `UserController.java:65` `userService.publicProfile(id);` —— 返回值被丢弃，这一行就是「主页过滤」的全部实现 |

**来历**：`git show --stat c9fa9f5`（提交「封禁用户内容下线」）只动 4 个文件、+42/-2 就覆盖了 7 个面；而 `RecommendService` 的那一份是在**下一个提交** `0c0a940` 里靠复制粘贴进来的。

**已经背离出的四个错误行为**：

| # | 行为 | 位置 |
|---|---|---|
| D3 | **RSS 完全没有这条规则** —— 被封禁作者的文章仍在公开订阅源里。这里还写了字面量 `1` 而不是 `ArticleStatus.PUBLISHED` | `RssController.java:28-32` | **已修** |
| D4 | **管理员豁免只在一处实现** —— 管理员能打开被封禁作者的文章，却看不到它的评论 | `ArticleService.java:127` vs `:63`、`:173-178`、`CommentService.java:55` | **已修** |
| D5 | **标签计数虚高** —— `selectList(null)` 把草稿和封禁作者的文章都算进去，标签云于是展示永远不会出现在列表里的文章数；删除确认弹窗还把这个数字念给管理员听 | `TagService.java:29` · `Home.vue:148` · `admin/Tags.vue:40` | **已修** |
| D6 | **先分页后过滤** —— 收藏列表先 `subList(from, to)` 再过滤封禁作者，而 `total` 用的是过滤前的 `favorites.size()`；页内条数会少于 `size` 而 total 虚高。它也是全库唯一绕过 `selectPage` 的列表 | `ArticleService.java:168-178` | **已修**（还一并修掉了没写进 D6 的草稿泄漏） |

**另一半规则也重复**：`canViewDraft` 在 `ArticleService.java:209-216` 与 `CommentService.java:72-79` **函数体完全相同**（含同样的 `catch (BizException e) { return false; }`）。

**「已发布才可见」重复 5 次**：`article.getStatus() != ArticleStatus.PUBLISHED.getValue()` 出现在 `ArticleService.java:146`、`CommentService.java:45`、`CommentService.java:117`、`LikeService.java:26`、`RecommendService.java:51`，每次独立判断、各自抛 404。

**D12（疑似缺陷）**：`escapeLike`（`ArticleService.java:295-297`）只被 `page`（`:75`）调用；`adminPage`（`:109-111`）用的是未转义 `keyword`。同一个搜索框，两个接口对 `%` `_` 的行为不一致。

**做删减测试**：把上述 9 处封禁判定、5 处发布判定、2 份 `canViewDraft`、3 份批量过滤删掉，复杂度**收敛**而不是转移 —— 这正是值得深化的信号。

### 4.2 状态码是魔法数字，且两个语义相反的状态共用了数值 1

- 文章状态有枚举：`common/ArticleStatus.java`（`DRAFT(0)`、`PUBLISHED(1)`）。**但 `RssController` 连它都没用**，写的是字面量 `1`。
- **用户状态没有任何枚举** —— 「已封禁」全库写作 `Integer.valueOf(1).equals(user.getStatus())`，出现在 7 个文件（`JwtAuthFilter:39`、`ArticleService:188,195`、`CommentService:64`、`RecommendService:118`、`AuthService:47`、`UserService:47`、`AdminService:79`），而在 SQL 里同一事实写作 `status = 0`（表示正常）。
- 于是 `PUBLISHED = 1` 与「已封禁 = 1」数值相同、语义相反。这是一个真实存在的阅读陷阱。

### 4.3 viewer 是环境状态，不是被接受的依赖

`SecurityUtil.currentUserId()`（`:12-18`）在匿名时**抛 401**，而调用方想要的恰恰是「可选 viewer」，于是把它当控制流用：

- `ArticleService.java:198-207`（favorited）、`:209-216`（draft）、`CommentService.java:72-79` 都是 `try { … } catch (BizException e) { return false; }`。
- 同一个问题在同一个文件里隔二十行有第二种写法：`:400-408` 直接判 `auth == null`。
- 测试只能靠改线程局部变量伪造登录：`CommentServiceTest.java:43-44`、`LikeServiceTest.java:40-41`，**且都不清理**。
- 后果：三处 try/catch 的匿名分支与 `AdminService` 的守卫，**全部零覆盖**。「未登录访客看到 liked=false、favorited=false、草稿 404」这条行为没有任何断言。

---

## 5. AI 助手内核现状（`com.blogsys.ai`）

结构上是全库最好的子系统，也是唯一一个真 seam 有多个 adapter 的地方。但它的对外承诺已经被代码违反。

**数据流**：

```
POST /api/ai/chat (SSE)
  └─ ChatController          起 AsyncContext(setTimeout 0)，转 ChatRequest → List<ChatMessage>，丢给 chatExecutor
      └─ ChatService.chat    注入 system prompt，裁剪历史到 20 条（:42-43）
          └─ runToolLoop     最多 4 轮（:21）
              ├─ OpenAiClient.chatStream(msgs, tools, listener)   ← 真流式 HTTP
              │    ├─ onContent   → 立刻转发 SSE message 帧
              │    └─ onToolCalls → 收集工具调用
              ├─ 有工具调用 → executeTool → 结果作为 tool 消息喂回，下一轮
              └─ 无工具调用 → 结束
```

### 5.1 「全部只读」已被违反 【已实测】【已由 §11 修复】

`docs/api.md:86` 声明工具「全部为只读操作」。实际上：

- **D2 · 写入副作用**：`ArticleDetailTool.java:45` → `ArticleService.detail(id)` → `ArticleService.java:130` `articleMapper.incrViewCount(id)`。
  而浏览量正是 `getHotArticles` 的排序依据（`ArticleService.java:93-102`）—— **AI 会把自己问出来的文章加热**。
  实测：以普通用户请求「查看 id=17 的文章详情」，AI 调用 `getArticleDetail`，文章 17 的 `view_count` 由 **6 变成 7**，全程没有人类打开过页面。

- **D1 · 越权读取**：AI 会话跑在 `chatExecutor` 的线程池上（`ChatController.java:47-49`），
  `JwtAuthFilter` 只把 principal 放进了 servlet 线程的 `SecurityContextHolder`（`JwtAuthFilter.java:46`），
  全库没有 `DelegatingSecurityContextExecutor`／`WebAsyncManager` callable／策略覆写 —— **上下文不传播**。
  于是 `SiteStatsTool.java:35` 直接调用 `adminService.stats()`，而 HTTP 层 `GET /api/admin/stats` 是 ADMIN-only（`SecurityConfig.java:50`、`AdminController.java:36-38`）。
  实测：注册一个全新 USER 账号，直接打 `/api/admin/stats` 得到 **403**；同一个账号通过 AI 提问「全站一共有多少篇文章、多少位用户、多少条评论」，
  AI 调用 `getSiteStats` 并回答了全站统计（含「今日新增 1 位用户」—— 正是刚注册的探针账号）。

  → **鉴权只存在于 HTTP seam 上，不在 module 里。工具路径绕过了它。**

### 5.2 身份缺失造成的静默错误答案 【已由 §11 修复】

因为池线程上没有 principal，`SecurityUtil.currentUserId()` 会抛 401，而域层用 `catch → false` 把它吸收掉：

- `ArticleService.isFavoritedByCurrentUser`（`SecurityUtil.currentUserId()` 抛 401 → `catch` 吸收）→ **模型被告知「用户未收藏」**；
- `ArticleService.isLikedByCurrentUser`（写法不同，直接判 `auth == null` → `false`）→ **模型被告知「用户未点赞」**。

（本节原先还列了第三处「draft」。那一处在可见性重构中被吸收进模块，见 §10.4，已不存在。
原引用的 `:204-206`／`:213-215`／`:402-404` 也随 `ArticleService` 缩短而漂移，故改为按方法名指认。）

这是**错误答案而不是错误** —— 这就是为什么它从不报错、也从不被发现。

### 5.3 seam 与 adapter 的诚实清点

- **`SseWriter` 是真 seam**：生产 adapter `SseWriterHttp` + 测试 adapter `ChatServiceTest.CapturingWriter`。
  但它真，是因为测试需要它，不是因为有什么东西在 seam 两侧变化。
- **`ChatStreamListener` 是假 seam**：只有一个匿名内部类的实现。
- **`AgentTool` 有 6 个实现，但契约没有信封**：参数强转被复制三次（`ArticleDetailTool.java:55-58` ≡ `RecommendArticlesTool.java:50-53`，`UserProfileTool.java:42-48` 内联同一逻辑）；
  schema 里没有 `required`（`ToolRegistry.java:37-42`），模型漏传 id 时 Java 异常消息会原样送给 LLM；
  错误是字符串拼接的 JSON（`ChatService.java:98`、`:108`）—— 驱动消息里一个引号就能造出坏 JSON；
  截断出现两次且都可能切在 JSON 字符串中间（`ChatService.java:110-112` 截 4000、`ArticleDetailTool.java:50-51` 截 2000）。
  `AbstractTool`（`:11`）没有任何抽象成员，只是命名空间，其 `json()`（`:13-19`）把序列化失败吞成**假成功**。
  `ToolRegistry` 用 `Collectors.toMap` 建表 —— 工具重名会在启动时直接崩。

### 5.4 解帧逻辑没有测试面 【已实测的代码事实】【已由 §13 修复】

唯一把上游帧变成事件的代码是 `OpenAiClient` 的私有内部类 `StreamAggregator`（`:80`），唯一入口 `accept(JsonNode)` 由 socket 喂（`:67`）。
测试整个 mock 掉 `OpenAiClient`（`ChatServiceTest.java:32`），用手工回调驱动（`:51-56`）—— **整个测试套件里一帧都没被解析过**。于是：

- **D8**：`OpenAiClient.java:97-100` 对 `choices` 缺失的帧直接 return，**包括 OpenAI 形状的 `{"error":…}`**（限流、内容过滤）。流随后读到 EOF，`finished` 保持 false，用户收到一个 `done` 和一片空白，没有任何错误事件。
- **D9**：`OpenAiClient.java:116-119,128` —— 「是否有工具调用」是从**参数分片**推断的（`if (finished && !callArgs.isEmpty())`）。参数为空或不带 arguments 的 delta 会被重分类为「没有工具调用」，助手消息里也不含这个调用，**模型永远不知道它被跳过了**。而零参数工具恰好是 `getSiteStats` 与 `getHotArticles`（`SiteStatsTool.java:29-31`、`HotArticlesTool.java:31-33` 返回空 property map）。
- 每轮新建 `HttpClient`（`:171-175`）—— 一次对话最多 4 个，无 keep-alive。
- 任何非 JSON 的 `data:` 行都抛 `BizException(502)`（`:164-168`）。

### 5.5 取消与生命周期不在内核里

- `AiExecutorConfig.java:16-20` core 2 / max 4 / queue 20 —— ThreadPoolExecutor 只在队列满后才扩容，所以**稳态并发是 2**，另外 20 个静默排队；每场对话可占该槽位 4 轮 × 120 秒。
- 拒绝时 `execute` 抛异常，lambda 从不执行、`complete()` 从不执行，而 `setTimeout(0L)` 让请求也没有期限 ⇒ **客户端收不到任何 SSE 错误**。
- 断连只有一种偶然信号：写 content 事件抛异常被转成 `StreamAbortedException`（`ChatService.java:66-70`）；写 tool 事件失败（`:102`、`:113`）会走成一条完整的 `log.error("AI chat failed")` 堆栈 —— 一次正常用户操作被记成服务故障。
- 轮与轮之间没有存活检查，**浏览器「停止」不会传到服务端**。没有心跳帧，响应头要到第一个 token 才 flush。

### 5.6 一件确实干净的事

`ChatService` 全字段 final、`messages` 是方法局部变量；`OpenAiClient` 无状态；`StreamAggregator` 每次调用新建；6 个工具都是无状态单例。
**并发对话之间不会互相污染** —— 这一块不用动。

### 5.7 历史窗口：三个地方互相矛盾

- `ChatController.java:58` `@Size(max=50)` → 超了返回 400；
- `ChatService.java:42-43` `MAX_HISTORY=20` → 静默丢最老的消息；
- `api/ai.js:43-46` 在 400 时**丢弃响应体**，只显示「AI 服务错误，请稍后重试」。

于是聊到 25 轮左右，用户会撞上一堵说不出原因的墙（**D10**）。

### 5.8 文档缺口

AI 子系统在 `docs/api.md` 只有 6 行；而 `docs/lessons.md` 全文 63 行、按类别整理，**一条 AI 相关的坑都没有** —— 这个子系统踩过的坑没有任何地方沉淀。

---

## 6. 测试与验证现状（缺口地图）

> **本节是 2026-09-13 的缺口快照。**「缺口地图」的意思是：它记录我动手之前站在哪里，
> 所以其中「零测试 / 无基础设施」这类判断指的是**那个时刻**。后端此后长到 185 个 `@Test`
> （27 个测试类），前端也从零到有。与后文各 `§NN`（尤其是 §12、§16–§32）冲突时，
> **一律以后文为准**，不要拿这一节去推断现在的测试面。

`mvn test` 于 2026-09-13 实跑：**28 个测试全绿**（ChatServiceTest 6、AuthServiceTest 5、CommentServiceTest 7、LikeServiceTest 3、RecommendServiceTest 7）。

但缺口比数字看起来大：

| 区域 | 状态 |
|---|---|
| `ArticleService`（429 行，**10 次提交，全库最热**） | ~~**零测试**~~ **已补**：`backend/src/test/java/com/blogsys/service/ArticleServiceTest.java`（13 个 `@Test`）。「最热却零测试」是本节当时最刺眼的一行，后来正是它被先补上 |
| `CommentService` | 有（7 个），但覆盖创建/删除，不含 `filterBanned` |
| `RecommendService` | 有（7 个），纯算法覆盖良好；**但看不到 N+1**（`userService` 是 mock） |
| `ai/ChatService` | 有（6 个），覆盖工具循环/异常/轮数上限 |
| `ai/OpenAiClient` 帧解析 · `buildPayload` · `SseWriterHttp` 组帧 · `ToolRegistry` · **全部 6 个工具** · `api/ai.js` 解析器 | **零测试** |
| 全部 controller / 安全 / 封禁可见性 / `AdminService` | **零测试** |
| 前端 | ~~**完全无测试基础设施**（无 vitest/jest，`package.json` 只有 dev/build/preview）~~ **已从零到有**：`frontend/package.json` 有 `"test": "node --test"`，8 个 `*.test.js` 共 **97 个用例**。仍然没有 vitest/jest —— 那是刻意的，加测试不该先加依赖（§12.2） |
| CI | 无（无 `.github/`） |

「改动最频繁的 module 测试最少」是这份地图里最刺眼的一行 —— 它同时也是 §4 里三条规则的主要宿主。两者是同一个问题的两面。

---

## 7. 改动前的现实约束

### 7.1 已确认缺陷速查

完整表格（含严重度）见评审报告 A 节。这里只列位置：

| # | 缺陷 | 位置 |
|---|---|---|
| D1 | AI 工具路径可读 ADMIN 专属统计（**已实测**） | `SiteStatsTool.java:35` · `SecurityConfig.java:50` |
| D2 | AI「只读」路径产生写入，且反噬热门排序（**已实测**） | `ArticleDetailTool.java:45` → `ArticleService.java:130` · `:93-102` |
| D3 | 封禁作者文章仍在公开 RSS 里 | `RssController.java:28-32` |
| D4 | 管理员看得到文章、看不到评论 | `ArticleService.java:127` vs `:63`、`CommentService.java:55` |
| D5 | 标签计数包含草稿与封禁作者 | `TagService.java:29` |
| D6 | 收藏列表先分页后过滤 | `ArticleService.java:168-178` |
| D7 | 点相关推荐 → URL 变了正文不变；Write 编辑模式发两次请求 | `App.vue:128-132` · `ArticleDetail.vue:146-151,232-244` · `Write.vue:125,178` |
| D8 | 上游错误帧被静默丢弃 → 空回答无提示 | `OpenAiClient.java:97-100,140-142` |
| D9 | 零参数工具调用可能被整个丢掉 | `OpenAiClient.java:116-119,128` |
| D10 | 25 轮左右撞上无解释的墙 | `ChatController.java:58` · `ChatService.java:42-43` · `api/ai.js:43-46` |
| D11 | 缺失静态资源返回 500 而非 404（**已实测**） | `GlobalExceptionHandler.java:49-53` |
| D12 | 同一搜索框，前后台对 `%` `_` 行为不一致 | `ArticleService.java:75` vs `:109-111` |
| D13 | **列表查询潜伏约 1000 倍性能问题** —— 见 §7.4（本次实测，与可见性重构无关但同处一条查询） | `ArticleService.java:59-81` · `:93-102` |

### 7.2 会咬人的地方（来自 `docs/lessons.md`，均为真实踩过的坑）

- 路由级 view **必须单根节点**，否则 `<Transition>` 离开动画抛错 → 触发路由错误 → 返回详情页必现「页面加载失败」。
- `router.onError` 会收到**导航取消**，必须用 `error.type !== undefined` 区分，否则正常返回也报错。
- Vite 运行中发现新依赖会重新预构建并让旧模块引用失效 → 白屏。因此 `vite.config.js` 里 `optimizeDeps.include` 必须全量列出依赖，**新增依赖时要同步加进去**。
- `cropperjs` v2 是 Web Component（`$` 前缀方法），v1 的记忆全部失效。
- `Integer == Integer` 只对 ±128 缓存池成立，状态判断一律 `Objects.equals`。
- 清除数据库后管理员不会被重新种子化（`DataInitializer` 只在启动时执行）：清库后需重启应用。
- Windows 下运行中的进程会锁住 jar，打包前先停进程。
- **AI 子系统未沉淀任何坑**（见 §5.8）—— 第一次改它的人没有前人经验可继承。

### 7.3 环境事实

- 本地 MySQL 占用 3306，所以 `docker-compose.yml` 把容器端口映射到 3307。
- `docker-compose.yml` 走容器内 MySQL（`mysql:3306`），与本地开发不是同一套数据。
- AI 环境变量：`AI_BASE_URL`（阿里云百炼 MaaS 兼容端点）、`AI_MODEL`（`qwen3.7-plus`）、API Key 需自行注入进程环境。
- **`stores/user.js:24` 的 `fetchMe()` 全库无调用者**，`isAdmin`（`:11`）直接读 localStorage 快照（`:7`）。
  管理员改动某人角色或封禁某人后，对方客户端在重新登录前仍按旧角色渲染。
  注意这**不是越权**：服务端 `JwtAuthFilter.java:39` 会直接拦截封禁用户，影响仅限于 UI 陈旧。

### 7.4 列表查询潜伏约 1000 倍性能问题（实测，与可见性重构无关）

2026-09-13 用临时表造假数据（2 万用户 / 6 万文章，已发布 90%）实测 `ArticleService.page` 那条查询形状：

| 写法 | 耗时 | 计划 |
|---|---|---|
| 今天的形式：不相关 `IN (SELECT id FROM users WHERE status = 0)` | **213 ms** | 扫 users(2 万) → 按 `idx_user` 嵌套循环 → **排序 53400 行** |
| 相关 `EXISTS` 形式 | 181 ms | **完全相同的计划** |
| 给 `users` 加 `(status,id)` 索引 | 0.059 ms | 沿 `idx_created` 倒序、每行探主键、`LIMIT 10` 提前收工 |
| **零 DDL，只加 `/*+ INDEX(a idx_created) */`** | 0.171 ms | 同上 |
| 只加单列 `users(status)`（非复合） | 0.081 ms | 同上 |

三点结论：

1. **两种子查询形式性能等价**，MySQL 8 优化成同一计划 —— 所以选形式只该看可读性与能否绑定参数，不能拿性能当理由。
2. **新索引不是必需的**：单列也行；零 DDL 加 hint 也行。设计阶段「相关 EXISTS 必须配 `users(status,id)`」的推断不成立。
3. 真正的分水岭是**连接顺序**。索引在此不是被当作访问路径（内层用的仍是 `PRIMARY`），而是扰动了优化器的成本估算，把顺序翻了过来。

`idx_status` 那类索引在这里是**症状**，不是病根：病根是「先扫 users 再 join」这个错误估算。详情与教训见 `docs/lessons.md` 第六节。
本次重构**不动**这条查询的形状（保证不引入回归），性能问题作为独立候选另行处理。

---

## 8. 热点文件（40 次提交的改动频次）

| 文件 | 提交数 |
|---|---|
| `frontend/src/views/ArticleDetail.vue`（458 行） | 11 |
| `backend/.../service/ArticleService.java`（429 行） | 10 |
| `frontend/src/App.vue`（141 行） | 10 |
| `frontend/src/components/ArticleCard.vue`（142 行） | 10 |
| `frontend/src/components/ImageCropUpload.vue`（193 行） | 9 |
| `backend/.../service/CommentService.java`（238 行） | 8 |
| `frontend/src/views/Write.vue`（334 行） | 8 |
| `frontend/src/views/Profile.vue`（168 行） | 8 |
| `frontend/src/style.css`（417 行） | 8 |
| `frontend/src/api/index.js`、`router/index.js` | 7 |
| `backend/.../config/SecurityConfig.java`（81 行） | 7 |

热点与 §4 的分布高度重合：改得最多的地方，正是那条没有家的规则所在的地方。

---

## 9. 下一步的候选方向

按杠杆排序，详细论证与前后对照图见评审报告：

1. ~~**把内容可见性收敛成一个 module**~~ —— **已完成**，见 §10。
2. **让「谁在问、只读」穿过异步 seam** —— 严重度最高（D1 越权 + D2 违反只读承诺）。可见性 module 已经就位，正是这个候选需要工具去穿过的那个 seam。
3. **给 router outlet 加 route identity** —— 一处改动修掉 D7。
4. **前端分页列表编排收敛** —— 6 个 view 各写一遍，且「请求失败」当前不可表达。
5. **session 一个归属** —— 7 处可重定向收敛为 1。
6. **上游解帧搬出传输层** —— 解开 D8/D9，AI 内核第一次可以离线测。
7. **文章→列表项投影一个家** —— `RecommendService` 漏了 `coverThumb` 且有 N+1。
8. **SSE seam 升到行为层** —— 同一份线格式知识目前被实现四次。

性能债 **D13**（§7.4：列表查询的连接顺序）是独立候选，与可见性无关。

一次性收尾项：**D11**（404）与 **D12**（LIKE 转义）各自一次提交即可。

---

## 10. 可见性重构：结果（2026-09-13）

### 10.1 模块的形态

新增 `com.blogsys.visibility`，interface 只有五样东西：

| 成员 | 作用 |
|---|---|
| `articles()` | 查询拥有者。**没有「不过滤」的变体** —— 调用方拿到 `ArticleQuery` 时谓词已经在了 |
| `ArticleQuery.includingOwnDrafts()` | 唯一的方向性开关：登记后才纳入自己的草稿。**忘了写是安全的**（少看见，不多泄漏） |
| `articles().require(id)` | 单篇披露。不可见与不存在返回**逐字相同**的 404 消息（两处稍有差别就是存在性预言机） |
| `commentsOf(VisibleArticle)` | 参数是**权限令牌**而不是 id：拿不到令牌就调不了，文章规则与评论规则不可能在同一处分歧 |
| `canSeeAuthor(User)` | 作者本人是否可见。管理员豁免在此实现，因而处处一致 |
| `restrictToVisibleArticles(wrapper)` | 第三张表（收藏、标签关联）的外层收窄，用相关 `EXISTS` |

`Viewer` 是纯值（匿名是正常取值，不是异常）；`ViewerSource` 是模块读环境的**唯一**入口。

### 10.2 谓词随 viewer 改变形状，而不是改变某个值

| viewer | 谓词 |
|---|---|
| 管理员 | **完全省略** —— SQL 与「只写了调用方条件」逐字相同，可复用执行计划 |
| 匿名 / 登录 | `已发布 AND 作者未封禁`；封禁值经 `apply(true, "…{0}…")` **绑定**，不进 SQL 文本 |
| `includingOwnDrafts()` | 追加被括号包住的 `(已发布 OR 是我的)` |

调用方的条件一律经 `and(Consumer)` 落地，MyBatis-Plus 会把每个嵌套条件整体括起 ——
所以**调用方一个同级 `or()` 改不动谓词的含义**。这一条有测试钉住，也在真实数据上验过。

### 10.3 修掉的行为

| 缺陷 | 修复前 | 修复后 |
|---|---|---|
| D3 | RSS 播放被封禁作者的文章 | 不播放 |
| D4 | 管理员能开文章却看不到评论；看不到被封禁用户的主页 | 全域豁免，处处一致 |
| D5 | 标签报 1、点进去空 | 计数与实际列表逐条一致 |
| D6 | 收藏先分页后过滤、`total` 虚高、草稿泄漏 | 三者一起消失 |
| — | 推荐接口靶子只查 status 不查封禁（同一函数自相矛盾） | 与详情页同口径 |
| — | 草稿的评论接口 `200 + 空数组`（与「零评论」无法区分） | **404** |
| — | 点赞/收藏/评论在不可见文章上**成功** | **404**（ADR-0001 的对外行为变更） |
| — | 未知的 `users.status` 可经管理端写库并被当成正常用户 | 写入被 400 拒绝；读取 fail closed |

### 10.4 收敛的度量

- **封禁判定**:9 处散落 → 模块内 1 处；`Integer.valueOf(1)` 全库仅剩文档注释里的一句举例
- **全库唯一那段裸 SQL**（`ACTIVE_USERS_SQL`）与唯一一处 `inSql` 拼接：消失
- **逐字重复的函数**：`canViewDraft` ×2、`bannedUserIdsOf` ×3 → 0
- **`ArticleService`** 429 → 约 390 行；`CommentService` 238 → 208 行
- **测试** 28 → 94

### 10.5 两处**刻意的不改动**（都有 ADR 或代码注释）

1. **`/uploads/**`** 仍是公开静态资源（ADR-0002）：被封禁/草稿内容的封面图仍可凭 URL 取得。改它要动静态资源服务、缓存与 CDN，量级与本次不同。
2. **`AdminService.stats()` 的计数仍是原始汇报值**：把 `articleCount` 改成可见性计数会让管理员失去「12 篇里有 5 篇草稿」这个更有用的数字。已在代码里逐项标明哪些是汇报值、哪些是内容列表。

### 10.6 仍未覆盖的

- **可见性语义没有数据库层的测试。** 谓词活在 SQL 里，而单测的 mapper 是 mock（不分 viewer 一律返回桩定的行）。当前保障是三段拼起来的：机制测试（绑定/括号化）＋ 谓词文本断言 ＋ **一次性真实数据核对（8 个用例逐条比对期望行集）**。真正缺的是一条能反复运行的 DB 层测试（H2 `MODE=MySQL` 或 Testcontainers），那需要一次显式的依赖决策。
- **`restrictToVisibleArticles` 是模块唯一可被忘记的一环**（2 个调用点）。理由与代价写在它的 javadoc 里。
- **D1/D2（AI 工具路径的身份与只读）** 已由 §11 修掉；本节这次（可见性重构）确实没有让工具穿过模块去请求「谁在问」。

---

## 11. AI 工具路径的身份与只读：结果（2026-09-13）

候选 02。§5.1/§5.2 记的两个缺陷（D1 越权读取、D2 违反只读承诺）已修。下面是形态、证据与刻意不做的事。

### 11.1 机制：两处，各管一层

| 位置 | 做法 | 管什么 |
|---|---|---|
| `config/AiExecutorConfig` | `ThreadPoolTaskExecutor.setTaskDecorator` → `DelegatingSecurityContextRunnable(task, SecurityContextHolder.getContext())` | **域层**：池线程上的 `SecurityContextHolder` 变成提问者本人，于是 `visibility` 模块与 `SecurityUtil` 读到正确的人 |
| `ai/ChatController` → `ai/ChatService` | 请求线程上用 `ViewerSource` 解出 `Viewer`，显式传进 `chatService.chat(...)` | **工具层**：工具清单、以及「这次调用要不要执行」，都由提问者算出来 |

两处同源：都出自同一个请求线程上的同一次捕获，因此不可能分歧。

事实核对：`DelegatingSecurityContextExecutor` 在 **`org.springframework.security.concurrent`**，不是 `task` 包（`task` 包只有 `DelegatingSecurityContextTaskExecutor` / `…AsyncTaskExecutor`）；`spring-security-core` 已在 classpath，**没有新增依赖**。不用 `DelegatingSecurityContextAsyncTaskExecutor` 包装整个池：那会让 `ThreadPoolTaskExecutor` 不再是 bean，从而失去 Spring 的 destroy 回调。

全后端只有**一处**跨线程跳（`ChatController:47`），所以这一处补上就够了 —— 没有 `@Async`、`@Scheduled`、其它 `Executor` bean。

### 11.2 授权只有一个收口点

`ToolRegistry.definitions(viewer)` **没有无参重载** —— 与 `visibility.articles()` 同一个道理：调用方拿到清单时过滤已经在了，忘不掉。`AgentTool.isAvailableTo(Viewer)` 默认 `true`（站内文章、用户公开资料本就是公开语料），只有 `getSiteStats` 覆盖成 `viewer.isAdmin()`。

清单过滤只是 UX：模型可以凭空说出一个它没被给过的工具名。所以强制在 `ChatService.executeTool` 做第二次判定，并且「不可用」与「不存在」返回**逐字相同**的答复 —— 稍有差别就是「这儿有个你不能用的能力」的预言机。

`AdminService.stats()` 的计数**仍是原始汇报值**（§10.5 第 2 条），只是现在只有管理员问得到。

### 11.3 D2 靠结构修，不靠参数

`incrViewCount` 从 `ArticleService.detail` 移出，成为 `recordView(id)`；HTTP 层 `ArticleController.detail` 先调它、再读详情。

选结构而非法 `readonly` 标志：**读操作里不再有写，所以没有任何标志需要传错。** 标志方案总有被漏传的一天，而漏传的失败方向恰好是「写了」—— 不可接受的那一侧。

顺序是「先写后读」而不是反过来，为的是响应里的 `viewCount` 含本次，与拆分前逐字一致（有测试钉住）。

### 11.4 证据

**自动化** 94 → 111 个测试。新增的三处正是这次的核心：

- `config/AiExecutorConfigTest` —— 用**真实线程池**证明身份跟过去了（不是断言某段配置文本存在，那种断言在 TaskDecorator 被挪走后仍然会过）。含一条**对照**测试（裸池会丢身份），用来证明前一条断言不是空转；以及一条「池线程被复用时不会带走上一个人的身份」。
- `ai/tool/ToolRegistryTest` —— 用**真实的** `SiteStatsTool`，不是 mock：mock 的 `isAvailableTo` 默认 `false`，拿它断言「普通用户看不到」等于什么也没说。
- `controller/ArticleControllerTest` —— 用 `InOrder` 钉住「先 `recordView` 再 `detail`」。

**实测**（本地 MySQL；探针账号 id=11，事后已删除，`view_count` 已复原）：

| 探针 | 修前 | 修后 |
|---|---|---|
| 探针账号直接打 `GET /api/admin/stats` | 403 | 403（未变） |
| 同一个账号问 AI「全站有多少文章/用户/评论」 | 答出完整全站统计 | 整个流里**没有一个 `tool` 帧**；答「暂不支持全局统计」 |
| **管理员**问同一句话 | —— | `getSiteStats` 工具帧出现，答出 5 篇 / 5 位 / 10 条 |
| 探针账号问 AI「查看 id=17 的文章详情」 | `view_count` 6 → 7 | 工具返回 `viewCount:10`，库里**仍是 10** |
| `GET /api/articles/17`（HTTP 路径） | +1，响应含本次 | +1，响应含本次（未变） |
| 探针账号问 AI 查 admin 的草稿（id=16） | 不存在 | `{"error":"文章不存在"}` —— 与真不存在逐字相同 |
| **管理员**问同一篇草稿 | 不存在 | 返回完整草稿对象（管理员豁免生效） |

最后两行是「身份真的到达了**域层**」的判别性证据：同一篇文章、同一个工具、同一个问题，只有提问者不同，结果就不同。修之前两者都会退化成匿名 —— 那时连管理员问自己的草稿都会得到「不存在」。

### 11.5 刻意不做 / 仍未覆盖

- **D8/D9（`OpenAiClient` 帧解析与 SSE 契约）没碰。** 它们长在「整个测试套件里一帧都没被解析过」的地方，要修得先给 `OpenAiClient` 建测试面 —— 那是候选 06/07 的目标，与本段的失败模式不同。
- **`AgentTool.execute(Map)` 的签名没改。** 既然全后端只有一处跨线程跳（§11.1），而 `execute` 的唯一调用点就是 `ChatService.executeTool`，授权在那里收口即可；给 5 个用不到 viewer 的工具各加一个参数只是噪声。真需要时再改，改动是机械的。
- **§4.3（viewer 是被接受的依赖）只做了一半。** 工具层是显式的；域层仍走 `ViewerSource` 这个环境入口 —— 那是模块自己的设计（§10.1：它是模块读环境的**唯一**入口），现在由 TaskDecorator 喂正确的值。「全链路显式传参」没做，也不该塞进本段。
- **DB 层测试仍缺。** §10.6 说那需要一次显式的依赖决策，本次把依赖事实查清了，因而**明确不做**：Docker 守护进程未运行（`docker version`/`info`/`ps` 全部 exit 1），Testcontainers 现在跑不了；H2 本机只有 2.1.214 / 2.2.224 / 2.4.240，而 Boot 3.4.1 的 BOM 钉的是**不在本机**的 2.3.232（不写 `<version>` 就离线失败）；`docs/schema.sql` 对 H2 `MODE=MySQL` 还有 3 处确认的硬阻塞（`CREATE DATABASE`、库级 `DEFAULT CHARACTER SET`、内联 `ON UPDATE CURRENT_TIMESTAMP` ×2）。
- **AI 内核的取消与生命周期（§5.5）未动。**

---

## 12. Router outlet 的 route identity：结果（2026-09-13）

候选 03。§6 的 D7 已修：URL 变了正文不变。同时修掉一处做核对时踩出来的编辑页缺陷。

### 12.1 标识算在一个纯函数里

`App.vue` 的 `<component :is>` 原本没有 `:key`，于是**同一个 route record** 下只有 params 变化时
（`/article/3` → `/article/7`）Vue 复用同一个实例 —— 在意的 view 只能各自发明 watcher 去补偿，
而全库只有 `Home.vue` 补了，其余 view 各假定了一次「不会变」。

现在标识由 `frontend/src/router/identity.js` 的 `outletKey(route, depth)` 算，规则是
**该 outlet 自己那一层的记录** + 它消费的 params + query：

| 变化 | 顶层 outlet 的标识 | 效果 |
|---|---|---|
| `/article/3` → `/article/7` | 变 | 重挂载 —— 这是 D7 |
| `/` → `/?keyword=x` | 变 | 重挂载 —— 于是 `Home.vue` 的补偿 watcher 删掉了 |
| `/admin/users` → `/admin/articles` | **不变** | 父布局**不**重挂载；子 outlet（depth=1）的标识变，叶子照换 |

第三条是有意的：若按整条 URL 算，每切一个后台标签页都会连带重播一次过渡动画。
params 也只取「这个记录自己声明的」，子记录的 params 变化不该把父布局一起换掉。

**已知代价**：query 一律参与标识，所以搜索时 `Home.vue` 重挂载，会连带重取 `/api/tags`
与 `/api/articles/hot`（实测可见）。今天没有第二个记录用 query，所以记录在案而不为它加
配置开关 —— 身份必须只有一个主人。

### 12.2 前端第一次有了可反复运行的测试

前端此前**零测试基础设施**（只有 `dev`/`build`/`preview`）。现在 `npm test` = `node --test`，
**零新增依赖**（Node 22 自带测试运行器与 ESM 支持）。

这不是权益之计而是设计要求：`outletKey` 之所以能用 `node --test` 直接测，正因为它是
一个不依赖 Vue 的纯函数。凡是需要挂载 SFC 才能测的逻辑，本身就说明它没被抽出来。

10 个用例钉住的是上面那张表的每一行，尤其是「哪些变化**不该**重挂载」这条边界。

### 12.3 一次差点蒙混过关的核对

D7 是运行时行为，纯函数测试只能证明规则、不能证明「正文真的换了」，所以用系统 Chrome +
CDP 做了一次真浏览器核对。第一版核对**全绿**，但它是假的：

- 驱动方式是用 JS 动态创建一个 `<a>` 再 `.click()`。**vue-router 4 的点击拦截在 `RouterLink`
  内部，不是全局 document 监听** —— 那个点击触发了整页刷新，新文档当然是新内容。
- 更早一次「对照」（故意去掉 `:key` 再看是否失败）也因此全绿，我却先怀疑是缓存问题，
  用 `grep outletKey` 去验证改动是否生效 —— 而那个 `outletKey` 命中的是**遗留的 import 行**，
  不是模板里的 key 绑定。两步都错，方向都指向「结论已经对了」。

修好之后，核对里加了一条：**导航前往 `window` 上打个标记，导航后检查它还在不在**。
标记消失就是整页刷新，这条核对直接判为无效。改用
`app.config.globalProperties.$router.push` 驱动真正的客户端导航。

于是对照才显出分辨力：

| | 无 `:key` | 带 `:key` |
|---|---|---|
| `/article/3` → `/article/7` | URL 变了、标题仍是文章 3 的、**0 个请求** | 标题变「嵌套验证」、3 个请求 |
| `/?keyword=` 变化 | 搜索条不动、**0 个请求** | 跟着变、3 个请求 |

教训已记进 `docs/lessons.md`：**「通过了」不等于「测到了」**。

### 12.4 核对时踩出来的一处缺陷：编辑页会自己改数据

探针打开过 `/write/7`（一篇**已发布**文章）并让标签页留着，26 秒后文章 7 从「已发布」
变成了**草稿**，`updated_at` 被改。数据已复原。追下去是两个缺陷叠加：

1. `loadArticle` 先给表单字段赋值、之后才把 `loaded` 置 true。那个深度 watcher 在同步块
   结束后 flush 时 `loaded` 已是 true，于是把 `dirty` 判成 true —— **「打开编辑页、
   什么都不碰」也会触发自动保存**。
2. 自动保存是 `save(true, true)`，固定以 `draft: true` 保存。`originalStatus` 在载入时读
   了出来，却**从未被使用**。

合并后果：打开一篇已发布文章的编辑页停留 20 秒，那篇文章就被静默下架。

现在：载入时先 `await nextTick()` 再打开 `loaded`；自动保存单独实现，按文章原本的状态
保存且不跳转；新文章仍以草稿落库（用户没点发布，不该替他发布）。注意后端的 `draft` 是
`Boolean` 且 `null/false` 都表示发布（`ArticleService.resolveStatus`），所以必须**显式**
传状态，不能靠省略字段。

核对（26 秒窗口，直接读库）：打开编辑页什么都不碰 ⇒ `status` 与 `updated_at` 一字未动；
改一下摘要 ⇒ 自动保存确实落库，而 `status` 仍是 1。

### 12.5 仍未覆盖的

- **SFC 层没有自动化测试。** `outletKey` 的规则有单测，但「模板确实用了它」「6 个 view
  确实改用了新模块」这类接线只能靠 `npm run build` + 浏览器一次性核对。要让它们可反复
  运行需要引入 vitest + @vue/test-utils —— 一次显式的依赖决策，本轮没做。
- 12.3 里那两个一次性浏览器核对脚本没有进仓库（依赖系统 Chrome 路径与整套本地服务），
  只在本文与提交说明里记录了过程。**这是本仓库沿用的一次性核对模式，代价是它不能重跑。**

---

## 13. 把上游解帧搬出传输层：结果（2026-09-13）

候选 06。§5.4 的两处缺陷（D8 错误帧被静默丢弃、D9 零参数工具调用可能被丢掉）已修。

### 13.1 问题不是「类太大」，是没有测试面

`OpenAiClient` 一个类装了四件事：拼 payload、每轮 `new HttpClient`、读 socket 切 `data:` 行、
参数分片重组与结束判定。真正的后果是最后那件**只能靠一次真实 HTTP 调用触达**，
于是整个测试套件里一帧都没被解析过 —— D8 与 D9 就长在那里。

### 13.2 两个 module，一条很小的 interface

| module | interface | 归谁 |
|---|---|---|
| `StreamReducer` | `accept(JsonNode)` 喂帧 · `markDone()` · `finish()` | 帧的**含义**（增量、工具调用、结束、错误） |
| `ChatPayload` | `of(mapper, model, messages, tools)` → JSON 文本 | 请求体构造 |
| `OpenAiClient` | `chatStream(messages, tools, listener)` | 只剩**线格式**（`data:` 前缀、`[DONE]` 哨兵）与 HTTP 错误 |

`OpenAiClient` 仍然解析 JSON 文本 —— 那是线格式的一部分；它不再**解释**帧。

### 13.3 三件在 reducer 里被钉死的事

1. **错误帧有通道。** OpenAI 形状的 `{"error":…}` 以前直接 `return` 掉，流随后读到 EOF、
   `finished` 保持 false，用户收到一个 `done` 和一片空白。现在抛 502 并把上游 message 带出来。
2. **「有没有工具调用」只看调用条目本身，不再从参数分片推断。** 旧实现在 `complete()` 里
   用 `if (finished && !callArgs.isEmpty())` —— 零参数工具（`getSiteStats` / `getHotArticles`，
   property map 是空的）会被重新归类成「没有工具调用」，助手消息里也不含这个调用，
   **模型永远不知道它被跳过了**。
3. **被截断的流不再伪装成正常结束。** 既没有结束原因、也没有 `[DONE]` 时抛错，
   而不是走成 `onFinish()`（那又是「空白回答 + done」）。

顺带把 `HttpClient` 提成字段：此前每轮新建一个，一次对话最多 4 个且无 keep-alive。

### 13.4 证据

**自动化** 111 → 126。`StreamReducerTest` 12 条 —— D8、D9、分片拼接、多调用按 index 分开、
缺 id 的占位、截断、以及「既非错误也非增量的帧被忽略」；`ChatPayloadTest` 3 条 ——
其中「tools 为空时不该带 `tools` 字段」这条约定此前从没有人断言过。全部毫秒级，不碰网络。

**真实链路回归**（本地 MySQL + 真实 provider，未使用浏览器）：

| 问题 | 结果 |
|---|---|
| 纯文本 | 1s，0 个工具帧，`done` |
| 零参数 `getHotArticles` | 2 个工具帧（开始+结果），`done` |
| 零参数 `getSiteStats` | 2 个工具帧、真实数字、完整中文回答 |

### 13.5 仍未覆盖的

- **模型自己产出空回答，这一条没修也不该在这里修。** 回归时观察到：同一句提问会让模型
  吐出 19 个空 `content` 增量、正文为空；换一种问法即有正文（63 帧 / 147 字）。这是模型
  行为而不是解帧丢内容 —— 内容分支与改动前逐字相同，且工具往返后能正常拿到正文。
  但它呈现出**与 D8 相同的症状**（空白消息 + `done`），值得单独记为一条：
  「回答为空」目前与「回答是空白字符」不可区分。
- **没有心跳帧，响应头要到第一个 token 才 flush**（§5.5 原有）。
- §12.5 的 SFC 层测试缺口与一次性核对模式在此同样适用。

---

## 14. 让 session 只有一个归属：结果（2026-09-13）

候选 05。「带上 token；遇到 401 就登出并跳 `/login`」此前有 **3 份实现**（axios 拦截器、
fetch 助手、router 守卫）**加 4 处 view 复制**，7 个地方能重定向，其中一处
（`ArticleDetail.vue` 的 `loginRequired`）还是死代码。于是「会话过期之后会发生什么」，
没有任何一处能回答。

### 14.1 这个 module 不 import 任何东西

`src/session.js` 的全部依赖都是注入的 port：

```js
createSession({ readToken, clearAuth, currentPath, navigate, notify })
```

生产接线在 `session-instance.js`（localStorage 由 Pinia 持有、vue-router 负责跳转、
element-plus 负责提示）；测试接线是 `session.test.js` 里几个内存对象。**这才叫
「两个真 adapter 共用一个 seam」** —— 与 `SseWriter` 同理，不同的是这里的第二个 adapter
不是测试的产物，而是真实存在的第二种传输。

`session-instance.js` 用动态 `import('./router')` 拿 router：守卫要用这个 session，
静态 import 会成环。

### 14.2 三件事各只有一个主人

| 归谁 | 谁不再做这件事 |
|---|---|
| `authHeaders()` | `api/ai.js` 里那份逐字重建的请求头 |
| `unauthorized()` —— 已登出过就不再重复跳转（并发请求会同时拿到 401） | `http.js` 两处、`ai.js` 一处各自的 logout + push |
| `loginTarget()` —— 带上回来的路 | 7 处裸跳 `/login`（此前只有守卫带了 `redirect`，登录完回不到原页） |

view 只问一个问题：`requireLogin()`。`ArticleDetail` 两处、`CommentItem` 一处照此改写，
死代码 `loginRequired()` 删除。

顺带把 `recommendApi` 从 `api/ai.js` 挪到 `api/index.js` —— 它是普通 REST 调用，
停在 fetch module 里只是为了蹭一句 `import http`。

### 14.3 证据

**自动化** 10 → 21（`session.test.js` 11 条：token 注入、跳转目标三种情形、
401 反应含「并发只跳一次」、`requireLogin`）。

**接线核对**（系统 Chrome + CDP，只在未登录态操作，不写任何数据）：

| 探针 | 结果 |
|---|---|
| 未登录访问 `/me` | 跳登录页，`redirect` 解析为 `/me` |
| 未登录访问 `/write?x=1` | `redirect` 解析为 `/write?x=1`（编码没弄坏它） |
| 未登录在文章页点「点赞」 | view 不再自己跳，`redirect` 解析为 `/article/3` |

**为什么断言的不是 URL 字面量**：vue-router 会把守卫返回的字符串 location 解析后再生成
URL（`%2Fme` 正规化成 `/me`），而 `router.push` 那条路保留了 `encodeURIComponent` 的结果。
两条路的 URL 写法不同，但解析出来的 `redirect` 都正确 —— 所以断言的必须是后者。
第一版核对正是断言字面量，于是把一个**行为正确**的路径报成了 FAIL。
**测试写错与代码写错长得一样，区别只在你去读哪一个。**

### 14.4 仍未覆盖的

- **两条路发出的 URL 写法不一致**（守卫那条不带百分号编码，`push` 那条带）。
  今天两者都能正确解析，所以没有改；但它是一处「同一个意图、两种形态」的残留，
  真要统一应让 session 返回结构化的 location 而不是字符串。
- **`stores/user.js` 的 `fetchMe()` 仍无人调用**，`isAdmin` 依然来自登录那一刻的
  localStorage 快照（§C 节记录）。本候选只统一了策略，没有动这条。
- **401 之后的界面状态没有核对**：只验了跳转与 `redirect`，没有验「登出后页面上的
  点赞按钮确实回到未登录态」。
- §12.5 的 SFC 层测试缺口与一次性核对模式在此同样适用。

---

## 15. 把 SSE seam 升到「对话行为」这一层：结果（2026-09-13）

候选 07。§5.3 里那条「`SseWriter` 是真 seam，但它真，是因为测试需要它」在此被改掉了层级 ——
**要改的是层级，不是再加抽象。**

### 15.1 seam 画错了层，知识就守不住

原来的 seam 是 `SseWriter.event(name, dataJson)`：只抽象了**帧外壳**，没抽象**载荷**。
于是四个事件名、四种载荷形状与它们的顺序，必须被每个调用者、adapter、测试、以及浏览器
解析器各自知道一遍 —— 这份知识当时被实现了四次：

| 副本 | 位置 |
|---|---|
| ① 拼载荷 | `ChatService`（`sendToolEvent` / `sendMessage` / `sendError`） |
| ② 组帧 | `SseWriterHttp` |
| ③ 再解析回来 | `ChatServiceTest.CapturingWriter` |
| ④ 浏览器 | `frontend/src/api/ai.js` 的 `dispatch` |

### 15.2 新的分层

| module | 拥有什么 |
|---|---|
| `ChatEgress`（interface） | **行为**：`assistantDelta` · `toolStarted` · `toolFinished` · `failed` · `ended` |
| `SseProtocol` | **线格式**：事件名常量 · 载荷形状 · 组帧。纯函数，不认识 servlet |
| `SseWriterHttp` | 只剩「把算好的文本变成字节」 |
| `frontend/src/api/aiEvents.js` | 浏览器那一侧的线格式归属 |
| `docs/api.md` 那张表 | 跨语言契约的**单一出处** |

`SseWriter` 随之删除。`ChatService` 现在说行为；它的测试 adapter 换成 `Recorder`，
断言的是「工具调用的开始与结束各说了一次」「回答是什么」「有没有 `ended`」——
不再把自己刚序列化出去的 JSON 解析回来。

### 15.3 `done` 不再是装饰

前端此前把 `done` **解析了却从不处理**：`onDone` 一律在读到流末尾时无条件触发。
于是一个被截断的回答与一个完整回答在界面上长得一模一样 —— 这是 D8 那一类静默失败
在客户端的双胞胎。

现在 `done` 是唯一的正常收尾凭证：`finish()` 返回 `done` / `error` / `truncated`，
只有 `done` 才触发 `onDone`，`truncated` 会如实报「连接中断」。契约写进了 `docs/api.md`。

### 15.4 证据

**自动化** 后端 126 → 133（`SseProtocolTest` 7 条：四种事件的帧形状、每帧以空行结束、
以及四个事件名本身 —— 它们是跨语言契约，改名必须是一次会红掉的决定）。
前端 21 → 32（`aiEvents.test.js` 11 条，其中三条专门钉收尾：收到 `done` / 收到 `error` /
两者都没有）。

**跨语言集成核对**：把**真实服务端**的 SSE 字节按 **7 字符碎片**喂给**前端解析器**
（碎片喂法顺带压了「一行被拆到多次 push」那条路径）：

| 问题 | 结果 |
|---|---|
| 纯文本 | HTTP 200 `text/event-stream`，8 个 `message` 帧，收尾 `done`，回答完整 |
| 带工具调用 | 事件序列 `[tool:getHotArticles, tool:getHotArticles(result), done]`，55 个 `message` 帧，收尾 `done`，回答完整 |

这条核对同时证明了两件事：两侧的名字与载荷**真的对得上**，以及「只有 `done` 才算完整」
在真实流上成立。它是本例中唯一能跨语言验证契约的手段 —— 两侧没有可共享的代码。

### 15.5 仍未覆盖的

- **`ChatStreamListener` 仍是「假 seam」**（§5.3）：只有一个匿名内部类的实现。
  本候选没有动它 —— 它已经在正确的层（行为），没有必要为了对称再加一个 adapter。
- **模型的推理尾巴会进正文**：核对时观察到回答里带出 `</think>`。那是 provider 把
  reasoning 混在 `content` 里，不是解帧或组帧的问题；本仓库既没有剥离它，也没有
  在契约里提到它。若要处理，应在 `StreamReducer` 之上做 —— 那是一个新决定，不是缺陷修复。
- **没有心跳帧，响应头要到第一个 token 才 flush**（§5.5 原有）。
- §12.5 的 SFC 层测试缺口与一次性核对模式在此同样适用。

---

## 16. 给文章→列表项的投影一个家：结果（2026-09-13）

候选 08。

### 16.1 两个 assembly，第二份已经在漂移

`ArticleListItemVO` 的组装此前被写了两次：

| 副本 | 位置 | 作者解析 | `coverThumb` |
|---|---|---|---|
| ① | `ArticleService.copyBase` + `attachUserAndTags` + `findTagsByArticles` | 一次批量 | ✅ |
| ② | `RecommendService.build` | **候选循环里逐篇查**（上限 100） | ❌ 漏了 |

这份漂移一直没被发现，因为三件事同时成立：前端用 `article.coverThumb || article.cover`
把它悄悄降级掩盖了；单测的 `userService` 是 mock，**数不出查询次数**；而两份实现在各自的
测试里都「通过」。

### 16.2 一个 module，两条路径

`ArticleListItems`：

| interface | 含义 |
|---|---|
| `of(articles)` | 批量投影。作者与标签**各查一次，与条数无关** —— 这就是「列表」在这里的全部含义 |
| `fill(article, vo)` | 单篇填充，用泛型把详情 VO（它是子类）原样还回去 |

`ArticleService` 丢掉 `copyBase` / `attachUserAndTags` / `attachAuthorAndTags` /
`findTagsByArticles` / `coverThumbOf` 与 `userService`；`RecommendService` 丢掉 `build`
与 `userService`，流程改成「先算分、排序、截到前 N，**再一次性投影**」——
于是查询次数与返回条数无关。缩略图命名的回退规则（webp 与非 `/uploads` 路径返回原图）
也随之只有一个主人。

前端 `ArticleCard` 改用 `article.coverThumb`，删掉 `|| article.cover`：那张回退规则属于
投影模块，前端再实现一遍等于给同一个决定留了两个主人 —— 而且正是它掩盖了这份漂移。

### 16.3 证据

**自动化** 133 → 140。`ArticleListItemsTest` 里两条钉住的是**曾经静默失效的性质**：

- 五篇文章、五位作者 ⇒ `findByIds` 恰好 **1 次**、标签查询恰好 **1 次**（把次数写死成断言，
  这是那笔 N+1 唯一的防线）；
- 每一条都带 `coverThumb`（含 webp 退回原图、空封面两种情况）。

另加 `RecommendServiceTest` 一条：推荐结果与其它列表形状一致。
`ArticleServiceTest` / `RecommendServiceTest` 的构造随之改传投影模块 —— 用的是同一批 mock，
所以投影的查询次数在那边也能被数出来。

**真实核对**（本地 MySQL；数据不是合成的，但推荐需要标签重叠，而现有数据里只有文章 3 与
草稿 16 有标签，故临时加一条关联，事后逐字还原）：

```
事前 article_tags = 3-3 3-4 3-5 16-14
临时给文章 10 打上 #随笔(tag_id=5,与文章 3 重叠)
GET /api/articles/3/recommend?size=5 →
  id=10  cover=/uploads/a25c...png  coverThumb=/uploads/a25c...-thumb.jpg  tags=#随笔  score=5
有封面却缺 coverThumb 的条目: 0        ← 迁移前这一条必然缺
删掉关联后 article_tags = 3-3 3-4 3-5 16-14
```

### 16.4 仍未覆盖的

- **上传资产的命名约定仍散着**（§C 节）：写方在 `UploadController` 生成 `base-thumb.jpg`，
  读方在投影模块用正则反推，而 `saveOriginal` 对 jpg/png/gif 之外的原图根本不写缩略图 ——
  那时投影会合成一个从未写入的文件名。本候选把**读**这一侧收成了一处，**写**那一侧没动。
- **`tags` 字段的形状仍是「有顺序的字符串数组」**，顺序来自关联表而没有任何显式约定；
  两条路径现在同源，所以一致，但顺序本身仍然不是契约。
- 推荐的候选打分仍会对全部候选跑一次 `tagsOf`（批量，与条数无关）—— 这不是 N+1，
  但候选上限 100 意味着那一步的 SQL 每次都扫 100 篇的关联；要优化应连同 §7.4 的连接顺序一起看。
- §12.5 的 SFC 层测试缺口与一次性核对模式在此同样适用。

---

## 17. 把 6 个 view 各自手搓的分页切片收成一个模块：结果（2026-09-13）

候选 04。

### 17.1 真正的代价不是重复，是失败无法表达

6 个 view 各自持有一份相同的五元组（`page`/`size`/`keyword`/`loading`/`total`）、各自一份
几乎逐字相同的 `load` 函数体、各自一块逐字相同的分页模板。但**重复只是症状**：

> 15 个 view 里只有 1 个 `catch`、零个错误态，而 `http.js` 早已弹过 toast 并 reject。
> 请求失败时 `loading` 被清掉、数组仍是 `[]`，于是首页把它渲染成
> 「还没有文章，快来写第一篇吧」，并附上一个号召按钮。

「请求失败」在这套架构里**不可表达** —— 这是本候选要修的东西，不是顺手清理重复。

### 17.2 两个新东西

| 位置 | 拥有什么 |
|---|---|
| `src/useList.js` | `records` `total` `page` `size` `keyword` `loading` `failure`，以及 `phase` = `loading`/`failed`/`empty`/`ready` 四者之一 |
| `src/components/ListPager.vue` | 分页块。**不接受 `v-model:current-page`** —— 页码的唯一写入者是 `goTo` |

取数函数只回答「这一页怎么取」；各 view 自己的筛选（标签、状态、用户 id、当前 tab）
由闭包带走 —— 模块不知道这些概念。两个动作：`search()`（回第 1 页再取，这段此前在 3 个
view 里各写一遍）、`goTo(n)`。

另导出 `messageOf()`：`http.js` 对业务错误与 HTTP 错误给两种形状，这里是那处不一致的
**适配点**，视图只需要问「给用户看什么」。

### 17.3 视图侧：四处分支取代两处

公开页（Home / UserProfile / Profile）用 `phase` 分四支：loading / failed（带重试）/
有数据 / empty。后台三个表格页在表格上方用 `el-alert` 报失败 + 重试 —— 不破坏
`el-table` 自己的结构与「暂无数据」态。

顺带两处同源问题：

- `UserProfile` 的资料加载失败此前被渲染成「用户不存在或已注销」，与列表是同一类错误；
  现在有自己的失败态与重试。
- `http.js` 的 HTTP 错误分支此前抛**原始 axios error**，与业务错误分支的
  `new Error(message)` 形状不同；现在一律 `Error(message)`，原始响应挂在 `.response` 上。

### 17.4 证据

**自动化** 32 → 45。`useList.test.js` 13 条，其中三条是这个模块存在的理由：
「失败后 `phase` 为 `failed` 且**不保留上一次的列表**」、「不把异常抛出去」、
「重试成功后失败态消失」；另有「空结果是 `empty` 而不是 `failed`」与 `messageOf` 的
两种形状。

**真实浏览器核对**（系统 Chrome + CDP，全部只读）：

| 探针 | 结果 |
|---|---|
| 首页正常加载 | 4 个文章链接 |
| `Network.setBlockedURLs` 掐断列表接口后点标签 | 出现「网络错误」+ 重试按钮，**且不再出现「还没有文章」** |
| 恢复网络后点重试 | 回到正常列表 |
| 带 token 进 `/admin/articles` | 共享分页组件在位，表格 5 行，显示「共 5 条」 |

### 17.5 仍未覆盖的

- **`Dashboard.vue` 与 `admin/Tags.vue` 没有改用这个模块。** 它们是「整份列表、无分页」，
  取数函数返回数组而不是 `{records, total}` —— 是另一种形状。它们的失败同样会被渲染成
  「没有数据」，要修需要一个姊妹模块（单次取数），而不是把两种形状都塞进 `useList`。
- **分页仍不在 URL 里。** 刷新或前进后退会丢掉页码 —— 这与 §12 的 route identity 是同一个
  话题的下一层（页码进 query 就要参与标识）。本轮没做。
- **`Profile.vue` 的 tab 状态同样不在 URL 里**，「我的收藏」刷新后回到「我的文章」。
- **本轮的核对脚本里有两次「断言写错、行为正确」**（走错路径没断言落点、正则多转义一层），
  都报成 FAIL。第三次与第四次了 —— 见 `docs/lessons.md` 方法论第 11 条。
- §12.5 的 SFC 层测试缺口与一次性核对模式在此同样适用。

---

## 18. 两个小缺陷：D11 与 D12（2026-09-13）

§7.1 说它们「各自一次提交就能收掉」。收掉了。

### 18.1 D11：缺失静态资源是 404，不是 500

`GlobalExceptionHandler` 没有 `NoResourceFoundException` 的处理器，于是「这张图不在」
落到 `Exception` 兜底分支 —— 用户看到 500，日志里多一条 `Unhandled exception`，
而真正的原因只是一个拼错的路径。这类请求会很多（浏览器、爬虫、失效外链），
所以它必须是安静的。

实测：`/uploads/does-not-exist.jpg` → **404** `{"code":404,"message":"资源不存在"}`；
存在的封面仍 200。

**一处没动的邻居**：`/totally-unknown-path` 返回 **401** 而不是 404 —— 因为
`SecurityConfig` 的 `anyRequest().authenticated()` 在资源解析之前就拦下了它。
那是既有的鉴权策略（未登录不看任何东西），不是 D11 的范畴；D11 说的是
**permitAll 的静态资源**。要改它得先决定「未登录时未知路径该说 401 还是 404」，
那是一个新决定。

### 18.2 D12：一条规则，四个调用点

LIKE 的转义规则此前只长在 `ArticleService` 的一个私有方法里，于是**公开搜索转义了、
后台三处没有** —— 而没有一处是「错的」，只是各写各的。后果不是注入（值始终走参数绑定），
而是**通配符被当字面量**：搜「%」命中全部内容，而用户以为自己搜的就是这个符号。

新增 `common/LikePattern`，四个调用点共用：`ArticleService.page` / `adminPage`、
`AdminService.users`、`CommentService.adminPage`。

实测（本地 MySQL）：

| 搜索词 | 公开 | 后台 |
|---|---|---|
| `%` | 0 条 | **0 条**（修前：命中全部文章） |
| `测试` | 1 条 | 2 条 —— 多的一条是草稿，那是可见性差异，不是 LIKE 差异 |

### 18.3 证据

**自动化** 140 → 145。`LikePatternTest` 3 条 —— 其中「反斜杠必须先转，否则它会把新加的
转义符再转一遍」是一条顺序要求；`ArticleServiceTest` 加两条断言**公开与后台绑定同一个
模式串**（这才是 D12 的内容：不是某一边错，是两边不一致）。

写这两条时踩到一次 MyBatis-Plus 的细节：`like()` 会自行在两端补 `%`，所以绑定值是
`%100\%\_x%` 而不是 `100\%\_x`。第一次断言按后者写，两条都红 —— 又是「断言写错、
行为正确」（第五次）。失败信息里两条的值完全相同，恰好就是我想钉住的性质。

### 18.4 C 节的进展

C 节七项里 **C7 已在 §19 收掉**，其余六项（viewer 作为被接受的依赖、上传资产命名约定
散在三处、工具结果没有信封、对话生命周期与取消、用户显示 fallback 约 20 份、
懒加载兜底横跨三处）尚未开工。前两项同时是 02 与 08 的「仍未覆盖」——
见 `docs/backlog.md` 的 v5 待办。

---

## 19. C7：让「登录后刷新用户」这条路径真的被走到（2026-09-13）

### 19.1 一条写好了但从没被调用的路径

`stores/user.js` 的 `fetchMe()` **全库无调用者**，于是 `isAdmin` 完全来自登录那一刻写进
`localStorage` 的快照 —— 管理员改了某人的角色、或封禁了某人之后，对方客户端在重新登录前
仍按旧角色渲染。

不是越权：`JwtAuthFilter` 对封禁用户会直接拦截，所以影响限于**界面陈旧**。
但「有一份服务端事实可用，却只用本地快照」这件事本身就是个坑 —— 它的症状是
「明明改过了，他那边还显示管理员」。

### 19.2 两处改动

1. `App.vue` 的 `onMounted` 调用 `store.fetchMe()`，用服务端的事实刷新快照。
2. `fetchMe` 的 `catch` 不再 `this.logout()`：
   - 401 的反应已经归 `session`（§14）**独家拥有**，这里再 logout 一次是第二个主人；
   - 其它错误（网络抖动、500）更不该把用户踢出去 —— 此前**一次网络故障就等于强制登出**。
   - 刷新失败的正确结果是「快照保持原样」，而不是「当作没登录」。

### 19.3 证据

**自动化** 前端 45 条仍全绿（本次改动落在 Pinia store 与 `App.vue` 的接线，
SFC 层无测试面 —— 见 §12.5 的缺口）。

**真实浏览器核对**（探针账号在库里提权/降权，事后删除，`users` 回到 4）：

| 阶段 | 操作 | 结果 |
|---|---|---|
| 一 | 快照与事实都是 ADMIN | 头部有管理员入口 |
| 二 | 库里降回 USER 后刷新页面 | 管理员入口**消失**（UI 跟着服务端走） |
| 三 | 掐断 `/api/users/me` 后刷新 | 仍在登录态、token 还在 |

**两条断言都做了对照**，确认它们不是空过：

| 对照 | 结果 |
|---|---|
| 去掉 `store.fetchMe()` | 阶段二 FAIL（管理员入口仍在） |
| `catch` 恢复成旧的 `logout()` | 阶段三 FAIL（被强制登出，token 没了） |

第二组对照是必要的：在「去掉 `fetchMe()`」那一次里，阶段三是**空过**的 ——
没调用就无从失败。**一条在对眼里空过的断言，和一个不会失败的断言没有区别。**

### 19.4 C 节剩余

C1（viewer 作为被接受的依赖）、C3（工具结果没有信封）、C4（对话生命周期与取消）、
C5（用户显示 fallback ×20）、C6（懒加载兜底 ×3）。

---

## 20. C2：让缩略图的命名约定成为事实（2026-09-13）

### 20.1 一半情况下成立的约定

写方产出文件名，读方（`ArticleListItems.coverThumb`）**从文件名反推**缩略图：
把 `/uploads/X.ext` 换成 `/uploads/X-thumb.jpg`。这条约定此前只对一种上传类型成立：

| 上传类型 | 写出的文件 | 读方反推 |
|---|---|---|
| `cover` | `base.jpg` + `base-thumb.jpg` | ✅ 对得上 |
| `content`（正文图） | 只写 `base.<ext>` | ❌ 反推到一个从不存在的文件 |
| `avatar` | `base-avatar.jpg` | 读方不会去反推它 |

而**封面是一个自由文本 URL 字段**（`Write.vue`：「封面图片 URL，或上传后自动填充」）——
作者完全可以把正文图片的 URL 粘进封面。那时列表页请求一个从未写入的
`-thumb.jpg`，得到 404。

### 20.2 修法：让写方把约定变成事实

- `saveOriginal` 也写缩略图（能解码时）。**这不是给正文用的**，是为了让命名约定成立。
- 抽出 `writeThumb(base, image)`：解码不了返回 `null` —— 那时确实没有缩略图，
  上传响应里的 `thumbUrl` 因此缺席，而不是报一个不存在的路径。
- `type` 从 `default -> saveOriginal` 改成显式白名单 + 报错：它此前是个未校验的魔法
  字符串，拼错一个字母就静默按正文图片存下去，调用方还以为自己传的是封面。

### 20.3 证据

**自动化** 145 → 150。`UploadControllerTest` 的断言不是「有没有写文件」，而是
「**读方反推出来的那个路径是否存在**」—— 这正是此前缺失的那条跨 module 不变式，
也是唯一能抓住这个 bug 的断言形式。

**实测**（本地后端，用一张真实 PNG 走真实上传接口）：

| 探针 | 结果 |
|---|---|
| `type=content` 上传 | `url=/uploads/f728….png` |
| 请求 `url` | **HTTP 200**（3238 字节） |
| 请求读方反推的 `-thumb.jpg` | **HTTP 200**（4308 字节）—— 修前必然 404 |
| `type=covers`（拼错） | **HTTP 400** —— 修前静默成功 |

本次上传产生的两个文件已删除。

### 20.4 仍未覆盖的

- **解码不了的原图仍是那个洞。** 魔数对得上、`ImageIO` 却读不出的图（截断的 jpg 等）
  拿不到缩略图，而读方照样会反推 —— 那种情况下仍是 404。`.webp` 之所以没事，是因为
  读方对它有一条例外（返回原图），而那条例外其实是「ImageIO 读不了 webp」的补丁，
  只是恰好被写成了按扩展名判断。要真正关掉这个洞只有两条路：**把缩略图 URL 存进文章**
  （一次表结构变更，与「不得修改既有表结构」的开发原则冲突，需要显式决策），
  或者**在读取时确认文件是否存在**（每行一次 I/O，投影不该做这件事）。
- **上传资产的约定仍然散在两个 module 里**（写方在这里，读方在 `ArticleListItems`）。
  本轮让它们**一致**，但没有让它们**同源**。
- C 节剩余：C1、C3、C4、C5、C6。

---

## 21. C5：把「一个人怎么被显示」收成一个模块（2026-09-13）

### 21.1 同一条链的 20 份拷贝，与 7 份各自的答案

「昵称，没有就用用户名」这条规则在 9 个 module 里重复了约 20 次；而
`avatarSrc(url, nickname || username)` 这个组合本身也重复了 7 次 —— 于是
**「头像拿谁的名字做兜底」这件事有 7 份各自的答案**。`avatarSrc` 里还藏着一条
没人看得见的规则：dicebear 的占位地址一律丢弃。

新增 `utils/person.js`：`displayName(person)` / `displayAvatar(person)`。
两者必须同源 —— 名字为空时 `avatarSrc` 只能退到那个 `?` 占位图，而头像的
dicebear 规则也依赖同一个名字生成确定性颜色。放在一起，「显示一个人」才是一个
可以被问一次的问题。

### 21.2 两处刻意不替换

- **`Profile.vue` 的头像预览**用的是表单里的值（`profileForm.avatar`），不是 store 里的 ——
  换成 `displayAvatar` 会把「边输边预览」弄坏。（这一处是我先改错、又改回来的：
  构建不会报，只有读代码才会发现。）
- `Profile.vue` 的 `nickname || ''` 是**表单默认值**，不是显示兜底。

### 21.3 一条硬约束

能被 `node --test` 加载的模块**必须写文件扩展名**（`from './avatar.js'`）：
Node 的 ESM 解析器不做扩展名补全，而 Vite 会。仓库其余地方的省略写法属于打包器。
第一次跑测试就撞上了这个（`ERR_MODULE_NOT_FOUND`），已写进 `person.js` 的注释。

### 21.4 证据

**自动化** 45 → 52（`person.test.js` 7 条：昵称优先、退回用户名、两者都没有时返回空串
而**不编一个名字**、dicebear 被丢弃、头像的首字母取自与 `displayName` 同一个兜底）。

**真实浏览器核对**（系统 Chrome，含 console 错误捕获）：

| 探针 | 结果 |
|---|---|
| 首页 5 张卡片 | 都渲染出作者名与头像 |
| 文章详情与评论区 | 人名都在 |
| 后台文章列表 | 作者列有内容 |
| 全程 console 错误 | **0** |

最后那条是必要的：构建能抓语法，抓不到「模板引用了 `script setup` 里不存在的函数」——
那是运行时错误，而这次改动恰好只动了模板里的表达式。

### 21.5 仍未覆盖的

- **`displayName` 对缺失作者返回空串**（保持原行为，不编名字）。作者行确实可能缺失
  （§16.3 的 `of_shouldLeaveAuthorNull_whenTheAuthorIsGone` 钉住了这一点），
  那时界面留白。要不要给一个「已注销用户」之类的文案，是一个产品决定，不是缺陷修复。
- C 节剩余：C1、C3、C4、C6。

---

## 22. C6：给懒加载失败兜底一个归属（2026-09-13）

### 22.1 一套没有主人的机制

它此前散在三处：`router/index.js` 里一个**单槽注册表**加一句**裸启发式**，`App.vue` 里
三条重试路径，`main.js` 里从不设置 `app.config.errorHandler`。
`App.vue` 共 10 次提交，其中 4 次是这套机制，`docs/lessons.md` 记着两个真实故障。

新增 `router/loadError.js`（可进 `node --test`）：

| 导出 | 修掉什么 |
|---|---|
| `shouldReportRouteError(error)` | `error.type !== undefined` 碰巧对 —— `NavigationFailure` 恰好带 `type` 字段。但那只是巧合：**任何带 `type` 的错误都会被悄悄吞掉，任何不带 `type` 的导航失败都会被当成崩溃。** 改用 vue-router 自己导出的 `isNavigationFailure` |
| `createRouteErrorChannel()` | 单槽变量 → 订阅表。此前 `onModuleLoadError(handler)` 直接覆盖，第二个订阅者会**静默**顶掉第一个，而顶掉的后果正是「加载失败时没人弹兜底」。`subscribe` 返回退订函数，`App.vue` 在卸载时退订 |

`main.js` 设置 `app.config.errorHandler`：组件在渲染/生命周期里抛出的错误此前没有归属
（Vue 默认只打到 console，而日志里**认不出是哪个组件、哪个阶段**）。它**刻意不**去弹
「页面加载失败」—— 那是 chunk 拉不下来的问题，与组件内部抛错是两回事，混在一起只会让
两种故障长得一样。

### 22.2 证据

**自动化** 52 → 59。其中导航失败用的是**真的** `NavigationFailure`：用 memory history
推两次同一个地址，vue-router 就会给出一个。这条规则的价值全在「能不能认出真的导航失败」
上，手搓一个假货证明不了。另有一条专门钉住旧启发式的错：**一个带 `type` 的普通错误
照样要报**。

**真实浏览器核对**（`Network.setBlockedURLs` 掐断首页那个懒加载 chunk）：

| 探针 | 结果 |
|---|---|
| 掐断 chunk + 客户端导航 | 弹出「页面加载失败」+「重新加载页面」「刷新页面」两个入口 |
| 放开拦截后应用内重试 | **仍失败** —— 见 22.3 |
| 整页刷新 | 恢复 |

### 22.3 一次「预期错了、机制是对的」

第三条原本断言的是「放开拦截后那条 1200ms 自动重试自己恢复」。它红了，但**错的是预期**：
**ESM 的模块表会缓存失败的 `import`** —— 同一个 URL 再 import 仍然拒绝。

这恰好解释了为什么会有三条重试路径：它们覆盖**不同的故障**（开发服务器重启 vs 模块表里
已经记下的失败），而**只有整页刷新能换掉模块表**。这条结论比原来那句断言有价值得多，
所以写进了这里而不是改掉断言了事。

顺带一个意外确认：这次核对只起了前端、没起后端，于是恢复后那张页面正好显示了候选 04 的
失败态（「网络错误 / 重试」），而「热门文章」与「标签云」仍显示「暂无」—— 与 §17.5 记的
缺口一致（那两个不是 `useList` 管的）。

### 22.4 仍未覆盖的

- **三条重试路径仍在 `App.vue` 里，我没有合并它们。** 22.3 说明它们覆盖不同的故障，
  合并需要先决定「哪种故障该给用户看哪条路」——那是产品决定，不是缺陷修复。
- **`retryNavigation()` 里那两次未 `await` 的 `router.replace` 我没动。** 它看起来可疑
  （先跳到 `/?t=…` 再跳回 `fullPath`），但那是为两个真实故障加的，动机我没有完整把握 ——
  在没有测试面的情况下改它，风险大于收益。
- C 节剩余：C1、C3、C4。

---

## 23. C1：viewer 作为被接受的依赖（2026-09-13）

### 23.1 同一个问题，两种写法

评审报告 C 节说这一项时引的行号已经漂移（`CommentService.java:72-79` 现在是投影代码，
不是 `catch → false`）。实际的读侧身份读取只剩 **两处**，而它们回答的是同一个问题、
却各写各的：

| 位置 | 写法 |
|---|---|
| `ArticleService.isFavoritedByCurrentUser` | 读 `SecurityUtil.currentUserId()`，它在匿名时**抛 401** ⇒ 必须 `catch (BizException e) { return false; }` 把一个表示「未登录」的异常当成控制流 |
| `ArticleService.isLikedByCurrentUser` | 隔一百多行，直接判 `SecurityContextHolder` 里的 auth 是不是 `null` |

两处现在都问 `ViewerSource` —— 模块读环境的**唯一**入口。「未登录」是它的一个
**正常取值**（`Viewer.anonymous()`），不是异常，所以匿名分支成了一条普通路径。

### 23.2 真正的收益在测试里

此前想造一个「已收藏」的 viewer，只能去**改线程局部变量**
（`docs/lessons.md` 第 26 条记着这类泄漏：同一个用例单跑通过、全量跑失败）。

现在 `ViewerSource.fixed(...)` 就够了，而且测试把**同一个** `ViewerSource` 同时喂给
可见性模块与 `ArticleService` —— 于是「这篇文章可见吗」与「我收藏过吗」不可能来自
两个不同的人。

**写侧刻意不动**：`requireOwnerOrAdmin` / `currentUserId` 仍在 `SecurityUtil`。
那里「没有登录」确实是个错误（你不可能以匿名身份发表文章），不是正常取值 ——
两种情形不该被统一。

### 23.3 证据

**自动化** 150 → 152：

- 「收藏与点赞来自被接受的 viewer」—— 断言查询确实绑定了 viewer 的 id（文章 id 是 3、
  viewer 是 7，绑定的参数里出现 7 就证明了问的是这个人）；
- 「匿名 viewer 不查收藏与点赞」—— `never()`。**这条以前根本写不出来。**

**实测**（真实接口）：

| viewer | liked | `like_count` |
|---|---|---|
| 匿名 | false | 1 |
| 管理员（本来就点赞过文章 3） | **true** | 1 |
| toggle 关闭 | false | 0 |
| toggle 打开 | true | 1 |

数据还原：`likes=1 favorites=0 users=4 article_tags=4`。

核对脚本第一次跑时，我按「基线未点赞」写断言，于是把一次**取消**点赞当成了点赞 ——
真实行为一直是对的。这是本项目里第六次「断言错、行为对」；`docs/lessons.md` 第 11 条
的反面说的就是这个。

### 23.4 仍未覆盖的

- **`AdminService` 的两处 `SecurityUtil.currentUserId()` 守卫没有改。** 它们问的是
  「我是不是在改自己」，只出现在 ADMIN-only 的写路径上，`SecurityUtil` 抛 401 在那里
  不可达 —— 与读侧的 viewer 是两件事。
- **`AdminService` 的守卫仍然没有测试**（报告把它与那三处 `catch` 并列，但那三处现在
  只剩两处，且都在 `ArticleService`）。
- C 节剩余：C3、C4。

---

## 24. C3：工具的契约与结果信封（2026-09-13）

评审报告 C 节把这一项列成八条。本轮收掉其中**可判定的四项**，其余四条例在 24.4。

### 24.1 收掉的四项

| # | 此前 | 现在 |
|---|---|---|
| 1 | 参数强转被复制**三次**（两个工具各一份私有 `idOf`，一个内联同一逻辑） | `AbstractTool.requiredLong` 一处 |
| 2 | schema 里**没有 `required`** | `AgentTool.requiredParameters()`（默认空）+ `ToolRegistry` 输出 `required`；三个需要 id 的工具声明它 |
| 3 | 错误信封靠**字符串拼接** | `ObjectMapper` 构造 |
| 4 | `ToolRegistry` 用 `Collectors.toMap`，重名抛一句 `Duplicate key` | 说清是谁重了；并保留注入顺序（发给模型的清单因此是确定的） |

第 1 项的要点不是去掉重复，而是**失败时说的话**：模型漏传 id 时以前抛的是
`Long.parseLong("null")` —— `For input string: "null"` 会原样进错误信封交给模型。
模型看不懂它，也没办法据此改正。现在给「id 是必填参数」/「需要是一个整数，收到：…」。

第 2 项的要点是**把失败从运行时挪到 schema**：没有 `required` 时，一次本该被 schema
挡住的调用只能等到 Java 抛异常。

第 3 项的要点最具体：驱动消息里**一个引号**就能造出坏 JSON，而那段 JSON 会作为 tool
结果原样回到模型那里 —— **模型读到的是「未知工具」还是「语法错误」，取决于它自己
刚才说了什么**。

另收一条小的：`ChatMessage.toolResult(id, name, content)` 收下 `name` 就丢掉。
OpenAI 的 tool 消息本来只有 `role`/`content`/`tool_call_id`（工具名靠 id 关联），
所以那个参数是**累赘而不是遗漏** —— 删掉它，免得读代码的人以为它去了哪里。

### 24.2 证据

**自动化** 152 → 161（`ToolContractTest` 8 条：漏传/非整数两种说清楚的话、数字与数字
字符串都收、三个工具声明 `required`、零参数工具不声明、`required` 真的出现在发出去的
schema 里、没有必填就不带这个键、重名说清是谁；`ChatServiceTest` 加 1 条引号测试）。

**对照**：把 `errorEnvelope` 恢复成字符串拼接，那条引号测试立刻红：

```
JsonParseException: Unexpected character ('这'): was expecting comma to separate Object entries
```

这正是报告描述的缺陷，而它此前**没有任何测试**。

### 24.3 一处刻意的判断

`requiredParameters()` 的默认值是**空列表**，而不是「把所有参数都当必填」。
`SearchArticlesTool` 的 `keyword` 因此不算必填 —— 它现在缺省成空串并返回全量前几条，
那是既有行为。把 `keyword` 改成必填是一次**行为变更**（模型不带关键词的搜索会开始报错），
不是契约修复，所以没有顺手做。

### 24.4 这一项还没收的四条

> 四条**都收完了**（2026-09-13）：前两条见 §28，第三条是「已决定不做」，
> 第四条是 C4（§25、§26、§29、§30）。本节保留原文，是为了让「当时为什么停在这里」可查。

- **截断出现两次，且都可能切在 JSON 字符串中间**（`ChatService` 截 4000、
  `ArticleDetailTool` 截 2000）。切断一个 JSON 字符串会让那段「JSON」不再合法 ——
  与 24.1 第 3 项是同一类问题，但改它要决定「截断后怎么才是合法 JSON」（是丢弃、
  还是把尾巴补成合法结构），那是个新决定。**→ §28.1：砍结构，不砍文本。**
- **`AbstractTool` 现在有了一个真正的成员**（`requiredLong`），但它依然不是抽象类意义上的
  「模板」；`json()` 把序列化失败吞成假成功（`{"error":"结果序列化失败"}`）——
  调用者无法察觉。**日志那一半已在 §27 收掉**；「抛还是返回信封」仍是待决定项。
  **→ §28.2：抛。**
- **`ChatStreamListener` 仍是假 seam**（§5.3，候选 07 记录）。**这一条已决定不做**：
  候选 07 判定它「已经在正确的层（行为），没有必要为了对称再加一个 adapter」——
  它是「只有一个实现」而不是「画错了层」。记在这里是为了不再挂在待办上。
- C 节剩余：C4（对话生命周期与取消）。**→ §25、§26、§29、§30。**

---

## 27. C3 之三：序列化失败不再无声无息（2026-09-13）

### 27.1 一个黑洞

`AbstractTool.json()` 的 catch 返回 `{"error":"结果序列化失败"}` —— 而这段 JSON 与一个
**正常的**工具结果长得一模一样：调用者看不出来，读日志的人也看不出来。
失败被降级成一个正常的返回值，且不留任何记录。

### 27.2 为什么这一半不需要决定

评审报告把「`json()` 把序列化失败吞成假成功」列为一条。改它确实要决定**抛还是继续返回
信封**（§24.4）—— 但不管选哪一条，**现在这种「不留痕迹」都是错的**。所以本轮只补上
可见性：记一条 WARN，带上出错对象的类型。

### 27.3 证据

**自动化** 164 → 165。用 logback 的 `ListAppender` 挂在 `AbstractTool` 的 logger 上，
断言确实收到了一条 WARN；序列化不了的对象用**自引用**（无限递归）构造。

这条断言不需要额外的对照：它断言的就是「收到了一条 WARN」，而它通过了 ——
说明 appender 有效、那行日志确实存在。

### 27.4 C3 的现状

| 条 | 状态 |
|---|---|
| 参数强转 ×3 收成一处 + 说清失败原因 | 完成（§24.1） |
| schema 加 `required` | 完成（§24.1） |
| 错误信封改由 `ObjectMapper` 构造 | 完成（§24.1） |
| 工具重名说清是谁 | 完成（§24.1） |
| `toolResult` 收下 name 又丢掉 | 完成（§24.1，删掉那个参数） |
| `json()` 把失败吞成假成功 | 完成（§27 补可见性 + §28.2 改成抛出） |
| 双重截断且可能切在 JSON 中间 | 完成（§28.1，砍结构不砍文本） |
| `ChatStreamListener` 是假 seam | 已决定不做（见 §24.4 的说明） |

C3 因此是 **8/8**。

---

## 25. C4：一条没人收尾的路径（2026-09-13）

评审报告 C 节这一项列了五条。本轮收掉其中**最清楚、也最可判定的一条**，其余四条列在 25.3。

### 25.1 唯一一条必须由提交方自己收尾的路径

`ChatController` 把一场对话交给 `chatExecutor` 之后就撒手了。队列满时 `execute` 抛
`RejectedExecutionException`，于是：

```
lambda 从不执行  ⇒  asyncContext.complete() 从不执行
而 setTimeout(0L) 让请求本身没有期限
⇒ 客户端什么也收不到,只会一直等
```

**一次「服务器忙」在客户端上表现得和「AI 卡住了」一模一样。**

正常路径的收尾在 lambda 的 `finally` 里 —— 而这条路径上 lambda 根本没跑起来，所以它是
唯一一处必须由**提交方**收尾的地方。现在捕获拒绝，发一个 `error` 帧再 `complete`。

`error` 与 `done` 互斥：后者会让客户端以为回答完整（候选 07 之后 `done` 是唯一的正常
收尾凭证），所以拒绝时**不能**发 `done`。

### 25.2 证据

**自动化** 161 → 163（`ChatControllerTest` 2 条：拒绝时发 `error` 帧且说人话、
拒绝时不发 `done`）。

**对照**：把 `catch` 改成重新抛出（等价于原来没有 `try/catch`），两条测试都 error：

```
java.util.concurrent.RejectedExecutionException: 队列已满
```

即异常直接冒出 `chat(...)`，客户端确实什么也收不到。

### 25.3 这一项还没收的四条

> 四条**都收完了**（2026-09-13）：池尺寸见 §29，存活检查与心跳见 §30，
> 最后一条已在 §26 收掉。本节保留原文，是为了让「当时为什么停在这里」可查。

- **线程池的尺寸与意图不符。** `corePoolSize=2 / maxPoolSize=4 / queueCapacity=20` ——
  `ThreadPoolExecutor` 只在**队列满之后**才扩容，所以稳态并发是 2，另外 20 个静默排队，
  而 `maxPoolSize=4` 几乎永远用不到。改成 4 还是缩队列，是**容量决策**（取决于这台机器
  愿意为 AI 对话付多少并发），不是缺陷修复。**→ §29：4 就是 4。**
- **轮与轮之间没有存活检查**，因此浏览器「停止」传不到服务端：用户点了停止，服务端仍会
  跑完剩余轮次（每轮最长 120 秒）。要修得先决定用什么信号传播取消（`AsyncContext`
  的 error/complete 回调？还是请求断开检测），并且它与「心跳帧」是同一件事的两面。
  **→ §30：信号是写失败，心跳让它按期发生。**
- **没有心跳帧**，响应头要到第一个 token 才 flush —— 所以「AI 正在想」与「连接已经死了」
  在客户端上不可区分。这与上一条应当一起做。**→ §30.2、§30.5。**
- **写 tool 事件失败会走成一条完整的 `log.error("AI chat failed")` 堆栈** ——
  一次正常用户操作被记成服务故障。**已在 §26 收掉。**

### 25.4 C 节的现状

| 项 | 状态 |
|---|---|
| C7 登录后刷新用户 | 完成（§19） |
| C2 上传资产命名约定 | 完成（§20） |
| C5 用户显示 fallback | 完成（§21） |
| C6 懒加载兜底 | 完成（§22） |
| C1 viewer 作为被接受的依赖 | 完成（§23） |
| C3 工具结果没有信封 | **8/8**（§24、§27、§28） |
| C4 对话生命周期与取消 | **5/5**（§25、§26、§29、§30） |

C3 与 C4 剩下的九条**都不是缺陷修复**：每一条都要先决定「截断后怎么才算合法 JSON」
「序列化失败时抛还是返回信封」「线程池该多大」「取消用什么信号」。这些决定属于用户，
不属于实现者 —— 所以当时它们被列在这里，而不是被悄悄做掉。**§28–§30 记的就是
「你定」之后每一个选择的理由与证据。**

---

## 26. C4 之二：客户端断开不是服务故障（2026-09-13）

### 26.1 两类失败此前不可区分

`ChatService` 的兜底分支把**所有** `Exception` 都记成 `log.error("AI chat failed")` 加
完整堆栈，并朝客户端发一句「AI 服务异常，请稍后重试」。于是：

```
用户关掉标签页 / 点了停止  ⇒  日志里一条 ERROR 加堆栈,而客户端那边根本没人收
```

**一次正常用户操作在日志里长得和「AI 服务挂了」一模一样。**

根因是两类失败不可区分：**写不出去**（客户端没了）与 **AI 服务出错**，都是 `Exception`。

### 26.2 改动

- 新增内部信号 `ClientDisconnectedException`（取代原来的 `StreamAbortedException`）；
  所有往客户端的写都经 `writeToClient(...)` 包一层 —— 写不出去只有一个合理解释。
- `chat()` 单独 catch 它：记 `log.debug`（**不带堆栈**）、**不**朝客户端发 `failed`
  （没有收件人），也不再走那条 ERROR。

顺带一处统一：内容事件的写失败与工具事件的写失败此前走的是**两条**不同的路径
（前者被包成 `StreamAbortedException`，后者以裸 `IOException` 冒出），现在同一条。

### 26.3 证据

**自动化** 163 → 164。

**对照**：去掉那个专门的 `catch`（让它落回兜底分支），新测试立刻红：

```
expected: <null> but was: <AI 服务异常,请稍后重试>
```

即确实又朝一个已经不在的客户端发了服务故障。

### 26.4 C4 的现状

| 条 | 状态 |
|---|---|
| 拒绝时客户端收不到任何错误 | 完成（§25） |
| 断连被记成服务故障 | 完成（本节） |
| 线程池尺寸与意图不符（稳态并发 2，`maxPoolSize=4` 几乎用不到） | 完成（§29，4 就是 4） |
| 轮间无存活检查（浏览器「停止」传不到服务端） | 完成（§30） |
| 无心跳帧（响应头要等第一个 token 才 flush） | 完成（§30，与上一条一起做的） |

---

## 28. C3 之二：截断与假成功（2026-09-13）

§24.4 剩下的两条各卡在一个决定上。**决定已由用户授权（「你定」），本节记录选择与理由。**

### 28.1 决定一：砍结构，不砍文本

此前那条 4000 字上限长在 `ChatService` 里，而且是用 `substring` 执行的 —— 在**序列化
之后**的文本上切。切断一个 JSON 字符串，那段「JSON」就不再是 JSON，而它会作为工具结果
原样回到模型那里：**模型读到的不是数据，是语法错误**，而它会拿这段语法错误继续往下推理。
与 §24.1 第 3 项（错误信封靠拼接）是同一类问题，只是这一处更隐蔽 —— 它只在结果真的
很大时才发作。

报告给的选项是「丢弃」还是「把尾巴补成合法结构」。选的是**第三种**：

> 别让坏结构产生。序列化是最后一步，砍要砍在它前面。

- 上限搬到工具层唯一的出口：`AbstractTool.json()` → 新增 `ResultBudget`。
- 超预算时**整条丢弃**数组尾部的一个条目、重新序列化、再看还超不超 ——
  **从来没有哪一步切开过一个 token**。
- 丢掉条目时 `count` 跟着改成实际条数：否则模型会照着 `count: 5` 说出一个它手上只有
  3 条的数据。根上再加一个 `truncated: true` 如实标注。
- 没有数组可丢时（单个超长字符串字段），砍在**值**上再序列化 —— 同样是先砍后序列化。
- `ChatService` 不再碰结果长度，只负责原样转发。

`ArticleDetailTool` 的 2000 字正文限制保留：它本来就在**值**上截断，序列化在其后，
所以从来不是这一类问题。

### 28.2 决定二：序列化失败要抛

`json()` 此前返回 `{"error":"结果序列化失败"}` —— 而它与一个**正常的**工具结果长得
一模一样：调用者分不出「工具跑完了但是空的」和「工具坏了」。工具契约本来就是「返回一段
给模型看的 JSON 文本」，给不出就是给不出。

现在抛 `BizException`，走 `ChatService.executeTool` 那条**统一失败路径**：记一条 WARN
（§27 补上的可见性原样保留），并给模型一个错误信封 —— 模型仍然知道发生了什么，
而日志里不再是一片安静。

### 28.3 证据

**自动化** 165 → 185。新增 `ResultBudgetTest` 7 条、`SseWriterHttpTest` 5 条，
`ToolContractTest` / `ChatServiceTest` / `ChatControllerTest` / `SseProtocolTest` /
`AiExecutorConfigTest` 各加若干。

`ResultBudgetTest` 的**第一条就是对照**，而且是自足的对照：同一份数据、同一个上限，
老办法切出来的东西解析不了 ——

```
assertThrows(Exception.class, () -> mapper.readTree(whole.substring(0, LIMIT) + "…(已截断)"))
```

如果这句话能解析，说明这条对照失去了分辨力，下面所有「合法 JSON」的断言也就没有意义了。

---

## 29. C4 之三：说 4 就是 4（2026-09-13）

### 29.1 一条读得出意图、跑不出意图的配置

```
corePoolSize=2 / maxPoolSize=4 / queueCapacity=20
```

`ThreadPoolExecutor` **只在队列满之后**才扩容，所以这条配置的实际含义是**稳态并发 2**：
另外 20 个静静地排队，而 `maxPoolSize=4` 几乎永远用不到。一个写着 4、跑着 2 的配置，
读的人会照它做容量判断。

现在 4 就是 4：`corePoolSize = maxPoolSize = 4`、`queueCapacity = 16` ——
**同时在飞 20 场对话，与这个池此前的总容量一致**。一次对话几乎全程在等模型（IO），
4 个线程很便宜；超出的部分排队，排满就明确拒绝（收尾路径见 §25）。

### 29.2 证据

**自动化** 加 1 条 `chatPool_shouldRunFourConversationsAtOnce`：四条任务各占住一个线程，
断言它们**同时**跑起来；第五个提交后 300ms 仍在排队，放行后才跑。

**对照**：把 `CONCURRENT_CHATS` 改回 2 再跑，它立刻红，而且红在正确的地方 ——

```
只有不到 4 场对话同时跑起来 —— 旧配置(core 2 / queue 20)正是这样:配置写着 4,实际稳态并发是 2
```

顺带修好了一条**靠概率通过**的测试：`poolThread_shouldNotKeepTheIdentity_afterTheTask`
原来反复提交任务去「撞」同一个池线程（20 次，池里 2 个线程），改池大小会让它变得不确定。
现在先用 4 条带身份的任务占满 4 个 worker，再提交 4 条不带身份的探针 ——
池已满，队列里的任务只能由那 4 个线程接手，**每一条断言都确定地落在复用过的线程上**。

---

## 30. C4 之四：心跳与取消（2026-09-13）

### 30.1 取消的信号是什么

报告问的是「用什么信号传播取消：`AsyncContext` 的 error/complete 回调？还是请求断开检测？」
本机实验（30.4）给出的答案是**后者，而且只有一个**：

> 一个已经断开的连接不会以别的方式通知服务端。servlet 的 error/complete 回调不会因为
> 对端关掉而触发，**只有真的写一次才知道**。

所以信号是**写失败**，而心跳是让这个信号**按期发生**的装置 —— 这两件事是同一件事的两面，
报告说「应当一起做」是对的。

### 30.2 心跳

- `SseProtocol.comment(text)` → `": ping\n\n"`：SSE 注释帧，浏览器端按规范忽略它。
  它不是事件，所以**不产生任何数据事件，也不能被当成收尾凭证**。
- `ChatEgress` 加两个行为层成员：`keepAlive()`（尽力而为）与 `stillConnected()`。
  「客户端还在不在」是**通道**的属性，不是服务层的猜测。
- `SseWriterHttp` 持有那个事实：**任何一次写失败**（含 `getOutputStream()` 自己抛的
  `IllegalStateException`）都把 `connected` 置为 false，且只保留第一次的 DEBUG 痕迹。
- `ChatController` 起一个定时任务（`chatHeartbeatScheduler`，守护单线程），
  **首次延迟 0**、每 10 秒一个 —— 首次延迟为 0 是有行为后果的：响应头在第一次写就
  flush，而不是等模型吐出第一个 token（那可能是一分钟后）。
- 对话收尾时 `heartbeat.cancel(false)`，再 `asyncContext.complete()`。

### 30.3 取消

`ChatService.runToolLoop` 在**每一轮开始前**问一次 `egress.stillConnected()`，
不在就直接抛 `ClientDisconnectedException`：

```
用户点停止 ⇒ 连接断 ⇒ 心跳写失败 ⇒ stillConnected=false ⇒ 下一轮不再开始
```

为什么要专门问：一次安静的模型轮次最长 120 秒，而「下一次写」要等到那一轮的第一个
token —— 中间这段时间，池线程正卡在 `chatStream` 里，什么都不知道。加这一问之后，
停止的生效上界从「剩下的所有轮次」缩到「当前这一轮」。

### 30.4 证据

**自动化** 后端 185、前端 68（`npm test`，零依赖 `node --test`）。

- `SseWriterHttpTest` 5 条：心跳帧的字节、写失败翻标志（IOException 与 RuntimeException
  两种）、增量写失败仍当场抛出、没断时事件照常。
- `SseProtocolTest` +1：心跳是注释帧（跨语言契约的那一行）。
- `ChatServiceTest` +2：轮间检查（下面有对照）、工具结果原样转发。
- `ChatControllerTest` +2：心跳被调度、被取消、首次延迟 0；被拒绝的路径**不留**心跳。
- 前端 +9：`aiEvents.test.js` 认注释帧是心跳而不是数据（且不能让半截回答伪装成完整
  回答）；`liveness.test.js` 7 条用**注入的假计时器**验证 `createAliveWatch` ——
  沉默 30 秒（= 连丢三次心跳）才判定断连，`stop()` 之后迟到的帧救不活已结束的对话。

**对照**（取消这一条）：把轮间那一问去掉，`chat_shouldStopBetweenRounds_whenTheClientIsGone`
立刻红，红在它该红的地方 ——

```
openAiClient.chatStream(...)  Wanted 1 time: But was 2 times:
```

也就是：用户已经走了，服务端照样开始烧下一轮。

**真机**（本机 8080 + 真实模型，用裸 TCP 直接读原始字节）：

1. 正常一场对话，原始流的第一帧就是 `8\r\n: ping\r\n\r\n`，之后每 10 秒一个 ——
   心跳确实在线上，且响应头立刻 flush。工具事件与回答增量与改动前无差别。
2. 收到 1650 字节后**关掉客户端**（普通 FIN，不设 linger，即浏览器 `abort()` 关的那种），
   日志随即出现（DEBUG，各一行，**没有堆栈**）：

```
[ai-chat-1] SseWriterHttp : 往客户端写失败,判定为已断开: ServletOutputStream failed to flush: ...
[ai-chat-1] ChatService   : 客户端已断开,对话中止: ServletOutputStream failed to flush: ...
```

   即「谁走了」这件事第一次在日志里说得清，且不再长得像服务故障。

**一句诚实的话**：本机上这个模型会**持续吐空增量**，所以安静窗口很短，
断连信号是由增量写失败（而不是心跳写失败）先报出来的；心跳写失败那条路
由 `SseWriterHttpTest` 覆盖，真机上没有被单独触发过。两半都验了，但不是同一半。

### 30.5 前端那半边

心跳在浏览器这边换来一件原本做不到的事：**「AI 正在想」与「连接已经死了」不再长得一样**。

- `aiEvents.js`：注释行 → `handlers.onAlive?.()`，仅此而已（不产生事件、不影响收尾判定）。
- `liveness.js`：`createAliveWatch({ onStall, timeoutMs = 30000, ... })` —— 每收到一次
  心跳就重新起表，表真的响了说明服务器已经连丢三次心跳。计时器从外面注入，
  所以测试不需要真实时间。
- `AiChat.vue`：表响 ⇒ 把这条回答标成「连接已中断，请重试」、停表、**并 `abort()` 那条
  fetch**（表响就是连接已死的结论，那条流还挂着只是在读一个不会来的字节；顺手也保证
  它的迟到回调不会再动界面）。`onDone` / `onError` / 用户点「停止」都停表。

### 30.6 一处顺带修掉的真缺陷

`SseWriterHttp.write()` 里 `response.getOutputStream()` 原本写在 `try` **外面** ——
那么当响应已经提交或关闭、异常正是由它自己抛出时，探测恰好漏掉那一种失败。
单测（`keepAlive_shouldMarkDisconnected_whenTheWriteFailed`）把它抓出来了：
「写都写不出去，客户端已经不在了」这条断言第一次跑就是红的。

**教训**：**探测本身也要在 try 里**。写这份探测的初衷就是「凡是写不出去都算断开」，
而最容易漏掉的恰恰是「还没开始写就失败了」。

---

## 31. C4 附带发现：取消路径上还有两条 ERROR（2026-09-13）

真机实验 2 里，客户端断开之后除了上面那两行 DEBUG，还多了**两条 ERROR 加完整堆栈**：

```
ERROR [dispatcherServlet] : Servlet.service() for servlet [dispatcherServlet] threw exception
org.springframework.security.authorization.AuthorizationDeniedException: Access Denied
    ... at org.apache.catalina.core.AsyncContextImpl.setErrorState(AsyncContextImpl.java:442)
    at org.apache.catalina.connector.CoyoteAdapter.asyncDispatch(CoyoteAdapter.java:155)

ERROR [Tomcat].[localhost] : Exception Processing [ErrorPage[errorCode=0, location=/error]]
jakarta.servlet.ServletException: Unable to handle the Spring Security Exception
because the response is already committed.
```

机制（从堆栈读出来的，不是猜的）：客户端断开 ⇒ Tomcat 在**连接层**收到 socket 错误 ⇒
异步请求进入 ERROR 状态 ⇒ 容器做一次 `/error` 的**错误派发** ⇒ 而 `JwtAuthFilter` 是
`OncePerRequestFilter`，默认**跳过 ERROR 派发** ⇒ 那次派发里没有身份 ⇒
`.anyRequest().authenticated()` 拒绝 `/error` ⇒ 异常 ⇒ 已经提交的响应渲染不了错误页，
于是再记一条 ERROR。

也就是说：**§26 在应用层把「用户关页面」和「服务故障」分开了，容器层这一半还在。**
一次正常用户操作仍然会在日志里留下两条 ERROR 加堆栈。

**本轮没有改它**，两个原因，都属于不该由实现者代拍的决定：

1. 修法是改**授权面**（`/error` 加进 `permitAll`，或让 `JwtAuthFilter` 也跑 ERROR 派发）。
   后者对匿名请求无效（匿名请求的错误派发同样会被拒），所以真正的选项只有前者 ——
   而「`/error` 对所有人开放」是一次安全面的变更。
2. 还有一个我没验证的后果：放行之后容器会真的去渲染 `/error`，而此时响应**已经提交**
   （SSE 流已经开始写字节），错误页的内容有可能被塞进那个已经开始的 SSE 流里。
   这一条需要它自己的对照实验，不能顺手改。

记在这里，是因为它是**同一条取消路径**上的另一半，而这一轮第一次把它看见了。

---

## 32. AI 对话的留档：刷新与切页之后记录还在（2026-09-14）

### 32.1 先分清这是不是回归

报告的现象是「刷新之后对话记录直接消失，切走再切回来也消失」。**先查它是不是这几轮改出来的**：

```
git show 0c0a940:frontend/src/views/AiChat.vue | Select-String 'const messages'
→ const messages = ref([])
```

从 AI 助手这个功能落地那天起，消息就活在一个组件内的 `ref([])` 里；全库没有 `<keep-alive>`，
也没有任何持久化。所以「切页就没了」是必然会发生的，刷新只是同一件事更彻底的一次 ——
**这不是回归，是这个功能从来没做过这件事**。顺序很重要：先确认这一点，再决定改哪里，
否则很容易去「修」一段本来就是这样的代码。

### 32.2 存哪儿

`sessionStorage`，一个键 `ai-chat`。理由是它的形状正好合适：

- **每个标签页各自一份** —— 两个标签页同时开着 `/ai` 不会互相覆盖对方那一场对话
  （`localStorage` 是全域共享的，两个标签页会一直抢同一份记录）；
- F5 与客户端路由切换都在（正是报告的两件事）；
- 关掉标签页就消失，不在磁盘上久留。

想让它跨浏览器重启也留着，只需把 `views/AiChat.vue` 里那一个值换成 `window.localStorage` ——
`chatHistory.js` 不认识任何一种 storage，它只认识注入进来的那个对象。

### 32.3 三件容易做错的事

**归属。** 载荷里写着 `userId`，`load` 只认相等的那一份 —— 同一个浏览器里换个人登录，
不该看到上一个人的整场对话；宁可什么都不显示，也不显示错的。
用户 id 在**视图 setup 时定下来**，而不是每次落盘现问一次：用户在回答中途点退出登录时，
`store.user` 已经是 null，那时若现问，这一整份记录会被写成「无主」，本人下次登录就读不回来了 ——
**一次登出等于丢了一整场对话**。

**诚实。** 刷新或离开页面时，正在流式回答的那条会被原样存下来。半截回答如果看起来和完整
回答一模一样，就是在骗用户（这个仓库对这件事有明确立场，见 `api/aiEvents.js` 里
「`truncated` 绝不能触发 `onDone`」）。所以：落盘时那条带着 `pending: true`，载入时由 `revive`
换成 `interrupted: true`，界面上多一行灰色的「回答未完成」（不是红色 —— 它不是错误）。
「用户点停止」与「离开页面」走同一个 `markInterrupted()`：两处说的是同一件事，拆成两份迟早漂移。

**存不下也不能崩。** 配额满、隐私模式下 storage 会抛异常，而聊天不该因为留档失败就跟着坏掉：
读写全是尽力而为，绝不把异常抛给调用方；连 `window.sessionStorage` 这个 getter 本身抛
（某些隐私设置下会）也被兜成 `null`，而 `null` 在 `chatHistory` 眼里的含义就是「当没存过」。

### 32.4 顺带修掉的一个既有 bug

`send()` 此前把**整份** `messages` 发给服务端，而后端 `ChatRequest.messages` 上有
`@Size(max = 50)`，聊长了必然 400「历史消息过多」；而后端喂给模型的本来也只有最近 20 条。
现在 `outgoing()` 只取最近 `OUTGOING_LIMIT = 20` 条，且只带 `role`/`content` —— 气泡长什么样
（`tools`/`error`/`pending`/`interrupted`）不该上线。

留档本身也把这个 bug 变得更可能发生：以前一场对话活不过一次刷新，现在能一直攒下去。

### 32.5 证据

**自动化** 前端 68 → 97（新增 `chatHistory.test.js` 29 条：归属对不上、坏 JSON、版本对不上、
非数组、上限取尾部、写入抛异常、`clear`、`revive`、`outgoing` 各一条；`tail()` 用
`slice(max(0, len - limit))` 而不是 `slice(-limit)` —— `limit = 0` 时 `-0` 会返回**全部**，
一个「不许存」的配置反而存下最多）。`npm run build` 通过。

**真机**（Chrome + CDP，本机 8080/5173，14 项全过）：

| # | 核对 | 结果 |
|---|---|---|
| 1 | 登录、进入 `/ai`、发一句、收到回答 | 通过 |
| 2 | 对话已经落进 `sessionStorage` | `{"keys":["ai-chat"],"count":2}` |
| 3 | **对照**：刷新确实重新加载了页面 | 探针 `window.__probeMark` 消失 |
| 4 | **F5 之后记录还在** | 消息行数与正文长度与刷新前一致 |
| 5 | **对照**：切页走的是客户端路由 | 探针 `window.__probeMark2` 仍在（没有整页刷新） |
| 6 | **切到首页再切回来记录还在** | 同上 |
| 7 | **对照**：把存储删掉再刷新，记录确实消失 | 消息行 0 —— 证明上面那份是**从存储里读回来的**，不是某处缓存 |
| 8 | 回答还在写时那条带着 `pending: true` | `{"pending":true,"error":null}` |
| 9 | 写到一半切走再切回：记录在，且「回答未完成」 | `{"rows":2,"hint":"回答未完成"}` |
| 10 | 页面被销毁后载入：`revive` 把那一条标成未完成 | `{"hint":"回答未完成"}` |
| 11-12 | 「清空对话」清掉界面与存储，刷新后仍是空的 | 通过 |
| 13-14 | 登录会话与「刚进页面是空的」 | 通过 |

第 3、5、7 条是**对照**：没有它们，「刷新后还在」可能只是页面没真刷新、或者内容来自别处
（见 `docs/lessons.md` 第 11 条）。

第 10 条为什么用「手写一份 `pending: true` 的载荷」而不是「发出去立刻刷新」：后者谁先跑
（cancel 回调还是页面卸载）是竞态，两种结局都诚实，断言会随机红 —— 而随机红的断言比没有
断言更坏。竞态的另一半（cancel 回调先跑，于是记下一条错误）在这一轮里被真实撞见过一次，
它的表现是气泡里一条英文的 `network error` —— 那暴露了既有 `api/ai.js` 把
`err.message`（浏览器给的英文）直接端给用户，现在换成「网络连接失败，请重试」，
原始错误留给 `console.warn`。

### 32.6 三处刻意的判断

- **没有引入 Pinia store**：留档这件事只需要「进页面读一次、变更时写一次」，视图自己持有
  消息就够了。抽成 store 是为了跨组件共享，而这里没有第二个消费者。
- **落盘没有节流**：深度 watcher 每个增量都会「序列化整份历史 + 写 storage」，一次回答
  几十上百次。上限 60 条下这点开销可以接受（实测无感）；真要节流，节流点在
  `AiChat.vue` 那个回调里，而 `onBeforeUnmount` 里那次显式 save 必须仍然立即执行。
- **真机核对脚本没有进仓库**（仍在 `%TEMP%`，与之前几次一致）。它是**一次性**核对：
  要跑它得同时起着后端、前端与一个自己 user-data-dir 的 Chrome。所以「SFC 层没有自动化
  测试」这条**没有被解决**，只是这一次被真机看过一遍 —— 要真正覆盖它需要 vitest +
  `@vue/test-utils`，那是一个依赖决定。

---

## 33. 文档漂移与社区种子数据（2026-09-14）

### 33.1 先修文档，因为下一轮要拿它当地图

代码在前几轮里前进了，文档有一部分停在三周前。逐条核对代码之后修掉六处：

| 漂移 | 事实 |
|---|---|
| `docs/api.md` 缺四类**已经实现**的接口 | 收藏两个端点、`GET /api/articles/hot`、`GET /rss`、全部 11 个 `/api/admin/**` —— 在 api.md 里都是零命中 |
| `api.md` 对上传的描述过时 | `type=content` 现在也写缩略图并返回 `thumbUrl`；`cover` 是「裁 16:9 → 1280x720」而不是「保留原图」（`UploadController.saveCover/saveOriginal`） |
| `backlog.md` 两条「待做」其实做完了 | RSS 订阅（`RssController`）、用户头像上传（`UploadController.saveAvatar` + `ImageCropUpload.vue`） |
| `backlog.md` 自相矛盾 | 同一文件里 outlet route identity 一处「待做」一处「已完成」 |
| `README.md` 说 28 个单测 | 实际 **185**（`git grep -c "@Test"`），前端另有 97 个用例 |
| `architecture.md` §6 两处论断过期 | 「前端完全无测试基础设施」与「`ArticleService` 零测试」都不再成立 |

外加 `docs/schema.sql` 的 `DROP TABLE` 列表漏了第 7 张表 `favorites`（照 README 的流程重跑
会因表已存在而失败）。

§6 是**快照**，本来就该过期，所以除了改那两行，还在节首写明了它是快照、与后文各 `§NN`
冲突时以后文为准 —— 否则下一个人还会被它误导一次。

**修文档时又发现两处真缺陷**，都记进 `backlog.md` 而不是顺手改（它们各自是一次行为变更）：

- `ArticleService.hot` 只钳上限不钳下限：公开接口 `?size=-1` 会拼出 `LIMIT -1` 直接 500。
- `RssController.SITE_URL` 硬编码 `http://localhost:8080`，换域名必须改代码。

### 33.2 社区种子数据

目标是让这个站**看起来真的有人在用**。数据模型很小，难点全在「别造出会撒谎的数据」上。

**唯一真源**是 `tools/seed/community.json`（账号、文章元数据、互动计划）加 `articles/*.md`
（正文）与 `articles/*.comments.json`（评论树）。执行者是 `tools/seed/seed.mjs`，
它**默认 dry-run**，只有 `--apply` 才写。

**走接口而不是直接写 SQL。** 计数列（`like_count`/`comment_count`/`view_count`）是应用自己
维护的：手写 INSERT 很容易造出「点赞数 38、点赞记录 6 条」这种数据 —— 而假数据最要紧的品质
恰恰是不能撒谎。所以注册、发布、草稿、两级评论、点赞、收藏全部走真实接口，浏览量靠真实
调用详情接口累加（3084 次）。唯一接口做不到的是**发布时间回填**，最后用一条 SQL 把
`created_at` 挪到设计好的时间点上。

**这条约束直接改变了账号设计。** 点赞是行，一篇文章的 `like_count` 必须等于真实行数 ——
只有 6 个账号的话，任何一篇文章的点赞数都超不过 6，再多就是撒谎。于是 6 个写作者之外
另有 8 个「只看不写」的读者账号（点赞与收藏），这也正好是真实社区的样子：写的人少，
看的人多。浏览量不受这个约束（它只是计数器），所以按真实感给到 143~876。

**八篇正文兼做 Markdown 渲染测试集**：每篇侧重一种结构（代码块 / 表格+任务列表 /
SQL+引用 / 长段落 / 嵌套列表 / 图片+图注 / 引用+任务列表 / 多级标题），标签图刻意设计成
有重叠（`#随笔` 5 篇含你原有那篇、`#技术` 4 篇、`#写作` 3、`#时间感悟` 3、`#夏日` 2）。

### 33.3 证据

**dry-run 抓到的两个真坑**（这就是为什么要先跑一遍不写的）：

```
ERROR 1137 Can't reopen table: 'seed_users'          -- MySQL 不允许同一条语句两次引用临时表
ERROR 1267 Illegal mix of collations ... find_in_set  -- 字面量 utf8mb4_0900 撞列上的 unicode_ci
```

两条都改了做法：名单收进会话变量 + `FIND_IN_SET`，字面量显式 `COLLATE`。
另外「同一份名单只允许一个真源」这条是用**报错**守住的：脚本会解析 `clear.sql` 里的名单
并与 `community.json` 比对，不一致直接退出（写这份脚本时它当场抓到过一次格式变更）。

**SQL 断言**（`mysql` 直查）：

| 断言 | 结果 |
|---|---|
| 每篇文章的 `like_count`/`comment_count` 等于真实行数 | 全部相等（脚本末尾也自检一次） |
| 标签重叠 | `#随笔` 5 · `#技术` 4 · `#写作` 3 · `#时间感悟` 3 · `#夏日` 2 |
| 评论树只有两级 | 种子部分 48 根 + 25 回复；全库唯一一条三层评论是**既有数据**（文章 7 的评论 #15，非本次所造） |
| 时间线 | 2026-04-08 → 2026-09-14（昨天一篇、今天一篇） |
| 你原有的 5 篇 | 3 号 `88\|1\|7`、7 号 `1\|0\|3`、10 号 `18\|0\|0`、16 号仍是草稿、17 号 `11\|0\|0` —— 与基线逐字一致 |

**接口侧**：推荐接口终于有东西可推了 —— 第 25 篇（旗舰）拿到 5 条推荐（score 90/68/55/49/38），
**你原来那篇文章 3 也拿到 5 条**（36/30/27/25/24）；草稿对他人 404、对作者 200。

**真机**（Chrome + CDP，1500x950）：首页 10 张卡、标签云与热门榜有内容；详情页正文 3715 字、
10 个二级标题、目录侧栏 14 项；**正文插图 2/2 加载成功**、**列表缩略图 6/6 加载成功**；
后台统计 `18 用户 / 14 文章 / 73 评论 / 27 标签`，`今日新增用户 14 · 今日新增文章 1`，
热门榜第一名就是旗舰那篇。

一句诚实的话：后台那条核对**第一次报的是假 FAIL** —— 我的等待条件写成了「页面上出现
用户/文章/评论/标签」，而它立刻匹到了导航栏里的「写文章」。补测（打印真实 DOM）之后
后台完全正常。又是一次「断言写错，行为没错」（`docs/lessons.md` 第 11 条）。

**可逆与确定**（这是对「写进你真实库」这件事最要紧的一条）：

```
清除 → 0 个种子账号 / 4 用户 / 5 文章 / 10 评论 / 文章 3 仍是 88|1|7   ← 回到基线,一个字节不差
重灌(--skip-images) → 18 用户 / 14 文章(12 已发布) / 73 评论 / 45 赞 / 16 收藏 / 27 标签 / 3202 浏览
                     ↑ 与第一次完全一致 ⇒ 跑十次等于跑一次
```

### 33.4 刻意做的事

- **界面上不标记种子账号**（要的是「像真实社区」）。真实与虚构的唯一区分是
  `tools/seed/README.md` 里那份账号表与 `clear.sql` 里那份名单 —— 两者由脚本强制一致。
- **不碰你原有的 4 篇/4 用户**：脚本在动手前后各核对一次，数量变了就报错退出。
- **清理会连带删除种子文章下的所有评论**（包括不是种子账号写的）—— 打印计数，0 条时
  也不隐藏这一行。理由是文章本身要没了，留孤儿评论行更脏。
- **只删「没有任何文章引用」的标签**；仍被真实文章使用的标签一律不动。
- **种子口令统一 `demo1234` 并写在 `tools/seed/README.md` 顶部**，带一句「上线前必删或必改」。
  这些账号能登录、能发文章，公开库里写着口令就等于公开了发帖权限。
- 库里那 4 篇测试残渣（`测试`、`嵌套验证`、`封面缩略图验证`、`草稿测试`）**一行没碰** ——
  这是你选的；它们现在会出现在首页列表里（第 5、6 位）。要藏起来只需把前两篇的 `status`
  改成 0（可逆的一条 SQL），但那属于改你的数据，等你开口。






---

## 34. 阅读体验包：把算好没人看的字段用上（2026-09-14）

### 34.1 一条新端点：上一章 / 下一章

`GET /api/articles/{id}/neighbors` → `{prev, next}`，每项只有 `id` 与 `title`，**键恒在**
（值是 `null` 表示没有那一侧）。前端读 `neighbors.prev?.id`，所以刻意不加 JSON 的
`NON_NULL` —— 键缺席与值为 `null` 是两件事。

两条口径**刻意不对称**，理由都在方法注释里：

- **当前文章**用 `includingOwnDrafts().require(id)`，与 `detail` 逐字相同：不可见/不存在
  同一个 404；草稿作者看自己的草稿能拿到邻居（否则「详情 200 而邻居 404」才叫自相矛盾）。
- **候选集合**用 `visibility.articles()`，与列表 `page` 同一条路：草稿从侧门出现在别人的
  「下一篇」里就是泄漏。

可见性规则全库只有一个主人（§10），所以这个实现里**一行过滤条件都没有自己写**。
`ArticleQuery` 只加了 `orderByAsc` —— 「下一篇」要的是「最近的一个更晚者」，方向是调用方的
语义而不是可见性规则；不扩它就得在 Service 里手拼 wrapper，那正是「两套可见性」的开始。

边界：并列时间戳用 `created_at < X OR (created_at = X AND id < cur)` 并把 `id` 一起排序，
各 `LIMIT 1`，结果确定。这个端点**是纯读**：不记浏览，否则点一次「下一篇」就把那篇顶进热门榜。

**证据**：后端 185 → **195**。除了首/末篇的 `null`、不可见时 404 且一条候选查询都不发、
响应 JSON 形状、控制器不记浏览之外，**作用域是用 mapper 收到的 wrapper 渲染出的 SQL 断言的**
（登录者必须含 `status` 谓词且不含草稿 OR 组；管理员两条都不许有）—— 防的是 Service 自己
偷偷补过滤，那正是可见性收敛要避免的事。

### 34.2 详情页变厚

后端算好却没人渲染的字段有三个（`summary`、`coverThumb`、`recommendScore`），一个都没上过
界面 —— 详情页因此比它应有的样子空。这一轮全部用上，另加三块读者真的会看的东西：

| 加了什么 | 数据来自 | 失败时的样子 |
|---|---|---|
| 字数与阅读时长（`N 字 · 约 M 分钟`） | 纯模块 `utils/textStats.js` 本地估算 | `chars` 为 0 就不显示 |
| 作者卡（头像/昵称/@用户名/文章数/注册时间，整卡可点） | 已有的 `GET /api/users/{id}` | 整卡不渲染（正文不依赖它） |
| 上一章 / 下一章 | 新端点 | 整块静默隐藏 |
| 相关推荐补封面 + 摘要 + 「相关度 N」 | 已有的推荐接口 | 不显示该字段 |
| 摘要 | `article.summary` | 为空不显示 |

`textStats.js` 是**近似估算**（去空白后的字符数 ÷ 400 字/分钟）：精确字数要完整解析
Markdown，而这个数字的用途只是「值不值得现在读」——为一行屏角小字引一个解析器不划算。
这条判断写在代码注释里，测试也钉住了口径（Markdown 标记照数，并说明为什么不去标记）。

顺带在 `api/http.js` 加了一个 **opt-in** 的 `{silent: true}`：默认契约仍是「失败就弹 toast」，
但作者卡与上下篇这类**正文不依赖**的附加内容失败时，读者既看不懂也没法处理 ——
一个 404 被弹成「网络错误」只是在正文旁边制造噪音。401 不受它影响（会话状态变了必须说）。

### 34.3 证据

**自动化**：后端 185 → 195，前端 97 → 104（`textStats.test.js` 7 条），`npm run build` 通过。

**真机**（Chrome + CDP，1500x950，匿名读者身份），7/7：

```
PASS  字数与阅读时长        3609 字 · 约 9 分钟
PASS  摘要显示              命中「三年、六十三篇文章」
PASS  作者卡                含「海棠」且含 @haitang
PASS  上一篇 →              /article/32(一个人的周末)
PASS  相关推荐              缩略图 5 张加载成功 + 「相关度」小标存在
PASS  点「上一篇」          路径变了、未整页刷新、**标题也真的换成了相邻那篇**
PASS  最早一篇的「下一篇」  → /article/28
```

第六条特意断言了「标题也换了」：路径是同步变的，而正文要等新数据回来（实测约 300ms）。
只断言地址栏变了，就等于只测了 D7 当初的样子。

### 34.4 两处诚实

1. **第一次实测那两条 FAIL 都是我核对的错**：相关推荐是异步加载的（断言跑在它到达之前），
   导航后正文要等约 300ms（我读到了上一篇的标题）。补了等待条件之后 7/7。
   又一次「报红可能只是断言写错了」（`docs/lessons.md` 第 11 条）。为确定起见我还单独
   dump 了一次真实 DOM：5 张缩略图 5 张全部 `naturalWidth > 0`。
2. **「ID 会变」这句话第一次咬到我**：清空重灌之后文章 ID 从 25 变成 34，核对脚本里的硬编码
   ID 当场 404。这正是 `tools/seed/README.md` 里写「引用要按 slug 而不是 id」的原因 ——
   写那句话的时候我只是觉得有道理，现在是实测过一次了。

---

## 35. §31 结案：容器层的错误派发不再被鉴权拒掉（2026-09-14）

### 35.1 先做对照，再改

§31 记着一条实测发现：「用户点停止/关页面」会在容器层留下两条 ERROR 加完整堆栈。
当时的判断是「修法是改授权面，而它有一个**没验证过**的后果：放行之后容器会真的去渲染
`/error`，而此时响应已经提交（SSE 已经写过字节）」。所以这一轮先做对照实验，再动手。

**实验方式**：带真实模型起后端，用裸 TCP 直接读原始字节，第 4 秒**关掉客户端**（普通 FIN，
即浏览器 `abort()` 关的那种），25 秒后数日志。

| | 改之前 | 改之后 |
|---|---|---|
| 断开时的 ERROR 行 | **2** —— 其中一条是 `AuthorizationDeniedException: Access Denied`（读日志的人会以为出了安全事件） | **1** —— `HttpMessageNotWritableException: No converter for [...] with preset Content-Type 'text/event-stream'` |
| `ErrorPage` 处理失败 | 1 | **0** |
| 附带 | —— | 2 条 WARN：`Ignoring exception, response committed`（这是**正确**的处置：写不进去就不写） |
| 直接访问 `GET /error` | 401 | 500，body 只有 `{"timestamp":…,"status":999,"error":"None"}` |
| **正常**一场对话的流 | `: ping` + `event:*` | 同样：2680 字节的 `: ping` 与 `event:message`，**没有任何错误 JSON 被塞进流里** |

最后一行就是对那个「没验证过的后果」的回答：**错误页不会被塞进已经开始的 SSE 流**。
原因是响应已经提交，Spring 的 `DefaultHandlerExceptionResolver` 会直接放弃
（`Ignoring exception, response committed`）—— 客户端那边一个字节都没多。

### 35.2 改动

`SecurityConfig` 的 permitAll 里加了 `"/error"`，并把理由写在代码注释里：

> `/error` 不是接口，而是**容器的错误派发入口**：容器出错时会向它做一次 ERROR 型 dispatch，
> 而那个 dispatch 走一遍过滤器链时是没有身份的（`JwtAuthFilter` 是 `OncePerRequestFilter`，
> 默认跳过 ERROR 派发）。不放行它，任何一次容器层错误都会被拒掉，再叠一条「已提交的响应
> 渲染不了错误页」—— 于是**一次正常用户操作会留下两条 ERROR 加完整堆栈**。

业务接口的规则一条没动（`/api/**` 的每一条都在 `"/error"` 之后）。

### 35.3 剩下的那一条 ERROR（诚实记录，没有收干净）

2 条 ERROR 变成了 1 条 ERROR + 2 条 WARN。剩下这条的性质与原来不同，也**不再有安全含义**：

```
ERROR GlobalExceptionHandler : Unhandled exception
org.springframework.http.converter.HttpMessageNotWritableException:
  No converter for [class java.util.LinkedHashMap] with preset Content-Type 'text/event-stream'
```

机制：ERROR 派发现在能进应用了，于是 Spring 的 `BasicErrorController` 试图把错误 JSON
写进一个**已经提交为 `text/event-stream`** 的响应，找不到转换器。它写不进去（也就没污染流），
但我们的 `GlobalExceptionHandler` 兜底分支把它记成了一条 `Unhandled exception`。

**没有继续收**，两个候选修法都记进 `docs/backlog.md`，因为都超出这一轮的范围：
给 `/error` 一个「响应已提交就什么都不做」的自定义 error controller；或者在
`GlobalExceptionHandler` 里显式处理 `HttpMessageNotWritableException` 并降级为 DEBUG
（「写不出去」不是服务故障）。两者都要各自的一次对照实验。

---

## 36. 推送：把 70 个提交同步到公开库（2026-09-14）

### 36.1 推之前扫了四类东西

上一次推送是 2026-08-09，中间攒下的工作**只存在一块盘上**。推送是最便宜的保险，但那个库是
**公开**的，所以先扫一遍再推（结果先给人看过，点头才推）。

| 扫什么 | 命令（要点） | 结果 |
|---|---|---|
| 邮箱 / 手机号 / 真人姓名 | `git grep -nEi '<邮箱形状>\|1[3-9][0-9]{9}'` | **零命中** |
| GitHub token / 私钥 / AWS key | `git grep -nEi 'ghp_\|github_pat_\|AKIA\|-----BEGIN'` | **零命中** |
| 本机绝对路径 / 临时目录 / 个人目录 | `git grep -nE '[A-Z]:\\\\Users\|%TEMP%\|AppData\|\.dsh'` | **1 处真问题**（见 36.2），另 2 处只是 docs 里提到 `%TEMP%` 这个笼统说法，不含路径 |
| 字面凭证 | `git grep -nEi 'demo1234\|admin123\|123456\|sk-…'` | 见 36.3 |

顺带核对了 `backend/src/main/resources/application-local.yml`（本机 MySQL 口令）：
**从未进过历史**（它现在在 `.gitignore` 里，而 `git log --all -- <file>` 是空的）—— 也就是说
那份口令重来没被推上去过。

### 36.2 唯一一处真改动

`tools/seed/seed.mjs` 里写死了 `C:\Users\zz\.dsh\skills\imggen\gen.py` —— 一个会随仓库公开的
**本机用户名与个人目录**。改成按技能目录的相对位置找：

```js
const IMGGEN = process.env.IMGGEN || join(homedir(), '.dsh', 'skills', 'imggen', 'gen.py')
```

装了技能的机器都能用，`IMGGEN` 仍可覆盖，README 里也写明了。dry-run 复跑正常。

### 36.3 凭证：哪些早就公开，哪些是这一批新公开的

**早就公开的**（上一次推送时就已在库里，与这一轮无关）：`README.md` 的 `root / 123456` 与
`admin / admin123`、`DataInitializer` 里初始管理员的口令、`application.yml` 的 JWT 默认密钥、
`docker-compose.yml` 的默认口令。

**这一批新公开的**：种子账号统一口令 `demo1234`（`tools/seed/README.md`、`community.json`，
以及 `docs/architecture.md` 里那句说明）。

边际风险接近零 —— 默认管理员的口令本来就是公开的。真正的风险只有一条：**别拿默认口令
部署到公网**。这条警告现在写在 `tools/seed/README.md` 顶部（那 14 个种子账号是能登录、
能发文章的）。

### 36.4 一个环境坑：配了代理而代理没起

第一次推送直接失败：

```
fatal: unable to access 'https://github.com/zk-boop/blogSys.git/':
       Failed to connect to 127.0.0.1 port 7890 after 2057 ms
```

诊断（三条命令）：代理来自 **git 的 global 配置**，而且是**只给 github.com 配的**
（`http.https://github.com.proxy = http://127.0.0.1:7890`）；7890 端口**没有服务**；
而直连 GitHub 是通的（`api.github.com` 返回 200）。

处理：**没有改全局配置**（那是他的环境设置，代理起着的时候是有用的），只在这一次命令里旁路：

```
git -c http.https://github.com.proxy= push origin master
→ 1721796..a2b4922  master -> master
```

推送后 `git status -sb` 显示 `## master...origin/master`，本地与远端同步。
这一批共 **70 个提交、146 个文件、约 1.4 万行新增**。

---

## 37. 修目录：种子文章没写小标题（2026-09-14）

### 37.1 症状与根因

用户报告「种子文章的 Markdown 没命中目录的格式要求，目录加载不出来」。核对之后属实，
而且**根因在写作要求里**：

- 目录靠 `extractToc(content)` 从正文抽 `h1`-`h3`（`utils/markdown.js`），并且要求
  **标题数 > 1** 才渲染（`v-if="toc.length > 1"`）；
- 八篇里**只有 A8 有标题**（`##` 10 个、`###` 4 个），`01`–`07` **一个都没有**：
  我给写文章的要求写的是「不要写标题」，第一批理解成「连 `##` 小标题也不写」，
  第二批只有 A8 明确要了多级标题。**是要求写得含糊，不是写得不好。**

### 37.2 改动与自证

给 `01`–`07` 各补 5-6 个 `##` 小标题（如 `01` 的「为什么决定自己写 / 清单和选型 /
旧数据怎么搬 / 踩到的坑 / 部署和备份 / 迁移后的这一年」），**正文一个字不改**。

自证不是靠嘴说的，四条一起：

| 证据 | 结果 |
|---|---|
| `git diff --numstat` | 七个文件全是「新增 N / **删除 0**」（6/0、5/0、6/0、5/0、6/0、5/0、6/0） |
| 新增行内容 | diff 里没有任何 `-` 行，且**所有**新增行都以 `## ` 开头 |
| 去掉 `^## ` 行后逐字对比 | 七个文件全部 `True` |
| 用站点自己的 markdown-it 实渲染 | h2 = 6/5/6/5/6/5/6，`toc.length > 1` 七篇均成立 |

然后重灌种子数据（清后插，复用图片缓存），**真机 6/6**：

```
PASS  #36「把博客从 WordPress 搬到自建站点」目录 6 项:为什么决定自己写 / 清单和选型 / 旧数据怎么搬 / 踩到的坑 / …
PASS  #36 点目录第三项瞄准的是那一段的标题      调用:[{id:"sec-2",tag:"H2",text:"旧数据怎么搬"}]
PASS  #38「一次 MySQL 慢查询排查」目录 6 项:那条出事的 SQL / 第一次 EXPLAIN / 按顺序试了五件事 / …
PASS  #38 点目录第三项 → sec-2「按顺序试了五件事」
PASS  #39「夏日傍晚的菜市场」目录 5 项:声音和气味 / 丝瓜摊前的闲聊 / 西红柿和写作业的女孩 / …
PASS  #39 点目录第三项 → sec-2「西红柿和写作业的女孩」
```

### 37.3 两处诚实

1. **第一版核对报了 3 条假 FAIL**：我用 `window.scrollY` 有没有变大来判断「点目录会不会跳」。
   而探测用的那个 Chrome 窗口**自己完全不能滚** —— `window.scrollTo(0, 500)` 与
   `documentElement.scrollTop = 800` 都不动，尽管 `maxScroll = 6411`。于是「环境不能滚」
   被误报成「目录不跳」。改成断言**对哪个元素调了 `scrollIntoView`**之后 6/6 ——
   这个断言与环境是否真能滚无关，才是这条核对该测的东西。
   又一次「报红可能只是断言写错了」（`docs/lessons.md` 第 11 条），这次错在**拿环境状态当行为证据**。
2. **目录侧栏有 1280px 的 CSS 门槛**（`@media (min-width: 1280px)`），窄一点的窗口里它整块消失，
   而且没有任何替代入口。设计如此，但对手里是 1366×768 笔记本的人来说就是「没有目录」。
   记在这里，没有改 —— 要么降门槛，要么给窄屏一个可折叠的目录入口，那是一次界面决定。

### 37.4 防止复发

`tools/seed/README.md` 里写明了：**每篇至少 3 个 `##` 小标题**，因为目录要求标题数 > 1 ——
下次再写种子内容就不用重新踩一遍。

---

## 38. 上线前的收口与安全硬化（2026-09-14）

这几轮的目标从「把站做厚」换成了「把它做到能交付」—— 验收标准只有两条：**能不能跑起来**、
**跑起来是不是安全的**。所以先把这两条做实，而不是继续加功能。

### 38.1 仓库可见性调整

`github.com/zk-boop/blogSys` 的可见性经历过一次往返：先设为私有做内部整理，后因需要作为
求职展示材料重新转为公开。两次都用 `gh repo edit --visibility <private|public>` 完成
（`gh` 本机已登录），每次改完核实 `isPrivate` 与预期一致。

### 38.2 默认凭证硬化（并且揪出一个真漏洞）

| 硬化 | 做法 |
|---|---|
| 默认 JWT 密钥 | 非 local 且密钥为空/等于仓库默认值/短于 32 字符 → **拒绝启动**，消息里给生成命令。放在 `InitializingBean` 而不是 `CommandLineRunner` —— runner 跑在端口绑定之后，那时候失败已经晚了 |
| 管理员初始口令 | `BLOGSYS_ADMIN_PASSWORD` 优先；local 仍是 `admin123`（日志说明这是本地默认）；其它 profile **随机生成 16 位**并 WARN 打印一次，提示立即保存 |
| 破坏性脚本 | `docs/schema.sql` 加了警告：它是**初始化**脚本（7 个 `DROP TABLE` + 自己 `CREATE DATABASE blog_sys`），不是升级脚本 |

**顺带发现一个真漏洞**：`application.yml` 里写着 `spring.profiles.active: local`。而 `local`
正是「允许默认密钥启动」的那一个 —— 部署者什么都不配、直接 `java -jar` 就落在 local 上，
那道 fail-fast **永远不会触发**。一道被默认值悄悄关掉的护栏等于没有护栏，所以删掉了这个默认：
现在不指定 profile 就是生产口径，本地开发显式加 `--spring.profiles.active=local`。

测试 195 → **225**（新增 30），并做了双方向运行时冒烟：local 放行、prod 秒退且消息原样出现。

### 38.3 从零部署验证

在一份**新复制出来的仓库副本**里按首次部署路径跑了一遍（副本有两个理由：部署者拿到的是新克隆的一份；
以及 `mvn` 会重编译 `target/classes`，而正在跑的 `spring-boot:run` 是惰性加载类的 ——
**这一轮真的撞上过**：截图核对时正好遇到重编译窗口，接口瞬时抛
`TypeNotPresentException: ChatController$ChatRequest$ChatItem not present`，编译一结束就恢复正常）。

| 步骤 | 实测结果 |
|---|---|
| 干净库 + `docs/schema.sql` | 建出 **7 张表**（含此前容易漏掉的 `favorites`） |
| 非 local + 48 位随机 `JWT_SECRET` + 无 `AI_API_KEY` | **启动成功**，2.6 秒 |
| 管理员初始口令 | **随机生成并 WARN 打印**（提示立即保存、尽快修改） |
| 用该口令登录 → 发文 → 列表 → `/rss` | 全部通过（`id=1`、`total=1`、RSS 200） |
| 未配 key 时访问 AI | 返回明确错误帧：「AI 服务未配置:请在环境变量中设置 AI_API_KEY」 |
| 收尾 | 部署库删除；**原有 `blog_sys` 18 用户 / 14 文章 / 73 评论前后一致** |

结论写成 `docs/deploy.md`（环境变量表、两种启动方式、上线前必改 5 条、常见问题 6 条、验证记录）。
**没验证到的**：`docker-compose.yml` —— 本机 Docker Desktop 没运行，这条路径一次都没跑过，
文档里如实写明「要用它请先自己起一遍，不要假定它是好的」。

一句诚实的话：这次冒烟**第一次是假失败** —— 我解析日志里管理员口令的正则写成「取行尾」，
抓到的是提示语尾巴上的 8 个字符，于是登录 400。按 `admin / <口令>` 的形状改对之后通过。
又一次「报红可能只是断言写错了」。

---

## 39. 交付材料与上线前的收尾（2026-09-14）

### 39.1 种子脚本加了「只灌本机」的守卫

`tools/seed/` 那 14 个账号的口令是**公开写在文档里**的（`demo1234`），而且它们能登录、能发文章 ——
往任何对外可达的站上灌一次，就等于给陌生人开了 14 个发帖账号。所以 `seed.mjs` 在 `--apply` 时
会检查 `baseUrl` 的主机名：不是 `localhost/127.0.0.1/::1/0.0.0.0` 就直接**拒绝运行**
（确实要跑可以加 `--force-remote`，后果自负）。dry-run 只警告 —— 它什么都不写。

三个方向都实测过：本机目标正常；把 `baseUrl` 改成 `blog.example.com` 后 `--apply` 被拒
（异常消息里点名了主机与理由）；同一个地址 dry-run 只打一条警告。

### 39.2 README 加「上线前必改」

五条，每条都写清"不改会怎样"：`JWT_SECRET`（已有程序级护栏）、管理员口令、
数据库口令、演示数据（一条命令清空）、AI key 与模型选择。指向 `docs/deploy.md`。

### 39.3 交付材料

| 产物 | 说明 |
|---|---|
| `docs/screenshots/` 5 张 | 首页 / 带目录的文章详情 / AI 助手（含工具调用）/ 后台仪表盘 / 个人主页；1600×1000，每张都目视确认过不是白屏或报错页 |
| README | 新增「截图」与「上线前必改」两节 |

**数字的自相矛盾是这一轮抓出来的**：安全硬化把测试从 195 加到 225，而 README 还写着 185/97、
`deploy.md` 还写着 36 个接口 —— 三处口径必须一致，对不上就是一读就露怯。
已统一成 **225 个后端测试 / 104 个前端用例 / 37 个接口**（接口数按 `/v3/api-docs` 的权威值）。

### 39.4 两条留给下一轮的（都写进了 backlog）

- **`docker-compose.yml` 一次都没验证过**：本机 Docker Desktop 没运行。要在 README 里把它写成开箱即用的能力就得先实测一遍，
  否则使用者第一次部署就会失败。
- **AI 助手在思维链模型下经常吐空**：`AI_MODEL=qwen3.7-plus` 是思维链模型，而 `StreamReducer`
  只读 `delta.content` —— 实测 4 次里 3 次只吐空串或只吐工具名（截图那边重试 6 次才拿到真回答）。
  这是**功能真实状态**，文档如实记录了并建议换非思维链模型。
  `architecture.md` §13.5 早就记过「模型自己产出空回答，不在这里修」，现在它有了实测数字与产品后果。
