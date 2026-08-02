import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import './style.css'
import App from './App.vue'
import router from './router'
import { initTheme } from './utils/theme'

initTheme()

createApp(App).use(createPinia()).use(router).use(ElementPlus, { locale: zhCn }).mount('#app')
