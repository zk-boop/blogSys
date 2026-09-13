# 开发踩坑记录

blogSys 开发过程中真实遇到并解决的问题,按类别整理。每条的"解法/教训"可直接复用。

## 一、编码与 PowerShell(坑最多的一类)

| 坑 | 现象 | 根因 | 解法/教训 |
|---|---|---|---|
| 中文乱码 | 接口返回中文变 `??` | PS5.1 默认 GBK,JSON body 非 UTF-8 | 请求头带 `charset=utf-8`;换 PS7(默认 UTF-8) |
| 脚本毁文件 | 5 个 .vue 文件里所有 `c` 变成 `o` | `$p[0]` 对**字符串**取的是第一个字符,`Replace('c','o')` 全局替换 | 批量改文件用安全工具;改动前先 commit,坏了用 git 恢复 |
| 文件被写坏 | 中文文件乱码 | `Get-Content`/`Set-Content` 按 ANSI 读、UTF-8 写 | 用 `[IO.File]::ReadAllText` + 无 BOM 写入 |
| UTF-8 BOM | docker 读 config.json 报 `invalid character 'ï'` | PS5.1 `Set-Content -Encoding UTF8` 写 BOM | `WriteAllText(..., UTF8Encoding($false))` |
| 语法差异 | `<` 重定向、`-Form`、`Encoding.Latin1` 不可用 | PS5.1 不支持 | `cmd /c` 或 `source`、curl、换 pwsh |

## 二、Java/Spring Boot

| 坑 | 现象 | 根因 | 解法/教训 |
|---|---|---|---|
| Integer 比较 | 状态判断偶尔不对 | `Integer == Integer` 只对缓存池(±128)内成立 | 一律 `Objects.equals` |
| MyBatis-Plus 参数表惰性填充 | 断言绑定参数时参数表是空的 | `getParamNameValuePairs()` 只在 `getSqlSegment()` 渲染时才被填 | 测绑定之前先调一次 `getSqlSegment()` |
| `inSql` 无法绑定参数 | 想参数化却拼出了裸 SQL | 3.5.7 只有 `inSql(R, String)`,没有 values 参数 | 要绑定就用 `apply(true, "… {0} …", 值)`;别用 `inSql` |
| `apply` 只有布尔重载 | `apply(sql, values)` 编译不过 | 3.5.7 未提供双参便利版本 | 写 `apply(true, sql, values)` |
| 测试里解析方法引用报错 | `LambdaQueryWrapper` 报 NPE 或找不到列 | 方法引用要靠 `TableInfo`,非 Spring 环境下没人初始化 | `TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), X.class)` |
| Mockito 编译歧义 | `verify(...).insert(any())` 编译失败 | MyBatis-Plus 3.5.7 新增 `insert(Collection)` 重载,裸 `any()` 无法推断 | 显式类型 `any(User.class)` |
| 日期格式化崩溃 | RSS 500:`UnsupportedTemporalTypeException` | `LocalDateTime` 无时区偏移,格式符 `Z` 报错 | `.atZone(ZoneId.systemDefault())` |
| Security 重载消失 | `requestMatchers(HttpMethod, RequestMatcher...)` 编译不过 | Spring Security 6.x 无此重载 | 用 `{id:[0-9]+}` 变量正则 |
| HTTP 语义 | 业务错误返回 HTTP 200,body 带 code | 异常处理器没设状态码 | 错误响应必须带真实 HTTP 状态,前端才能正确判断 |

## 三、前端

| 坑 | 现象 | 根因 | 解法/教训 |
|---|---|---|---|
| 多根节点(最隐蔽) | 离开详情页必现"页面加载失败",刷新才恢复 | Vue `<Transition>` 要求子组件单根;多根组件离开动画执行抛错 → 传播到路由错误 | 路由级组件必须单根;看浏览器 console 的 warn 比猜快 |
| router.onError 误报 | 正常返回也触发错误页 | onError 会收到**导航取消**(NavigationFailure) | 用 `error.type !== undefined` 区分 |
| cropperjs v2 API 大变 | `zoom is not a function` | v2 重构为 Web Component,`$` 前缀方法 | 升级库前先看 d.ts,别信 v1 记忆 |
| canvas 极小 | 裁剪画布 200x100 | v2 canvas 尺寸由内容撑开,需显式设 100% | 查库源码的注入样式 |
| Blob 无文件名 | 上传 400"仅支持 jpg/png..." | Blob 没有 name,后端白名单拿不到扩展名 | 前端包 `new File([blob], 'x.jpg')` + 后端 Content-Type 兜底 |
| Vite 运行中白屏 | 切换页面偶发模块加载失败 | 运行中发现新依赖 → 重新预构建 → 旧模块引用失效 | `optimizeDeps.include` 全量预构建 |
| wheel 缩放失效 | 滚轮没反应 | `@wheel.passive` 使 `preventDefault` 无效 | 去掉 passive 修饰符 |

