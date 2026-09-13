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
| 排序被塞进 `and(Consumer)` | 生成的 SQL 变成 `AND (ORDER BY ...)`,非法 | `and()` 会用括号包出一个**嵌套条件**,而排序不是条件 | 排序直接作用在外层 wrapper,不要经 `and()` |
| 不可变构建器 + `Consumer` 配错 | 配置写了却完全没生效,一批用例同时红 | `where()` 返回新实例,`Consumer` 把返回值丢掉了 | 用 `UnaryOperator`,或写 `q = q.where(...)` |
| 线程局部泄漏到别的测试类 | 同一用例单跑通过、全量跑失败 | `SecurityContextHolder` 是线程局部的,寿命比测试类长;有测试类塞了登录态却不清理 | 测试类**前后都清**;共用全局状态的测试必须自己负责还原 |
| fail closed 打到测试夹具 | 用例突然报「账号已被封禁」 | 测试造的用户没设 `status`,而 `UserStatus.of(null)` 是 fail closed | 生产列是 `NOT NULL`,所以是夹具建模不完整 —— 补上显式值,别改判定方向 |
| Mockito 严格模式报「桩多余」 | 删掉一段逻辑后测试失败,说某个 stub 没人用 | 严格模式会把**没用到的桩**当失败 | 这不是麻烦,是免费信号:它当场告诉我这次迁移少查了一次库 |
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
| 缺 `:key` 不会报错 | URL 变了正文不变,刷新才对 | 同一 route record 下 params 变化时 Vue 复用实例 | outlet 加 `:key`;把「标识怎么算」抽成纯函数单测(见候选 03) |
| 探针点了链接却是整页刷新 | 前端核对「全绿」,其实什么都没测到 | vue-router 4 的点击拦截在 `RouterLink` 内部,**不是全局 document 监听** —— 动态创建的 `<a>` 点下去会整页刷新 | 驱动 SPA 导航用 `app.config.globalProperties.$router.push`;**导航前打个标记,导航后检查它还在不在** —— 标记没了就说明整页刷新,这条核对无效 |

## 四、环境与部署

| 坑 | 现象 | 根因 | 解法/教训 |
|---|---|---|---|
| 图片 404 | 封面不显示 | vite 代理只配了 `/api`,没配 `/uploads` | 代理清单和真实资源一一对照 |
| 改了没生效 | 反复报旧错误 | 浏览器缓存旧模块 | 先硬刷新,再怀疑代码 |
| Docker 拉不到镜像 | `dial tcp ... connectex` | Docker Hub 被墙;Docker Desktop 代理配置字段随版本变 | 最稳的是 registry-mirrors 加速器 |
| jar 打包失败 | `Unable to rename` | Windows 下运行中的进程锁住 jar | 先停进程再打包 |
| 端口冲突 | 起不来 | 本地 MySQL 3306 已被占用 | 容器端口映射改 3307 |
| **按进程名杀进程** | 脚本收尾「清理我起的无头浏览器」,把使用者**正在用的浏览器**一起杀了(5 次) | `Get-Process chrome \| Stop-Process -Force` 按**名字**匹配,不区分是谁起的进程 | 只杀自己起的那个:`Start-Process -PassThru` 留下 PID,收尾 `Stop-Process -Id $proc.Id`;它派生的子进程用自动化的专用参数(如 `--user-data-dir=<自己的目录>`)从命令行里精确筛。**永远不要按进程名杀** |

## 五、数据与流程

| 坑 | 现象 | 根因 | 解法/教训 |
|---|---|---|---|
| 清库丢 admin | 登录失败 400 | 种子数据只在启动时执行,TRUNCATE 后不重种 | 清库后重启应用 |
| 孤立标签 | 点空标签结果为空 | 删文章只删关联,没删标签本身 | 级联清理要想到"孤儿数据" |
| 删库遇外键 | DROP 失败 | 遗留表外键 | `FOREIGN_KEY_CHECKS=0` |
| 功能砍了又后悔 | 头像只能填 URL | 需求确认时一刀切砍掉"上传" | 砍需求时问一句"高频操作用起来顺吗" |
| **打开编辑页本身就会写库** | 做完一轮 UI 核对,一篇**已发布**文章变成了草稿 | 页面载入时表单赋值后 `loaded` 才置 true,深度 watcher 随即把 `dirty` 判成 true;20 秒后自动保存触发,而它固定以 `draft: true` 保存 | 核对探针**不要停留在编辑页**;要验编辑页就用**草稿**文章,跑完立刻离开。凡是"打开某页"的核对,先问它会不会自己发请求 |

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
8. mock 测试证明不了 SQL:谓词活在 SQL 里,而 mock 的 mapper 不分 viewer 一律返回桩定的行。
   别让这种用例冒充端到端证明 —— 要么断言发出的 SQL 文本,要么拿真实数据核对,要么上真库
