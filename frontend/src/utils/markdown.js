import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'
import 'highlight.js/styles/github.css'

const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
  highlight(str, lang) {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return `<pre class="hljs"><code>${hljs.highlight(str, { language: lang, ignoreIllegals: true }).value}</code></pre>`
      } catch {
        /* fallthrough */
      }
    }
    return `<pre class="hljs"><code>${md.utils.escapeHtml(str)}</code></pre>`
  },
})

const slugify = (() => {
  let counter = 0
  const reset = () => {
    counter = 0
  }
  const next = () => `sec-${counter++}`
  return { next, reset }
})()

md.renderer.rules.heading_open = (tokens, idx, options, env, self) => {
  const token = tokens[idx]
  token.attrs = token.attrs || []
  token.attrs.push(['id', slugify.next()])
  return self.renderToken(tokens, idx, options)
}

export function renderMarkdown(source) {
  slugify.reset()
  return md.render(source || '')
}

export function extractToc(source) {
  const html = renderMarkdown(source || '')
  const toc = []
  const re = /<h([1-3]) id="(sec-\d+)">(.*?)<\/h\1>/g
  let match
  while ((match = re.exec(html)) !== null) {
    toc.push({
      level: Number(match[1]),
      id: match[2],
      text: match[3].replace(/<[^>]+>/g, '').trim(),
    })
  }
  return toc
}
