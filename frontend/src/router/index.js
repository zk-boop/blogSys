import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '../stores/user'

const routes = [
  { path: '/', name: 'home', component: () => import('../views/Home.vue') },
  { path: '/login', name: 'login', component: () => import('../views/Login.vue') },
  { path: '/register', name: 'register', component: () => import('../views/Register.vue') },
  { path: '/article/:id', name: 'article', component: () => import('../views/ArticleDetail.vue') },
  { path: '/write', name: 'write', component: () => import('../views/Write.vue'), meta: { requiresAuth: true } },
  { path: '/write/:id', name: 'writeEdit', component: () => import('../views/Write.vue'), meta: { requiresAuth: true } },
  { path: '/me', name: 'me', component: () => import('../views/Profile.vue'), meta: { requiresAuth: true } },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  const store = useUserStore()
  if (to.meta.requiresAuth && !store.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if ((to.name === 'login' || to.name === 'register') && store.isLoggedIn) {
    return { name: 'home' }
  }
})

export default router
