#!/usr/bin/env node
/**
 * 社区种子数据 —— 让这个站看起来像一个真的有人在用的博客社区。
 *
 * ## 它做什么
 *
 * 把 `community.json`(账号、文章元数据、互动计划)+ `articles/*.md`(正文)+
 * `articles/*.comments.json`(评论)变成数据库里的一份真实数据,过程**全部走真实接口**:
 * 注册 → 登录 → 上传封面 → 发布/存草稿 → 评论与回复 → 点赞 → 收藏 → 浏览。
 * 只有一件事不是接口能做的:发布时间要回填到过去六个月,所以最后一步用一条 SQL
 * 把 `created_at` 挪到设计好的时间点上。
 *
 * 为什么坚持走接口而不是直接写 SQL:计数列(`like_count`/`comment_count`/`view_count`)
 * 由应用自己维护。手写 INSERT 很容易造出"点赞数 38、点赞记录 6 条"这种**会撒谎的数据**;
 * 走接口则是由构造保证一致的。
 *
 * ## 怎么用
 *
 *   node tools/seed/seed.mjs                     # 默认 dry-run:什么都不写,只打印计划
 *   node tools/seed/seed.mjs --apply             # 真写:先清后插
 *   node tools/seed/seed.mjs --apply --skip-images   # 复用上次上传的图(见 .image-cache.json)
 *
 * 前提:后端在 `community.json.baseUrl` 上跑着;本机有 `mysql` 客户端;
 * 出图需要本机 ComfyUI(可用 `--skip-images` 绕开)。
 *
 * ## 安全阀
 *
 * 1. **默认不写**:不加 `--apply` 就只读不写,连清理都不做。
 * 2. **只碰名单上的账号**:清理与写入都以 `community.json.accounts` 里的用户名为边界,
 *    你原有的账号一行都不会被碰 —— 脚本会在动手前后各核对一次。
 * 3. **清单一致**:`clear.sql` 里那份用户名清单必须与 `community.json` 完全一致,
 *    不一致就直接报错退出(同一份名单只允许有一个真源)。
 */
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const ARTICLES_DIR = join(HERE, 'articles')
const CLEAR_SQL = join(HERE, 'clear.sql')
const IMAGE_CACHE = join(HERE, '.image-cache.json')

/**
 * imggen 的命令行工具。
 *
 * <p>默认按**技能目录的相对位置**找(技能装在 {@code ~/.dsh/skills/imggen/gen.py}),
 * 所以它不绑定某一台机器、也不在代码里写死谁的用户名;装了别处就用环境变量 `IMGGEN` 覆盖。
 * 找不到时只有出图那一步会失败,`--skip-images` 与其它步骤都不受影响。
 */
const IMGGEN = process.env.IMGGEN || join(homedir(), '.dsh', 'skills', 'imggen', 'gen.py')
/** 出图落在系统的临时目录:图是内容,不进仓库;复现靠 seed 与提示词,两者都在 community.json 里。 */
const IMAGE_DIR = process.env.SEED_IMAGE_DIR || join(process.env.TEMP || '/tmp', 'blogsys-seed-images')

/** 你原有的账号 —— 只用来做"没被碰过"的前后核对。 */
const PREEXISTING = ['admin', 'zzkk', 'alice', 'bob']

const argv = new Set(process.argv.slice(2))
const APPLY = argv.has('--apply')
const SKIP_IMAGES = argv.has('--skip-images')

const community = JSON.parse(readFileSync(join(HERE, 'community.json'), 'utf8'))
const accounts = community.accounts
const usernames = accounts.map((a) => a.username)

// ---------------------------------------------------------------- 计划

