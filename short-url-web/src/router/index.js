import { createRouter, createWebHistory } from 'vue-router'
import CreateView from '../views/CreateView.vue'
import ListView from '../views/ListView.vue'

const routes = [
  { path: '/', redirect: '/create' },
  { path: '/create', name: 'Create', component: CreateView },
  { path: '/list', name: 'List', component: ListView }
]

export default createRouter({
  history: createWebHistory(),
  routes
})