9. 「不可见」与「不存在」必须**逐字**同一个响应:两处消息稍有差别就是一个存在性预言机
10. 规则收敛时,方向比彻底更重要:忘了登记安全选项 = 少看见(可接受),
    忘了应用过滤 = 泄漏(不可接受)。设计时让前者成为默认
11. **「通过了」不等于「测到了」** —— 端到端核对必须先证明自己测的是那条路径:
    导航是不是真的走了客户端路由、请求有没有真的发生、断言在旧代码上会不会红。
    一条不会失败的核对,和一个不会失败的测试一样,只提供虚假的安全感。
    **而它的反面同样成立:报红也可能只是断言写错了。** 同一次工作里出现了四次
    「行为正确、核对报 FAIL」:导航整页刷新(测了个寂寞)、`grep` 命中的是 import 行
    而不是 key 绑定、核对走错路径却没断言自己落在哪、正则多转义了一层。
    结论:核对报红时,**先读实际值,再改代码**。两者的症状一模一样,
    区别只在于你去读哪一个
12. **别在序列化之后的文本上做截断** —— `substring` 切在 JSON 字符串中间,出去的就是
    语法错误,而它会作为「数据」回到模型那里。上限要交给**造这份数据的人**执行:
    「砍结构,不砍文本」,砍完重新序列化,出去的每一段都是合法的。
    与第 9 条同源:**坏结构一旦产生,接收方无从分辨它是数据还是噪声。**
13. **探测本身也要写在 `try` 里** —— 「写失败 ⇒ 客户端断开」这条探测,最初把
    `getOutputStream()` 留在了 `try` 外面,于是「还没开始写就失败」恰好是唯一漏掉的一种。
    单测第一次跑就红了(真机不一定抓得到)。写探测时先问:**它自己能抛的那几种失败,
    都在里面了吗?**
14. **配置的语义会被当成意图读** —— `corePoolSize=2 / maxPoolSize=4 / queue 20` 写着 4,
    跑起来稳态是 2(`ThreadPoolExecutor` 只在队列满后才扩容)。一条读得出 4、
    跑不出 4 的配置,会让读它的人做出错误的容量判断。**声称的数字要么等于行为,
    要么在注释里写明它不等于。**
15. **应用层把日志修干净了,容器层可能还在写** —— 客户端断开这条路上,应用这边已经
    只留一行 DEBUG;而 Tomcat 仍会走一次 `/error` 错误派发(被 Spring Security 拒),
    留下两条 ERROR 加堆栈。**「我改的那一层干净了」不等于「这条路径干净了」**:
    验证时把整段日志读完,别只 grep 自己新加的那一行
16. **用户报「以前有、现在没了」时,先分清是回归还是从来没做过** —— AI 对话记录刷新就丢
    这件事,`git show <最早那次提交>:<文件>` 一眼就能看出它从落地那天起就是个组件内的
    `ref([])`(全库也没有 keep-alive)。**先花一条命令确认,再决定改哪里**;跳过这一步,
    就会去「修」一段本来就是这样的代码,而真正的缺陷(比如那条会把半截回答当成完整回答
    留下来的路)反而被漏掉
17. **竞态不是断言的对象** —— 「发出去立刻刷新,看界面上有没有『未完成』那行字」这种核对
    会随机红:谁先跑(cancel 回调还是页面卸载)决定结局,而两种结局都诚实。要测的是
    **两条确定的路**(卸载钩子;载入时的 `revive`),竞态本身用手写一份状态去固定。
    随机红的断言比没有断言更坏 —— 它会训练人忽略红色
