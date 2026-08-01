import http from './http'

export const authApi = {
  register: (data) => http.post('/auth/register', data),
  login: (data) => http.post('/auth/login', data),
  me: () => http.get('/users/me'),
  updateProfile: (data) => http.put('/users/me', data),
}

export const articleApi = {
  page: (params) => http.get('/articles', { params }),
  detail: (id) => http.get(`/articles/${id}`),
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