## 四、环境与部署

| 坑 | 现象 | 根因 | 解法/教训 |
|---|---|---|---|
| 图片 404 | 封面不显示 | vite 代理只配了 `/api`,没配 `/uploads` | 代理清单和真实资源一一对照 |
| 改了没生效 | 反复报旧错误 | 浏览器缓存旧模块 | 先硬刷新,再怀疑代码 |
| Docker 拉不到镜像 | `dial tcp ... connectex` | Docker Hub 被墙;Docker Desktop 代理配置字段随版本变 | 最稳的是 registry-mirrors 加速器 |
| jar 打包失败 | `Unable to rename` | Windows 下运行中的进程锁住 jar | 先停进程再打包 |
| 端口冲突 | 起不来 | 本地 MySQL 3306 已被占用 | 容器端口映射改 3307 |

## 五、数据与流程

| 坑 | 现象 | 根因 | 解法/教训 |
|---|---|---|---|
| 清库丢 admin | 登录失败 400 | 种子数据只在启动时执行,TRUNCATE 后不重种 | 清库后重启应用 |
| 孤立标签 | 点空标签结果为空 | 删文章只删关联,没删标签本身 | 级联清理要想到"孤儿数据" |
| 删库遇外键 | DROP 失败 | 遗留表外键 | `FOREIGN_KEY_CHECKS=0` |
| 功能砍了又后悔 | 头像只能填 URL | 需求确认时一刀切砍掉"上传" | 砍需求时问一句"高频操作用起来顺吗" |

## 六、性能:别用理论争论子查询形式,测一次就有答案

2026-09 实测,MySQL 8.0.36,临时表造假数据(2 万用户 / 6 万文章,已发布 90%),取最新 10 篇:

| 写法 | 耗时 | 计划 |
|---|---|---|
| 不相关 `IN (SELECT id FROM users WHERE status = 0)` | **213 ms** | 扫 users → 按 `idx_user` 嵌套循环 → 排序 53400 行 |
| 相关 `EXISTS (… WHERE u.id = a.user_id AND u.status = 0)` | **181 ms** | **完全相同的计划** |
| 同上,但给 users 加 `(status,id)` 索引 | 0.059 ms | 沿 `idx_created` 倒序走,每行探一次主键 |
| 同上,零 DDL 只加 `/*+ INDEX(a idx_created) */` | 0.171 ms | 同上 |
| 同上,只加单列 `users(status)`(非复合) | 0.081 ms | 同上 |

三条结论:

1. **两种子查询形式性能等价** —— MySQL 8 把它们优化成同一个计划。选哪种只该看可读性与能否绑定参数,不该拿性能当理由。
2. **复合索引不是必需的**,单列也行;甚至零 DDL 加个 index hint 就能拿到同样的快计划。
3. **快慢的真正分水岭是连接顺序**:慢计划先扫 users 再 join,快计划沿 `idx_created` 倒序走、`LIMIT 10` 提前收工。索引在这里的作用不是被当作访问路径(内层用的仍是 `PRIMARY`),而是**扰动优化器的成本估算,把连接顺序翻了过来**。

教训:这类问题靠读代码和推理永远得不出结论 —— 一排 `EXPLAIN ANALYZE` 就结束了。而且**今天的列表查询就踩在这个慢计划上**,它与可见性规则无关,是独立存在的性能债。

## 核心方法论

1. 环境差异是第一坑源:Windows/PS5.1/代理/端口,先验证环境再写业务
2. 错误信息要看到底:console 的 warn、HTTP 状态码、异常堆栈,都比猜快
3. 每层留验证点:编译、单测、E2E,错误不跨层
4. git 是后悔药:批量操作前先 commit/status,坏了能 checkout 救场
5. 升级大版本先看 API:cropperjs v2、Spring Security 6 都是破坏性变更
6. 前端兜底别兜错:错误处理要区分"真错误"和"正常流程"(导航取消、HTTP 200 的业务错误)
7. 性能结论必须实测:子查询形式、索引、hint 的取舍,`EXPLAIN ANALYZE` 一次胜过一整轮争论
