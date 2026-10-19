<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { request, message, copy, date, type Page } from './api'
interface Key {id:string;name:string;masked:string;createdAt:string;expiresAt:string|null;revokedAt:string|null;status:string}
const items=ref<Key[]>([]), page=ref(1), total=ref(0), name=ref(''), expires=ref(''), secret=ref(''), busy=ref(false), error=ref(''), notice=ref('')
async function load() { try { const result=await request<Page<Key>>(`/api/admin/api-keys?page=${page.value}`);items.value=result.items;total.value=result.total } catch(e) {error.value=message(e)} }
async function create() {
 if(busy.value)return
 busy.value=true;error.value='';secret.value='';notice.value=''
 try {const result=await request<{key:string}>('/api/admin/api-keys','POST',{name:name.value,expiresAt:expires.value?new Date(expires.value).toISOString():null});secret.value=result.key;name.value='';expires.value='';page.value=1;await load()}catch(e){error.value=message(e)}finally{busy.value=false}
}
async function revoke(key:Key) {
 if(!confirm('撤销会立即中断使用此 Key 的调用方，且无法恢复。确认撤销？'))return
 busy.value=true;error.value=''
 try{await request(`/api/admin/api-keys/${key.id}/revoke`,'POST',{});await load()}catch(e){error.value=message(e)}finally{busy.value=false}
}
async function clipboard(){try{await copy(secret.value);notice.value='已复制'}catch{error.value='复制失败，请手动复制'}}
onMounted(load)
</script>
<template><h1>API Key 管理</h1><p>Key 仅可读取全部已发布 Skill。轮换：创建新 Key → 更新调用方 → 撤销旧 Key。</p><p v-if="error" class="alert" role="alert">{{error}}</p><form class="card" @submit.prevent="create"><fieldset :disabled="busy"><label>名称<input v-model="name" maxlength="100" required></label><label>过期时间（可选）<input v-model="expires" type="datetime-local"></label><button :disabled="!!secret">创建 Key</button></fieldset></form><section v-if="secret" class="card"><strong>完整 Key 只显示一次，请立即保存</strong><p class="code">{{secret}}</p><button @click="clipboard">复制 Key</button><button @click="secret='';notice=''">关闭并清除</button><p>{{notice}}</p></section><section v-for="key in items" :key="key.id" class="card"><b>{{key.name}}</b><p>{{key.masked}} · {{key.status}}</p><p>创建 {{date(key.createdAt)}} · 过期 {{date(key.expiresAt)}} · 撤销 {{date(key.revokedAt)}}</p><button :disabled="busy || !!key.revokedAt" @click="revoke(key)">撤销</button></section><button :disabled="page===1||busy" @click="page--;load()">上一页</button> {{page}} <button :disabled="page*20>=total||busy" @click="page++;load()">下一页</button></template>
