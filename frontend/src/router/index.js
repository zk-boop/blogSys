import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '../stores/user'

const routes = [
  { path: '/', name: 'home', component: () => import('../views/Home.vue') },
  { path: '/login', name: 'login', component: () => import('../views/Login.vue') },
  { path: '/register', name: 'register', component: () => import('../views/Register.vue') },
  { path: '/article/:id', name: 'article', component: () => import('../views/ArticleDetail.vue') },
  { path: '/user/:id', name: 'user', component: () => import('../views/UserProfile.vue') },
  { path: '/write', name: 'write', component: () => import('../views/Write.vue'), meta: { requiresAuth: true } },
  { path: '/write/:id', name: 'writeEdit', component: () => import('../views/Write.vue'), meta: { requiresAuth: true } },
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
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.meta.requiresAdmin && !store.isAdmin) {
    return { name: 'home' }
  }
  if ((to.name === 'login' || to.name === 'register') && store.isLoggedIn) {
    return { name: 'home' }
  }
})

let loadErrorHandler = null

router.onError((error) => {
  if (error && error.type !== undefined) {
    return
  }
  loadErrorHandler?.(error)
})

export function onModuleLoadError(handler) {
  loadErrorHandler = handler
}

export default router
