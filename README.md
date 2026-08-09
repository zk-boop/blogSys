# blogSys — 人的创作平台,智能内容发现

多人博客平台:注册登录、Markdown 文章发布、嵌套评论、点赞收藏、管理后台,以及只做**内容检索**的 AI 助手。

## 项目定位

blogSys 是一个尊重创作的内容平台:

- **内容来自人,不来自 AI**。平台提供工具让内容更容易被找到(搜索、推荐、AI 检索),但不提供任何写作/选题/审校 Agent——创作权属于作者本人。作者可以自行选择用任何外部工具润色,这是作者的自由;平台不主动部署"写作 Agent",因为那会吸引灌水内容、摧毁创作生态。
- **AI 助手只读、只查、不生成内容**。它帮助你找到站内文章、查用户资料、看站内统计、获取推荐,所有工具均为只读检索,平台不生成任何内容。

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | JDK 17 · Spring Boot 3.4 · MyBatis-Plus · Spring Security · JWT |
| 前端 | Vue 3 · Vite · Element Plus · Vue Router · Pinia · Axios · markdown-it · cropperjs |
| 数据库 | MySQL 8 |
| AI | OpenAI 兼容 API(兼容阿里云百炼/DeepSeek 等)· function calling · SSE 真流式 |
| 部署 | Docker Compose(MySQL + 后端 + 前端 nginx) |

## 功能

- **用户**:注册/登录(JWT)、个人资料、头像、角色(USER/ADMIN)
- **文章**:发布/编辑/删除(仅作者或管理员)、草稿箱、列表分页、标签筛选、标题/内容搜索、Markdown 渲染 + 目录 + 代码高亮、图片上传(5MB,封面裁剪)
- **互动**:嵌套评论(回复挂一级评论)、点赞、收藏
- **管理后台**:全站统计、用户管理(封禁/角色)、文章/评论/标签管理
- **AI 助手** `/ai`:登录后可用。Agent 通过 6 个只读工具检索站内数据(搜索文章/查看详情/查用户/站内统计/热门文章/相关推荐),SSE 流式输出,打字机效果
- **相关推荐**:文章详情页底部,标签重叠 + 标题/摘要/正文关键词打分,**纯算法,不依赖 AI**

## 目录结构

```
blogSys/
├── backend/   # Spring Boot 后端(com.blogsys.ai = AI 助手内核)
├── frontend/  # Vue 3 前端
├── docs/      # 设计文档(api、backlog)
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

启动时自动创建管理员 `admin / admin123`(如不存在)。接口文档:启动后访问 `http://localhost:8080/swagger-ui.html`。

### 3. 前端

```bash
cd frontend
npm install
npm run dev
```

访问 `http://localhost:5173`。

## Docker 部署(可选)

```bash
docker compose up -d --build
```

- 前端:`http://localhost:8081`
- 后端 API:`http://localhost:8082`
- MySQL 对外端口:`3307`

配置通过环境变量覆盖:见 `docker-compose.yml` 中的 `DB_*`、`JWT_SECRET`、`MYSQL_ROOT_PASSWORD`、`AI_*`。

## AI 助手配置

AI 助手需要 OpenAI 兼容 API,全部走环境变量,不写死在代码里:

| 环境变量 | 说明 |
|---|---|
| `AI_API_KEY` | API Key(也兼容 `AI_LLM_API_KEY`) |
| `AI_BASE_URL` | 兼容端点,如阿里云百炼 `https://.../compatible-mode/v1` |
| `AI_MODEL` | 模型名,如 `qwen-plus`、`gpt-4o-mini` |

未配置时聊天页会提示"AI 服务未配置",不影响其他功能。

## 设计要点

- **SSE 真流式**:`AsyncContext` + 响应流直写,模型边生成边推送;工具调用跨帧聚合(参数分片到达,按 index 拼接)
- **工具循环**:Agent 最多 4 轮决策→执行→喂回,工具异常回传 LLM 不中断对话
- **安全**:6 个工具全部只读,无写操作面;`/api/ai/**` 需登录
- **可测试**:`SseWriter` 接口解耦 Servlet API,28 个单元测试(Mockito)覆盖工具循环/异常/轮数上限/推荐算法

见 [docs/api.md](docs/api.md) 查看完整接口说明,[docs/backlog.md](docs/backlog.md) 查看计划。
