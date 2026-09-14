# blogSys API 接口说明

统一返回格式:`{ "code": 200, "message": "ok", "data": ... }`。
除标注(公开)的接口外,需请求头 `Authorization: Bearer <token>`。

## 认证

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/register` | 注册,body: `{username, password, nickname}`,返回 `{token, user}` |
| POST | `/api/auth/login` | 登录,body: `{username, password}`,返回 `{token, user}` |
| GET | `/api/users/me` | 当前用户信息 |
| PUT | `/api/users/me` | 修改资料,body: `{nickname?, avatar?}` |
| GET | `/api/users/me/articles?page=&size=` | 我的文章分页(含草稿) |

## 用户主页(公开)

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/users/{id}` | 用户公开资料,含 `articleCount` |
| GET | `/api/users/{id}/articles?page=&size=` | 该用户已发布文章列表 |

## 文章

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/articles?page=&size=&tagId=&keyword=` | 文章分页列表(公开),`keyword` 匹配标题/内容 |
| GET | `/api/articles/hot?size=` | 热门文章(公开),按浏览量倒序,只含浏览量 > 0 的文章;`size` 默认 5,**服务端上限 20** |
| GET | `/api/articles/{id}` | 文章详情(公开;草稿仅作者/管理员,且浏览量不增加) |
| GET | `/api/articles/{id}/edit` | 文章编辑回填(作者或管理员,浏览量不变) |
| GET | `/api/articles/{id}/neighbors` | 上一篇文章/下一篇文章(公开,与详情同一条可见性判定):`prev` 更早、`next` 更晚,各只含 `id` 与 `title`,没有则为 `null`(**键仍在**,前端按 `neighbors.prev?.id` 判断);候选集合与列表接口同一可见性口径,排序同 `created_at`(并列按 `id` 兜底) |
| POST | `/api/articles` | 发布/存草稿,body: `{title, content, summary?, cover?, tagNames?, draft?}` |
| PUT | `/api/articles/{id}` | 编辑文章(作者或管理员,`draft=false` 即发布) |
| DELETE | `/api/articles/{id}` | 删除文章(作者或管理员,级联删评论/点赞) |

## 评论(v2:嵌套,统一挂到一级评论下)

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/articles/{articleId}/comments` | 评论树(公开),一级评论含 `replies` 与 `replyTo` |
| POST | `/api/articles/{articleId}/comments` | 发表评论/回复,body: `{content, parentId?}` |
| DELETE | `/api/comments/{id}` | 删除评论(评论作者或管理员;删一级评论连带回复) |

## 点赞

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/articles/{articleId}/like` | 点赞/取消点赞(切换),返回 `{liked, likeCount}` |

## 收藏

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/articles/{id}/favorite` | 收藏/取消收藏(切换),返回 `{favorited}` —— 只有状态,没有计数 |
| GET | `/api/users/me/favorites?page=&size=` | 我的收藏分页,按收藏时间倒序 |

两个都以「文章此刻可见」为前提:不可见的文章返回 404,而不是 403(见 `docs/adr/0001`)。

收藏列表返回的是文章列表项,不是收藏记录 —— 因此带 `coverThumb` 等列表字段;
文章详情里的 `favorited` 就是这份集合的成员判定。取消收藏后文章从列表消失,
而 `total` 与当页条数出自同一次查询(不会出现「页内条数少于 `size` 而 total 虚高」)。

## 标签

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/tags` | 全部标签列表(公开) |

## 图片上传

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/uploads?type=content` | multipart 字段 `file`。`type` 控制处理方式,见下。限制 5MB,jpg/png/gif/webp |
| GET | `/uploads/**` | 上传的静态资源(公开) |

`type` 处理规则:

| type | 处理 |
|---|---|
| `avatar` | 居中裁剪 1:1 并缩放 256x256,输出 jpg,返回 `{url}` |
| `cover` | 居中裁剪 16:9 并缩放 1280x720,输出 jpg + 640x360 缩略图,返回 `{url, thumbUrl}` |
| `content` | 原图原样保存 + 640x360 缩略图,返回 `{url, thumbUrl}` |

`thumbUrl` 的缺席是**有意义的**,不是错误:图片解码不了(比如文件名是 `.gif` 而内容不是图)时
确实没有缩略图,响应里就不会有 `thumbUrl` —— 服务端不声称自己写了不存在的文件。
`type` 只认上表三个值,其余一律 400,不会静默按 `content` 存下去。

**`content` 为什么也写缩略图**:列表页的 `coverThumb` 是从文件名反推缩略图的
(`/uploads/X.ext` → `/uploads/X-thumb.jpg`),而封面是个自由文本 URL 字段 ——
作者可以把正文图片的 URL 粘进封面。若正文上传不写缩略图,那次粘贴就必然 404。
命名约定因此对**每一个能解码的上传**都成立,而不只对 `cover` 成立。

文章列表接口会附带 `coverThumb` 字段(封面缩略图 URL,供列表页使用;非 `/uploads/` 来源或 webp 则与 `cover` 相同)。

## AI 助手(需登录)

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/ai/chat` | AI 对话(SSE 流式),body: `{messages: [{role, content}]}`,返回 `text/event-stream` |

`messages` **最多 50 条**(超出是 400「历史消息过多」);服务端喂给模型的只有最近 20 条,
所以浏览器端也只发最近 20 条(`chatHistory.js` 的 `OUTGOING_LIMIT`)—— 两边说的是同一件事。
对话记录存在浏览器本地(`sessionStorage`,按用户区分,见 `architecture.md` §32),
**服务端不保留任何历史**。

SSE 事件类型 —— 这张表是**跨语言契约**:服务端的事件名与载荷形状由 `com.blogsys.ai.SseProtocol` 独家拥有(它的单测 `SseProtocolTest` 就是规格),浏览器端的解析器(`frontend/src/api/ai.js`)按同样的名字读。改名字必然是一次显式的、会让两侧测试都红掉的决定。

| event | data | 说明 |
|---|---|---|
| `message` | `{content}` | 回答内容增量,可拼接出完整回复 |
| `tool` | `{name, args, result?}` | 工具调用。**同一个事件名发两次**:第一次无 `result` 表示开始,第二次带 `result` 表示结束 |
| `error` | `{message}` | 失败。发出后不再有后续事件 |
| `done` | `{}` | **唯一的正常收尾凭证**。客户端只应在收到它时认为回答完整 —— 流被截断时不会出现 |

`done` 与 `error` 互斥且必居其一:两者都没收到就说明连接被截断,客户端应当如实报错,而不是把半截回答当成完整回答。

**心跳帧**(不是事件,没有 `event`/`data` 行):

| 帧 | 频率 | 说明 |
|---|---|---|
| `: ping`(SSE 注释) | 每 10 秒,对话一开始就发一次 | 浏览器按规范**忽略**它。它的用途有三个:响应头不必等到第一个 token 才 flush;服务端靠「写不出去」发现客户端已经断开(这是取消在服务端唯一的传播信号);客户端靠「沉默多久」区分「AI 正在想」与「连接已经死了」 |

两侧的常数必须一起改:服务端是 `ChatController.HEARTBEAT_SECONDS`(10),
浏览器端是 `createAliveWatch` 的 `timeoutMs`(30 = 连丢三次)。

Agent 可用工具:`searchArticles(keyword)`、`getArticleDetail(id)`、`getUserProfile(userId)`、`getHotArticles()`、`recommendArticles(articleId)`、`getSiteStats()`。

**全部为只读**,且这一条现在由构造保证:`getArticleDetail` 走的 `ArticleService.detail` 里没有任何写操作 —— 记浏览是 HTTP 层在 `GET /api/articles/{id}` 上单独调 `recordView` 做的。AI 查一次详情不会改变浏览量(因而不会把自己问过的文章顶进热门榜)。

**`getSiteStats()` 只对管理员开放。** 非管理员的工具清单里根本不会出现它;即使模型凭空说出这个名字,得到的答复也与「未知工具」逐字相同 —— 不给出「这儿有个你不能用的能力」这个信号。

工具清单按提问者计算,可见性规则(草稿、封禁作者、管理员豁免)与 HTTP 接口完全同一口径。

## 相关推荐(公开)

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/articles/{id}/recommend?size=` | 基于标签重叠与标题/摘要关键词打分的相关文章(不含 AI 依赖),返回文章列表,含 `recommendScore` |