/** 每篇文章的完整计划:元数据 + 正文 + 评论树。正文里的 {{imgN}} 先原样留着。 */
function buildPlan() {
  const errors = []
  const warnings = []

  const articles = community.articles.map((meta, index) => {
    const bodyPath = join(ARTICLES_DIR, meta.file)
    const commentsPath = join(ARTICLES_DIR, meta.commentsFile)
    if (!existsSync(bodyPath)) {
      errors.push(`缺少正文文件:articles/${meta.file}`)
      return null
    }
    if (!existsSync(commentsPath)) {
      errors.push(`缺少评论文件:articles/${meta.commentsFile}`)
      return null
    }
    if (!meta.summary) {
      warnings.push(`articles[${index}](${meta.key})的 summary 还是 null —— 列表卡片与相关推荐会空着`)
    }
    return {
      ...meta,
      body: readFileSync(bodyPath, 'utf8').trim(),
      comments: JSON.parse(readFileSync(commentsPath, 'utf8')),
    }
  }).filter(Boolean)

  for (const article of articles) {
    if (!usernames.includes(article.author)) {
      errors.push(`${article.key} 的作者 ${article.author} 不在账号名单里`)
    }
    for (const name of [...(article.likes || []), ...(article.favorites || [])]) {
      if (!usernames.includes(name)) {
        errors.push(`${article.key} 的互动名单里有未知账号 ${name}`)
      }
    }
    for (const name of (article.favorites || [])) {
      if (!(article.likes || []).includes(name)) {
        warnings.push(`${article.key}:${name} 收藏了却没点赞 —— 现实里少见,确认是不是写错了`)
      }
    }
    const roots = article.comments.length
    const replies = article.comments.reduce((sum, c) => sum + (c.replies?.length || 0), 0)
    for (const comment of article.comments) {
      for (const one of [comment, ...(comment.replies || [])]) {
        if (!usernames.includes(one.author)) {
          errors.push(`${article.key} 的评论作者 ${one.author} 不在账号名单里`)
        }
      }
    }
    if (roots < 3) warnings.push(`${article.key} 只有 ${roots} 条顶层评论,评论区会显得冷清`)
    article.stats = { roots, replies }
  }

  const draft = community.draft
  if (draft) {
    const draftPath = join(ARTICLES_DIR, draft.file)
    if (!existsSync(draftPath)) {
      errors.push(`缺少草稿正文:articles/${draft.file}`)
    } else {
      draft.body = readFileSync(draftPath, 'utf8').trim()
    }
    if (!usernames.includes(draft.author)) {
      errors.push(`草稿的作者 ${draft.author} 不在账号名单里`)
    }
  }

  return { articles, draft, errors, warnings }
}

/** clear.sql 里那份名单必须与 community.json 一致 —— 同一份名单只允许有一个真源。 */
function assertClearListMatches() {
  const sql = readFileSync(CLEAR_SQL, 'utf8')
  const line = sql.match(/SET @seed_usernames\s*=\s*'([^']*)'/)
  if (!line) {
    throw new Error('clear.sql 里找不到 `SET @seed_usernames = \'...\'` —— 清理名单的形状变了,先对齐这个检查')
  }
  const inserted = line[1].split(',').map((s) => s.trim()).filter(Boolean)
  const missing = usernames.filter((name) => !inserted.includes(name))
  const extra = inserted.filter((name) => !usernames.includes(name))
  if (missing.length || extra.length) {
    throw new Error(
      `clear.sql 的账号清单与 community.json 不一致(缺:${missing.join(',') || '无'} / 多:${extra.join(',') || '无'})。` +
      '两边必须一致,否则清理会漏掉或误删。',
    )
  }
  return inserted.length
}

// ---------------------------------------------------------------- 工具

function mysql(sql) {
  const m = community.mysql
  return execFileSync(
    'mysql',
    [`-u${m.user}`, `-p${m.password}`, '--default-character-set=utf8mb4', '-N', '-B', m.database],
    { encoding: 'utf8', input: sql },
  ).trim()
}

async function api(path, { method = 'GET', token, body, form } = {}) {
  const headers = {}
  if (token) headers.Authorization = `Bearer ${token}`
  if (body) headers['Content-Type'] = 'application/json'
  const res = await fetch(community.baseUrl + path, {
    method,
    headers,
    body: form || (body ? JSON.stringify(body) : undefined),
  })
  const text = await res.text()
  let json
  try {
    json = JSON.parse(text)
  } catch {
    throw new Error(`${method} ${path} → 非 JSON 响应(HTTP ${res.status}):${text.slice(0, 200)}`)
  }
  if (!res.ok || json.code !== 200) {
    throw new Error(`${method} ${path} → HTTP ${res.status} code=${json.code} ${json.message}`)
  }
  return json.data
}

