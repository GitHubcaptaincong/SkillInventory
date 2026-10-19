import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import List from './List.vue'
import Editor from './Editor.vue'
import './style.css'
const router = createRouter({ history: createWebHistory(), routes: [
  { path: '/', component: List }, { path: '/skills/new', component: Editor },
  { path: '/skills/:id(\\d+)', component: Editor }, { path: '/:pathMatch(.*)*', redirect: '/' },
] })
createApp(App).use(router).mount('#app')
