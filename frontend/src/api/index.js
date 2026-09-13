import http from './http'

export const authApi = {
  register: (data) => http.post('/auth/register', data),
  login: (data) => http.post('/auth/login', data),
  me: () => http.get('/users/me'),
  updateProfile: (data) => http.put('/users/me', data),
}

export const userApi = {
  profile: (id) => http.get(`/users/${id}`),
  articles: (id, params) => http.get(`/users/${id}/articles`, { params }),
}

export const articleApi = {
  page: (params) => http.get('/articles', { params }),
  hot: () => http.get('/articles/hot'),
  detail: (id) => http.get(`/articles/${id}`),
  editDetail: (id) => http.get(`/articles/${id}/edit`),
  myArticles: (params) => http.get('/users/me/articles', { params }),
  myFavorites: (params) => http.get('/users/me/favorites', { params }),
  favorite: (id) => http.post(`/articles/${id}/favorite`),
  create: (data) => http.post('/articles', data),
  update: (id, data) => http.put(`/articles/${id}`, data),
  remove: (id) => http.delete(`/articles/${id}`),
}

export const commentApi = {
  list: (articleId) => http.get(`/articles/${articleId}/comments`),
  create: (articleId, data) => http.post(`/articles/${articleId}/comments`, data),
  remove: (id) => http.delete(`/comments/${id}`),
}

export const likeApi = {
  toggle: (articleId) => http.post(`/articles/${articleId}/like`),
}

export const tagApi = {
  list: () => http.get('/tags'),
}

/**
 * 相关推荐是普通 REST 调用,所以它在这里 ——
 * 此前它长在 `api/ai.js`(那个 module 存在的原因是 SSE 需要 fetch 直读流),
 * 只是为了蹭一句 `import http`。
 */
export const recommendApi = {
  byArticle: (id, size = 5) => http.get(`/articles/${id}/recommend`, { params: { size } }),
}

export const adminApi = {
  stats: () => http.get('/admin/stats'),
  users: (params) => http.get('/admin/users', { params }),
  updateUserStatus: (id, status) => http.put(`/admin/users/${id}/status`, { status }),
  updateUserRole: (id, role) => http.put(`/admin/users/${id}/role`, { role }),
  articles: (params) => http.get('/admin/articles', { params }),
  removeArticle: (id) => http.delete(`/admin/articles/${id}`),
  comments: (params) => http.get('/admin/comments', { params }),
  removeComment: (id) => http.delete(`/admin/comments/${id}`),
  tags: () => http.get('/admin/tags'),
  renameTag: (id, name) => http.put(`/admin/tags/${id}`, { name }),
  removeTag: (id) => http.delete(`/admin/tags/${id}`),
}

export const uploadApi = {
  image: (file, type = 'content') => {
    const form = new FormData()
    form.append('file', file)
    form.append('type', type)
    return http.post('/uploads', form)
  },
}
