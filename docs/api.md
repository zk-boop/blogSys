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
| POST | `/api/uploads` | multipart 字段 `file`,返回 `{url: "/uploads/xxx.png"}`;限制 5MB,jpg/png/gif/webp |
| GET | `/uploads/**` | 上传的静态资源(公开) |

## 错误码

| HTTP | code | 说明 |
|---|---|---|
| 400 | 400 | 参数错误/业务错误 |
| 401 | 401 | 未登录或 token 过期 |
| 403 | 403 | 无权限 |
| 404 | 404 | 资源不存在 |
| 500 | 500 | 服务器内部错误 |
