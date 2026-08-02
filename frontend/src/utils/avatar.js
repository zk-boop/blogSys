const COLORS = ['#409eff', '#67c23a', '#e6a23c', '#f56c6c', '#9b59b6', '#1abc9c', '#f39c12', '#3498db']

function hashOf(text) {
  let hash = 0
  for (const ch of text) {
    hash = (hash * 31 + ch.charCodeAt(0)) >>> 0
  }
  return hash
}

export function initialAvatar(name) {
  const text = name || '?'
  const color = COLORS[hashOf(text) % COLORS.length]
  const letter = text.charAt(0).toUpperCase()
  const svg =
    `<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100">` +
    `<rect width="100" height="100" fill="${color}"/>` +
    `<text x="50" y="68" font-size="48" text-anchor="middle" fill="#fff" font-family="Arial,sans-serif">${letter}</text>` +
    `</svg>`
  return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg)
}

export function avatarSrc(url, name) {
  if (url && !url.startsWith('https://api.dicebear.com')) {
    return url
  }
  return initialAvatar(name)
}
