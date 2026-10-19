<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { request, refreshCsrf, message } from './api'
const username=ref(''), password=ref(''), busy=ref(false), error=ref('')
const route=useRoute(), router=useRouter()

async function login() {
  if(busy.value) return
  busy.value=true; error.value=''
  try {
    await refreshCsrf()
    await request('/api/auth/login','POST',{username:username.value,password:password.value})
    password.value=''; await refreshCsrf()
    const target=typeof route.query.redirect==='string'?route.query.redirect:'/'
    await router.replace(/^\/(?:skills(?:\/|$)|api-keys(?:\?|$)|$)/.test(target) && !target.includes('\\') ? target : '/')
    window.dispatchEvent(new Event('signed-in'))
  } catch(e) { error.value=message(e) } finally { password.value=''; busy.value=false }
}
</script>
<template><section class="card"><h1>管理员登录</h1><p v-if="error" class="alert" role="alert">{{ error }}</p><form @submit.prevent="login"><fieldset :disabled="busy"><label>用户名<input v-model="username" autocomplete="username" maxlength="64" required></label><label>密码<input v-model="password" type="password" autocomplete="current-password" required></label><button class="primary">{{ busy?'登录中…':'登录' }}</button></fieldset></form></section></template>

