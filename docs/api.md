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
| GET | `/api/articles/{id}` | 文章详情(公开;草稿仅作者/管理员,且浏览量不增加) |
| GET | `/api/articles/{id}/edit` | 文章编辑回填(作者或管理员,浏览量不变) |
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
| `avatar` | 居中裁剪 1:1 并缩放 256x256,输出 jpg |
| `cover` | 保留原图 + 生成 640x360 缩略图,返回 `{url, thumbUrl}` |
| `content` | 原样保存,返回 `{url}` |

文章列表接口会附带 `coverThumb` 字段(封面缩略图 URL,供列表页使用;非 `/uploads/` 来源或 webp 则与 `cover` 相同)。

## AI 助手(需登录)

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/ai/chat` | AI 对话(SSE 流式),body: `{messages: [{role, content}]}`,返回 `text/event-stream` |

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

## 错误码

| HTTP | code | 说明 |
|---|---|---|
| 400 | 400 | 参数错误/业务错误 |
| 401 | 401 | 未登录或 token 过期 |
| 403 | 403 | 无权限 |
| 404 | 404 | 资源不存在 |
| 500 | 500 | 服务器内部错误 |