/** 把 'YYYY-MM-DDTHH:MM:SS' 往后推若干小时,返回 MySQL 认的格式。 */
function shift(iso, hours) {
  const date = new Date(iso)
  date.setHours(date.getHours() + hours)
  return date.toISOString().slice(0, 19).replace('T', ' ')
}

function log(step, message) {
  console.log(`[${step}] ${message}`)
}

// ---------------------------------------------------------------- 出图与上传

function loadImageCache() {
  return existsSync(IMAGE_CACHE) ? JSON.parse(readFileSync(IMAGE_CACHE, 'utf8')) : {}
}

function generateImage(name, spec) {
  mkdirSync(IMAGE_DIR, { recursive: true })
  execFileSync(
    'python',
    [IMGGEN, '--preset', spec.preset || 'scene', '--style', spec.style || 'scene',
      '--prompt', spec.prompt, '--name', name, '--seed', String(spec.seed), '--out', IMAGE_DIR],
    { stdio: ['ignore', 'pipe', 'inherit'], encoding: 'utf8' },
  )
  const produced = readdirSync(IMAGE_DIR)
    .filter((f) => f.startsWith(`${name}-`) && f.endsWith('.png'))
    .sort()
    .pop()
  if (!produced) throw new Error(`出图失败:${name} 在 ${IMAGE_DIR} 里找不到产物`)
  return join(IMAGE_DIR, produced)
}

async function uploadImage(filePath, type, token) {
  const form = new FormData()
  form.append('file', new Blob([readFileSync(filePath)], { type: 'image/png' }), 'seed.png')
  form.append('type', type)
  return api('/api/uploads', { method: 'POST', token, form })
}

/** 出图(或复用缓存)并上传,返回可写进正文/封面的 URL 集合。 */
async function prepareImages(plan, tokens, cache) {
  const result = {}
  for (const article of plan.articles) {
    const author = article.author
    const cover = `cover-${article.key}`
    if (!cache[cover] || !SKIP_IMAGES) {
      if (SKIP_IMAGES && !cache[cover]) {
        throw new Error(`${cover} 没有缓存,不能 --skip-images。先跑一次完整流程。`)
      }
      const file = generateImage(cover, article.cover)
      const uploaded = await uploadImage(file, 'cover', tokens[author])
      cache[cover] = { url: uploaded.url, thumbUrl: uploaded.thumbUrl, seed: article.cover.seed, prompt: article.cover.prompt }
      log('img', `${cover} 生成并上传 → ${uploaded.url}`)
    }
    result[cover] = cache[cover]

    for (const [key, spec] of Object.entries(article.images || {})) {
      const name = `${key}-${article.key}`
      if (!cache[name] || !SKIP_IMAGES) {
        if (SKIP_IMAGES && !cache[name]) {
          throw new Error(`${name} 没有缓存,不能 --skip-images。`)
        }
        const file = generateImage(name, spec)
        const uploaded = await uploadImage(file, 'content', tokens[author])
        cache[name] = { url: uploaded.url, seed: spec.seed, prompt: spec.prompt }
        log('img', `${name} 生成并上传 → ${uploaded.url}`)
      }
      result[name] = cache[name]
    }
  }
  writeFileSync(IMAGE_CACHE, JSON.stringify(cache, null, 2))
  return result
}

// ---------------------------------------------------------------- 主流程

/**
 * 种子数据只该灌进**本机**演示库。
 *
 * <p>理由不是洁癖:这 14 个账号的口令是公开写在 `README.md` 里的(`demo1234`),而且它们能登录、
 * 能发文章。往任何对外可达的站上灌一次,就等于给陌生人开了 14 个发帖账号 ——
 * 这不是"演示数据",是后门。dry-run 只警告(它什么都不写),`--apply` 直接拒绝。
 */
function assertLocalTarget() {
  const host = new URL(community.baseUrl).hostname
  const local = ['localhost', '127.0.0.1', '::1', '0.0.0.0']
  if (local.includes(host)) return true
  const message =
    `baseUrl 指向 ${host},不是本机。种子账号的口令是公开的(demo1234)且能发文章,` +
    '灌到对外可达的站上等于开后门。确实要这么做就加 --force-remote,并自己承担后果。'
  if (APPLY && !argv.has('--force-remote')) throw new Error(message)
  console.log(`⚠️  ${message}`)
  return false
}

