# blogSys API 接口说明

统一返回格式:`{ "code": 200, "message": "ok", "data": ... }`。
除登录/注册外,接口需请求头 `Authorization: Bearer <token>`。

## 认证

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/register` | 注册,body: `{username, password, nickname}`,返回 `{token, user}` |
| POST | `/api/auth/login` | 登录,body: `{username, password}`,返回 `{token, user}` |
| GET | `/api/users/me` | 当前用户信息 |
| PUT | `/api/users/me` | 修改资料,body: `{nickname?, avatar?}` |

## 文章

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/articles?page=1&size=10&tagId=` | 文章分页列表(公开) |
| GET | `/api/articles/{id}` | 文章详情(公开,浏览量 +1) |
| POST | `/api/articles` | 发布文章,body: `{title, content, summary?, tagNames?}`(需登录) |
| PUT | `/api/articles/{id}` | 编辑文章(作者或管理员) |
| DELETE | `/api/articles/{id}` | 删除文章(作者或管理员,级联删评论/点赞) |

## 评论

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/articles/{articleId}/comments` | 评论列表(公开) |
| POST | `/api/articles/{articleId}/comments` | 发表评论,body: `{content}`(需登录) |
| DELETE | `/api/comments/{id}` | 删除评论(评论作者或管理员) |

## 点赞

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/articles/{articleId}/like` | 点赞/取消点赞(切换,需登录),返回 `{liked, likeCount}` |

## 标签

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/tags` | 全部标签列表(公开) |

## 错误码

| HTTP | code | 说明 |
|---|---|---|
| 400 | 400 | 参数错误/业务错误 |
| 401 | 401 | 未登录或 token 过期 |
| 403 | 403 | 无权限 |
| 404 | 404 | 资源不存在 |
| 500 | 500 | 服务器内部错误 |
