# blogSys — 多人博客平台

多人博客平台:v1 支持用户注册登录、Markdown 文章发布、一层评论、点赞、管理员删帖。

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | JDK 17 · Spring Boot 3.x · MyBatis-Plus · Spring Security · JWT |
| 前端 | Vue 3 · Vite · Element Plus · Vue Router · Pinia · Axios |
| 数据库 | MySQL 8 |

## 目录结构

```
blogSys/
├── backend/   # Spring Boot 后端
├── frontend/  # Vue 3 前端
├── docs/      # 设计文档(schema、backlog)
└── README.md
```

## 快速启动

### 1. 数据库

```bash
mysql -uroot -p123456 < docs/schema.sql
```

默认账号:`root / 123456`,库名 `blog_sys`。

### 2. 后端

```bash
cd backend
mvn spring-boot:run
```

启动时自动创建管理员 `admin / admin123`(如不存在)。

API 文档:`docs/` 见接口说明,后端默认端口 `8080`。
接口文档(OpenAPI):启动后访问 `http://localhost:8080/swagger-ui.html`。

### 3. 前端

```bash
cd frontend
npm install
npm run dev
```

访问 `http://localhost:5173`。

## Docker 部署(可选)

项目提供 `docker-compose.yml`,一键启动 MySQL + 后端 + 前端(nginx):

```bash
docker compose up -d --build
```

- 前端:`http://localhost:8081`
- 后端 API:`http://localhost:8082`
- MySQL 对外端口:`3307`

注意:构建需要能够访问 Docker Hub(镜像加速器已在 `~/.docker/daemon.json` 配置)。配置通过环境变量覆盖,见 `docker-compose.yml` 中的 `DB_*`、`JWT_SECRET`、`MYSQL_ROOT_PASSWORD`。

## 功能范围(v1)

- 用户:注册、登录(JWT)、个人信息
- 文章:发布/编辑/删除(仅作者)、列表分页、详情、Markdown 渲染、标签
- 互动:一层评论、点赞
- 管理:管理员可删除任意文章/评论
- 头像:DiceBear 生成默认头像,不做上传

见 [docs/backlog.md](docs/backlog.md) 查看 v2 计划。