const plan = buildPlan()
const clearListSize = assertClearListMatches()
assertLocalTarget()

console.log(`社区种子计划:${accounts.length} 个账号(${accounts.filter((a) => a.kind === 'reader').length} 个只看不写)、` +
  `${plan.articles.length} 篇已发布、${plan.draft ? 1 : 0} 篇草稿、` +
  `${plan.articles.reduce((n, a) => n + a.stats.roots + a.stats.replies, 0)} 条评论、` +
  `${plan.articles.reduce((n, a) => n + (a.likes?.length || 0), 0)} 次点赞、` +
  `${plan.articles.reduce((n, a) => n + (a.favorites?.length || 0), 0)} 次收藏、` +
  `${plan.articles.reduce((n, a) => n + a.views, 0)} 次浏览。`)
console.log(`清理名单:clear.sql 里 ${clearListSize} 个用户名,与 community.json 一致。\n`)

for (const warning of plan.warnings) console.log(`⚠️  ${warning}`)
if (plan.warnings.length) console.log()
for (const error of plan.errors) console.log(`❌ ${error}`)
if (plan.errors.length) {
  console.log('\n计划有错,先修掉再跑。')
  process.exit(1)
}

if (!APPLY) {
  console.log('--- dry-run:以下是将会发生的事,什么都没有写 ---\n')
  console.log('1) 清理(clear.sql):按名单删除下列账号及其作品,再删除只剩空壳的标签')
  // 只读计数:与 clear.sql 用同一份名单、同一套判据(理由见那个文件的注释)
  const usernamesSql = usernames.join(',')
  const preexistingSql = PREEXISTING.map((u) => `'${u}'`).join(', ')
  const counts = mysql(`
    SET NAMES utf8mb4;
    SET @seed_usernames = '${usernamesSql}' COLLATE utf8mb4_unicode_ci;
    SET @seed_user_ids = (SELECT GROUP_CONCAT(id) FROM users WHERE FIND_IN_SET(username, @seed_usernames));
    SET @seed_article_ids = (SELECT GROUP_CONCAT(id) FROM articles WHERE FIND_IN_SET(user_id, @seed_user_ids));
    SELECT CONCAT_WS(' | ',
      CONCAT('种子账号 ', (SELECT COUNT(*) FROM users WHERE FIND_IN_SET(username, @seed_usernames))),
      CONCAT('种子文章 ', (SELECT COUNT(*) FROM articles WHERE FIND_IN_SET(id, @seed_article_ids))),
      CONCAT('将被删的评论 ', (SELECT COUNT(*) FROM comments WHERE FIND_IN_SET(user_id, @seed_user_ids) OR FIND_IN_SET(article_id, @seed_article_ids))),
      CONCAT('其中非种子账号写的 ', (SELECT COUNT(*) FROM comments WHERE FIND_IN_SET(article_id, @seed_article_ids) AND NOT FIND_IN_SET(user_id, @seed_user_ids))),
      CONCAT('将被删的点赞 ', (SELECT COUNT(*) FROM likes WHERE FIND_IN_SET(user_id, @seed_user_ids) OR FIND_IN_SET(article_id, @seed_article_ids))),
      CONCAT('将被删的收藏 ', (SELECT COUNT(*) FROM favorites WHERE FIND_IN_SET(user_id, @seed_user_ids) OR FIND_IN_SET(article_id, @seed_article_ids))),
      CONCAT('你原有的账号还在 ', (SELECT COUNT(*) FROM users WHERE username IN (${preexistingSql}))));
  `)
  console.log(`   ${counts}\n`)
  console.log('2) 写入')
  for (const article of plan.articles) {
    console.log(`   ${article.key} ${article.publishedAt}  ${article.author.padEnd(8)} ` +
      `${article.title}  [${article.tags.join(' ')}]  ${article.stats.roots}+${article.stats.replies} 评论  ` +
      `${article.likes.length} 赞  ${article.favorites.length} 收藏  ${article.views} 浏览`)
  }
  if (plan.draft) {
    console.log(`   ${plan.draft.key} ${plan.draft.createdAt}  ${plan.draft.author.padEnd(8)} ${plan.draft.title}  [草稿]`)
  }
  console.log(`\n   封面:${SKIP_IMAGES ? '复用缓存' : `${plan.articles.length} 张(imggen,约 ${plan.articles.length * 17} 秒)`}` +
    `;正文插图 ${plan.articles.reduce((n, a) => n + Object.keys(a.images || {}).length, 0)} 张`)
  console.log(`   时间线:文章与评论的 created_at 回填到 ${plan.articles[0].publishedAt} ~ ${plan.articles.at(-1).publishedAt}`)
  console.log(`   封禁:${(community.banned || []).join(', ') || '无'}`)
  console.log('\n要真写就加 --apply。')
  process.exit(0)
}

