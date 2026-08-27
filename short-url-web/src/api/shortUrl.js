import request from './request'

export function createShortUrl(data) {
  return request.post('/short-url/create', data)
}

export function listShortUrl(current = 1, size = 10) {
  return request.get('/short-url/list', { params: { current, size } })
}