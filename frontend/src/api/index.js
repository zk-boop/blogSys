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

export const uploadApi = {
  image: (file, type = 'content') => {
    const form = new FormData()
    form.append('file', file)
    form.append('type', type)
    return http.post('/uploads', form)
  },
}
