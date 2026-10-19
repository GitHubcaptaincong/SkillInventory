<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { request, refreshCsrf, message } from './api'
const username=ref(''), expired=ref(false), error=ref(''), busy=ref(false),router=useRouter()
async function me(){try{username.value=(await request<{username:string}>('/api/auth/me')).username;expired.value=false}catch{username.value=''}}
function expire(){expired.value=true;username.value=''}
async function reconnect(){try{await refreshCsrf();await me()}catch(e){error.value=message(e)}}
async function logout(){if(busy.value)return;busy.value=true;try{await request('/api/auth/logout','POST',{});username.value='';expire();await refreshCsrf();await router.push('/login')}catch(e){error.value=message(e)}finally{busy.value=false}}
onMounted(()=>{me();window.addEventListener('session-expired',expire);window.addEventListener('signed-in',me)})
onBeforeUnmount(()=>{window.removeEventListener('session-expired',expire);window.removeEventListener('signed-in',me)})
</script>
<template>
<header><div class="shell header"><RouterLink to="/" class="brand">▧ Skill Inventory</RouterLink><RouterLink to="/api-keys">API Key 管理</RouterLink><span v-if="username">{{username}} <button :disabled="busy" @click="logout">退出</button></span></div></header>
<main class="shell"><p v-if="expired" class="alert">登录已失效。当前编辑内容仍保留，请先复制保存，再<RouterLink to="/login" target="_blank">在新标签页重新登录</RouterLink>，然后<button @click="reconnect">重新连接并保留输入</button>再重试。</p><p v-if="error" role="alert">{{error}}</p><RouterView /></main>
<footer class="shell">受控内网工具 · 管理员会话管理 · API Key 读取已发布快照</footer>
</template>
