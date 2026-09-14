# 部署:从零到能访问

这份文档里的每一步都在本机**实测过一遍**(见文末「验证记录」),不是照着代码推测的。

## 0. 你会拿到什么

| 部分 | 是什么 | 跑在哪 |
|---|---|---|
| `backend/` | Spring Boot 3.4.1 / Java 17 后端,36 个接口 | 默认 8080 |
| `frontend/` | Vue 3 + Vite 前端(开发服务器或构建成静态文件) | 默认 5173 |
| `docs/schema.sql` | MySQL 8 建表脚本(7 张表) | — |
| `tools/seed/` | 可选:一键灌一份「像真有人在用」的演示数据,可一键清空 | — |

## 1. 前置条件

- **JDK 17**(实测 17;更高版本没测过)
- **Maven 3.9+**(或者用 IDE 自带的)
- **MySQL 8**(实测 8.0/8.4 均可;5.7 没测过)
- **Node 20+**(只在前端需要;实测 Node 22.22.3 + Vite 8)

## 2. 建库

```bash
mysql -uroot -p < docs/schema.sql
```

> ⚠️ **这是初始化脚本,不是升级脚本**:它会先 `DROP TABLE` 那 7 张表再重建,并且会自己
> `CREATE DATABASE blog_sys` + `USE blog_sys`。库里有要紧数据时不要跑它。
> 想换成别的库名,改 `docs/schema.sql` 的第 4、5 两行。

跑完应当是 **7 张表**:`users` `articles` `tags` `article_tags` `comments` `likes` `favorites`。

## 3. 启动后端

### 环境变量

| 变量 | 必填 | 说明 |
|---|---|---|
| `DB_URL` | 是 | 例:`jdbc:mysql://localhost:3306/blog_sys?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true` |
| `DB_USERNAME` / `DB_PASSWORD` | 是 | 数据库账号 |
| `JWT_SECRET` | **生产必填** | 至少 32 字符的随机串,例:`openssl rand -base64 48`。**非 local 启动时**,没给、还是仓库默认值、或短于 32 字符都会**直接拒绝启动** |
| `BLOGSYS_ADMIN_PASSWORD` | 否 | 管理员初始口令;不填时:local 用 `admin123`,非 local **随机生成并打在 WARN 日志里** |
| `AI_API_KEY` | 否 | AI 助手用的 OpenAI 兼容 key;不填时 AI 助手会明确回「AI 服务未配置」,其它功能不受影响 |
| `AI_BASE_URL` / `AI_MODEL` | 否 | 默认 `https://api.openai.com/v1` / `gpt-4o-mini` |

### 启动

```bash
cd backend

# 本地开发:用 local profile(允许默认 JWT 密钥、管理员口令固定 admin123、DB 配置读 application-local.yml)
mvn spring-boot:run -Dspring-boot.run.profiles=local

# 生产口径:不指定 profile 就是生产口径,必须显式给 JWT_SECRET 与 DB_*
DB_URL='...' DB_USERNAME=root DB_PASSWORD='...' JWT_SECRET='<32 位以上随机串>' mvn spring-boot:run
```

> **第一次启动请立刻看日志。** 非 local 且没给 `BLOGSYS_ADMIN_PASSWORD` 时,管理员口令是随机
> 生成的,只在日志里出现这一次:
> ```
> WARN ... DataInitializer : 初始化管理员账号: admin / <口令> —— 这是本次随机生成的初始口令,请立即保存;登录后请尽快修改。
> ```

启动成功的标志:`Started BlogSysApplication`;自检:`curl http://127.0.0.1:8080/api/tags` 返回 200。

## 4. 前端

```bash
cd frontend
npm install

# 开发:npm run dev → http://localhost:5173(已配好把 /api 与 /uploads 转发到 8080)
npm run dev

# 上线:构建成静态文件,交给 nginx 之类的静态服务器
npm run build      # 产物在 frontend/dist
```

前端要改后端地址时看 `frontend/vite.config.js` 的 `server.proxy`(开发)与 `frontend/nginx.conf`(生产)。

## 5. 上线前必改清单

1. **`JWT_SECRET`** —— 用随机值。非 local 启动没给会直接被拒,这一条由程序保证。
2. **管理员口令** —— 用 `BLOGSYS_ADMIN_PASSWORD` 指定,或从启动日志里抄走随机口令并立即改掉。
   仓库和文档里写的 `admin / admin123` **只适用于本地**。
3. **数据库口令** —— 别用 `root` + 弱口令;`application-local.yml` 里那份只是本地配置(它不在版本控制里)。
4. **演示数据** —— `tools/seed/` 那 14 个账号的口令统一是 `demo1234`,而且**能登录、能发文章**。
   不想留就 `mysql -uroot -p blog_sys < tools/seed/clear.sql` 一条命令清干净。
5. **AI key** —— 如果开了 AI 助手,`AI_API_KEY` 自己申请,额度自己承担。

## 6. 常见问题

**Q:启动报「JWT_SECRET 未配置或仍是仓库默认值」。**
A:这是故意的护栏。给一个 32 字符以上的随机密钥;本地开发可以改用 `--spring-boot.run.profiles=local`。

**Q:登录不了,提示用户名或密码错误。**
A:非 local 第一次启动时管理员口令是随机的,去启动日志里搜「初始化管理员账号」。

**Q:AI 助手回「AI 服务未配置」。**
A:没配 `AI_API_KEY`。这是预期行为 —— 不配 key 不影响其它功能。

**Q:我自己改了代码,正在跑的服务突然报奇怪的类找不到。**
A:如果你在服务运行时跑了 `mvn test`/`mvn compile`,`target/classes` 会被重编译,而
`spring-boot:run` 是惰性加载类的 —— 重启服务即可。**本地验证请在一个副本里跑,或者先停服务。**

**Q:数据库想换个名字。**
A:改 `docs/schema.sql` 第 4、5 行的库名,再把 `DB_URL` 指过去。

**Q:图片上传到哪里了?**
A:`blogsys.upload.dir`(默认 `backend/uploads`,相对启动目录),通过 `/uploads/**` 对外提供。
备份时别只备份数据库 —— 图片不在库里。

## 7. 验证记录

2026-09-14,在一份**新复制出来的仓库副本**里(避免重编译打扰正在运行的实例),按下面这条路径实测:

| 步骤 | 结果 |
|---|---|
| 干净库 + `docs/schema.sql` | 建出 **7 张表**(含此前容易漏掉的 `favorites`) |
| 非 local profile + 48 位随机 `JWT_SECRET` + 无 `AI_API_KEY` | **启动成功**,2.6 秒 |
| 管理员初始口令 | **随机生成**并 WARN 打印:`admin / <16 位>` |
| 用该口令登录 | 成功,token 长度 192 |
| 发一篇文章 | 成功,`id=1`;列表 `total=1` |
| `/rss` | 200 |
| 未配 key 时访问 AI | 返回明确错误帧:`AI 服务未配置:请在环境变量中设置 AI_API_KEY(OpenAI 兼容 API Key)` |
| 收尾 | 部署库已删除;**原有 `blog_sys` 的账(18 用户 / 14 文章 / 73 评论)前后完全一致** |

**没验证到的**:`docker-compose.yml`。本机 Docker Desktop 没有运行,这条路径**一次都没跑过** ——
要用它请先自己起一遍,不要假定它是好的。
