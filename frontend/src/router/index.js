import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '../stores/user'
import { session } from '../session-instance'
import { createRouteErrorChannel } from './loadError'

const routes = [
  { path: '/', name: 'home', component: () => import('../views/Home.vue') },
  { path: '/login', name: 'login', component: () => import('../views/Login.vue') },
  { path: '/register', name: 'register', component: () => import('../views/Register.vue') },
  { path: '/article/:id', name: 'article', component: () => import('../views/ArticleDetail.vue') },
  { path: '/user/:id', name: 'user', component: () => import('../views/UserProfile.vue') },
  { path: '/write', name: 'write', component: () => import('../views/Write.vue'), meta: { requiresAuth: true } },
  { path: '/write/:id', name: 'writeEdit', component: () => import('../views/Write.vue'), meta: { requiresAuth: true } },
  { path: '/ai', name: 'aiChat', component: () => import('../views/AiChat.vue'), meta: { requiresAuth: true } },
  { path: '/me', name: 'me', component: () => import('../views/Profile.vue'), meta: { requiresAuth: true } },
  {
    path: '/admin',
    name: 'admin',
    component: () => import('../views/admin/AdminLayout.vue'),
    meta: { requiresAuth: true, requiresAdmin: true },
    children: [
      { path: '', name: 'adminDashboard', component: () => import('../views/admin/Dashboard.vue') },
      { path: 'users', name: 'adminUsers', component: () => import('../views/admin/Users.vue') },
      { path: 'articles', name: 'adminArticles', component: () => import('../views/admin/Articles.vue') },
      { path: 'comments', name: 'adminComments', component: () => import('../views/admin/Comments.vue') },
      { path: 'tags', name: 'adminTags', component: () => import('../views/admin/Tags.vue') },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  const store = useUserStore()
  if (to.meta.requiresAuth && !store.isLoggedIn) {
    // 跳转目标归 session。守卫此前是唯一带了 redirect 的地方,现在这条规则只有一个主人。
    return session.loginTarget(to.fullPath)
  }
  if (to.meta.requiresAdmin && !store.isAdmin) {
    return { name: 'home' }
  }
  if ((to.name === 'login' || to.name === 'register') && store.isLoggedIn) {
    return { name: 'home' }
  }
})

const routeErrors = createRouteErrorChannel()

router.onError((error) => routeErrors.notify(error))

/**
 * 订阅「这一次导航没能把页面拿出来」。**返回退订函数** —— 订阅者可能不止一个,
 * 而退订是它自己的责任。此前这里是一个单槽变量,第二个订阅者会静默顶掉第一个。
 */
export function onModuleLoadError(handler) {
  return routeErrors.subscribe(handler)
}

export default router
