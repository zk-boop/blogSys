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

const app = createApp(App).use(createPinia()).use(router).use(ElementPlus, { locale: zhCn })

/**
 * 组件在渲染/生命周期里抛出的错误。
 *
 * <p>此前这里从不设置,于是它没有归属:Vue 的默认处理会把它打到 console,而日志里
 * **认不出是哪个组件、哪个阶段**。这里补上归属,并且给它一个以后可以接上报器的位置。
 *
 * <p>它**刻意不**去弹「页面加载失败」——那是懒加载 chunk 拉不下来的问题
 * (见 `router/loadError.js`),与组件内部抛错是两回事,混在一起只会让两种故障
 * 长得一样。
 */
app.config.errorHandler = (error, instance, info) => {
  const name = instance?.$?.type?.__name ?? instance?.$?.type?.name ?? '未知组件'
  console.error(`[blogSys] ${name} 在 ${info} 阶段抛错`, error)
}

app.mount('#app')