// ---------------------------------------------------------------- --apply

log('0/9', '核对你原有的账号还在(前后各一次)')
const before = mysql(`SELECT COUNT(*) FROM users WHERE username IN ('${PREEXISTING.join("','")}');`)
if (before !== String(PREEXISTING.length)) {
  throw new Error(`预期你原有 ${PREEXISTING.length} 个账号,实际查到 ${before} 个 —— 停下来先看清现状`)
}

log('1/9', '清理旧的种子数据(clear.sql)')
console.log(mysql(readFileSync(CLEAR_SQL, 'utf8')))

log('2/9', '注册账号并登录')
const tokens = {}
const ids = {}
for (const account of accounts) {
  const created = await api('/api/auth/register', {
    method: 'POST',
    body: { username: account.username, password: community.password, nickname: account.nickname },
  })
  tokens[account.username] = created.token
  ids[account.username] = created.user.id
  // 头像:站点支持外链,种子账号用 dicebear,和库里既有账号一个路子
  await api('/api/users/me', { method: 'PUT', token: tokens[account.username], body: { avatar: account.avatar } })
}
log('2/9', `${accounts.length} 个账号就绪(口令统一,见 README)`)

log('3/9', '出图并上传(封面 + 正文插图)')
const cache = loadImageCache()
const images = await prepareImages(plan, tokens, cache)

log('4/9', '发布文章')
const published = []
for (const article of plan.articles) {
  let body = article.body
  for (const [key, spec] of Object.entries(article.images || {})) {
    body = body.split(`{{${key}}}`).join(images[`${key}-${article.key}`].url)
  }
  if (body.includes('{{')) throw new Error(`${article.key} 的正文里还有没被替换的占位符`)
  const cover = images[`cover-${article.key}`]
  const id = await api('/api/articles', {
    method: 'POST',
    token: tokens[article.author],
    body: {
      title: article.title,
      content: body,
      summary: article.summary,
      cover: cover.url,
      tagNames: article.tags,
      draft: false,
    },
  })
  published.push({ ...article, id })
  log('4/9', `${article.key} → #${id} ${article.title}`)
}

if (plan.draft) {
  const id = await api('/api/articles', {
    method: 'POST',
    token: tokens[plan.draft.author],
    body: {
      title: plan.draft.title,
      content: plan.draft.body,
      summary: plan.draft.summary || '',
      tagNames: plan.draft.tags,
      draft: true,
    },
  })
  plan.draft.id = id
  log('4/9', `${plan.draft.key} → #${id} ${plan.draft.title}(草稿)`)
}

log('5/9', '评论与回复')
const commentTimes = []
for (const article of published) {
  let rootIndex = 0
  for (const comment of article.comments) {
    const root = await api(`/api/articles/${article.id}/comments`, {
      method: 'POST',
      token: tokens[comment.author],
      body: { content: comment.text },
    })
    commentTimes.push([root.id, shift(article.publishedAt, 6 + rootIndex * 9)])
    let replyIndex = 0
    for (const reply of comment.replies || []) {
      const created = await api(`/api/articles/${article.id}/comments`, {
        method: 'POST',
        token: tokens[reply.author],
        body: { content: reply.text, parentId: root.id },
      })
      commentTimes.push([created.id, shift(article.publishedAt, 6 + rootIndex * 9 + 1 + replyIndex)])
      replyIndex++
    }
    rootIndex++
  }
}
log('5/9', `${commentTimes.length} 条`)

