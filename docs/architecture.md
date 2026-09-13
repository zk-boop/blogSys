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

### 5.4 解帧逻辑没有测试面 【已实测的代码事实】

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

`mvn test` 于 2026-09-13 实跑：**28 个测试全绿**（ChatServiceTest 6、AuthServiceTest 5、CommentServiceTest 7、LikeServiceTest 3、RecommendServiceTest 7）。

但缺口比数字看起来大：

| 区域 | 状态 |
|---|---|
| `ArticleService`（429 行，**10 次提交，全库最热**） | **零测试** |
| `CommentService` | 有（7 个），但覆盖创建/删除，不含 `filterBanned` |
| `RecommendService` | 有（7 个），纯算法覆盖良好；**但看不到 N+1**（`userService` 是 mock） |
| `ai/ChatService` | 有（6 个），覆盖工具循环/异常/轮数上限 |
| `ai/OpenAiClient` 帧解析 · `buildPayload` · `SseWriterHttp` 组帧 · `ToolRegistry` · **全部 6 个工具** · `api/ai.js` 解析器 | **零测试** |
| 全部 controller / 安全 / 封禁可见性 / `AdminService` | **零测试** |
| 前端 | **完全无测试基础设施**（无 vitest/jest，`package.json` 只有 dev/build/preview） |
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



