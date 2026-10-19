import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import List from './List.vue'
import Editor from './Editor.vue'
import './style.css'
import Login from './Login.vue'
import ApiKeys from './ApiKeys.vue'
import { request } from './api'
const router = createRouter({ history: createWebHistory(), routes: [
  { path: '/login', component: Login }, { path: '/api-keys', component: ApiKeys }, { path: '/', component: List }, { path: '/skills/new', component: Editor },
  { path: '/skills/:id(\\d+)', component: Editor }, { path: '/:pathMatch(.*)*', redirect: '/' },
] })
router.beforeEach(async to => {
  if (to.path === '/login') return true
  try { await request('/api/auth/me'); return true } catch { return { path: '/login', query: { redirect: to.fullPath } } }
})
createApp(App).use(router).mount('#app')