log('6/9', '点赞与收藏')
let likes = 0
let favorites = 0
for (const article of published) {
  for (const name of article.likes) {
    await api(`/api/articles/${article.id}/like`, { method: 'POST', token: tokens[name] })
    likes++
  }
  for (const name of article.favorites) {
    await api(`/api/articles/${article.id}/favorite`, { method: 'POST', token: tokens[name] })
    favorites++
  }
}
log('6/9', `${likes} 次点赞、${favorites} 次收藏`)

log('7/9', '浏览(真实调用详情接口,让 view_count 自然长起来)')
let views = 0
const readerToken = tokens[accounts[0].username]
for (const article of published) {
  let done = 0
  while (done < article.views) {
    const batch = Math.min(8, article.views - done)
    await Promise.all(Array.from({ length: batch }, () => api(`/api/articles/${article.id}`, { token: readerToken })))
    done += batch
    views += batch
  }
  log('7/9', `${article.key} 浏览量 → ${article.views}`)
}

log('8/9', '回填时间线(SQL:created_at 挪到设计好的时间点)')
const timeline = [
  ...published.map((a) => `UPDATE articles SET created_at='${shift(a.publishedAt, 0)}' WHERE id=${a.id};`),
  ...commentTimes.map(([id, at]) => `UPDATE comments SET created_at='${at}' WHERE id=${id};`),
]
if (plan.draft) {
  timeline.push(`UPDATE articles SET created_at='${shift(plan.draft.createdAt, 0)}' WHERE id=${plan.draft.id};`)
}
mysql(`SET NAMES utf8mb4;\n${timeline.join('\n')}\n`)
log('8/9', `${timeline.length} 行时间已回填`)

log('9/9', '封禁与核对')
for (const username of community.banned || []) {
  const admin = await api('/api/auth/login', { method: 'POST', body: { username: 'admin', password: 'admin123' } })
  await api(`/api/admin/users/${ids[username]}/status`, { method: 'PUT', token: admin.token, body: { status: 1 } })
  log('9/9', `${username} 已封禁`)
}

const after = mysql(`SELECT COUNT(*) FROM users WHERE username IN ('${PREEXISTING.join("','")}');`)
if (after !== before) throw new Error('你原有的账号数量变了 —— 这不该发生,请检查')
const summary = mysql(`
  SELECT CONCAT_WS(' | ',
    CONCAT('账号 ', (SELECT COUNT(*) FROM users)),
    CONCAT('文章 ', (SELECT COUNT(*) FROM articles), '(已发布 ', (SELECT COUNT(*) FROM articles WHERE status=1), ')'),
    CONCAT('评论 ', (SELECT COUNT(*) FROM comments)),
    CONCAT('点赞 ', (SELECT COUNT(*) FROM likes)),
    CONCAT('收藏 ', (SELECT COUNT(*) FROM favorites)),
    CONCAT('标签 ', (SELECT COUNT(*) FROM tags)),
    CONCAT('总浏览 ', (SELECT COALESCE(SUM(view_count),0) FROM articles)));
`)
console.log(`\n完成。${summary}`)
console.log(`你原有的 ${PREEXISTING.length} 个账号 ${before} → ${after}(未变)。`)
console.log(`图片缓存:${IMAGE_CACHE}(删掉它就会重新出图)`)

// 一致性自检:计数列必须等于真实行数,否则就是"会撒谎的数据"
const lying = mysql(`
  SELECT CONCAT_WS(' | ', a.id, a.title, CONCAT('like_count=', a.like_count), CONCAT('真实=', (SELECT COUNT(*) FROM likes l WHERE l.article_id=a.id)))
  FROM articles a
  WHERE a.like_count <> (SELECT COUNT(*) FROM likes l WHERE l.article_id = a.id)
     OR a.comment_count <> (SELECT COUNT(*) FROM comments c WHERE c.article_id = a.id)
  LIMIT 10;
`)
console.log(lying ? `\n⚠️ 计数与真实行数不符的文章(应为空):\n${lying}` : '\n自检:每篇文章的 like_count / comment_count 都等于真实行数。')
