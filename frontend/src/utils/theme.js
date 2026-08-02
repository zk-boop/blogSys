const THEME_KEY = 'blogsys-theme'

export function getTheme() {
  return localStorage.getItem(THEME_KEY) || 'light'
}

export function applyTheme(theme) {
  document.documentElement.setAttribute('data-theme', theme)
  document.documentElement.classList.toggle('dark', theme === 'dark')
  localStorage.setItem(THEME_KEY, theme)
}

export function initTheme() {
  applyTheme(getTheme())
}