## RSS 订阅(公开)

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/rss` | RSS 2.0,`Content-Type: application/rss+xml`;最新 20 篇,按发布时间倒序 |

订阅源是最公开的出口:**不发送 `Authorization`,viewer 恒为匿名**,所以草稿与被封禁作者的文章
都不在其中 —— 读者一旦订阅过,内容就留在别人的阅读器里,事后封禁追不回来。
条目带 `title`/`link`/`guid`/`description`(摘要)/`author`/`pubDate`,
链接按 `SITE_URL`(当前是 `http://localhost:8080`)拼,换域名要改 `RssController.SITE_URL`。

## 管理后台(全部 ADMIN)

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/stats` | 全站统计:用户/文章/评论/标签/点赞总数、今日新增、`hotArticles` 前 5 |
| GET | `/api/admin/users?page=&size=&keyword=` | 用户分页,`keyword` 匹配用户名/昵称 |
| PUT | `/api/admin/users/{id}/status` | 封禁/解封,body: `{status}`,`0`=正常 `1`=封禁;未知值或 `null` 一律 400,不猜默认值 |
| PUT | `/api/admin/users/{id}/role` | 改角色,body: `{role}`,只有 `ADMIN` 被当成管理员,**其余任何值都落回 `USER`**(与 `status` 不同,这里不做 400) |
| GET | `/api/admin/articles?page=&size=&keyword=&status=&userId=` | 文章分页(含草稿) |
| DELETE | `/api/admin/articles/{id}` | 删除文章(级联删评论/点赞/收藏) |
| GET | `/api/admin/comments?page=&size=&keyword=` | 评论分页(含被封禁作者的评论) |
| DELETE | `/api/admin/comments/{id}` | 删除评论(删一级评论连带回复) |
| GET | `/api/admin/tags` | 全部标签(不分页) |
| PUT | `/api/admin/tags/{id}` | 重命名标签,body: `{name}` |
| DELETE | `/api/admin/tags/{id}` | 删除标签 |

`/api/admin/**` 整段是 `hasRole("ADMIN")`,非管理员得到 403。管理员的读路径**没有可见性谓词**
(`ArticleQuery`:管理员完全不追加条件),所以草稿与被封禁作者的内容都看得到 ——
这与公开接口刻意不同(见 `architecture.md` §10)。

两个用户写接口合计三条守卫,消息就是它们拒绝的理由:不能封禁/解封**自己**、不能修改**自己的角色**、
不能把 ADMIN 账号**封禁**;目标用户不存在则 404。

`GET /api/admin/users` 里的 `articleCount` 是**全部文章数(含草稿)**,
而 `GET /api/users/{id}` 里同名字段是**已发布文章数**:同一个字段名两种含义,是刻意保留的既有契约
(改动它会动到前端契约,所以只在两处写明,不静默改名)。

## 错误码

| HTTP | code | 说明 |
|---|---|---|
| 400 | 400 | 参数错误/业务错误 |
| 401 | 401 | 未登录或 token 过期 |
| 403 | 403 | 无权限 |
| 404 | 404 | 资源不存在 |
| 500 | 500 | 服务器内部错误 |
