// @vitest-environment jsdom
import { afterEach, describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import Login from './Login.vue'
import ApiKeys from './ApiKeys.vue'
import App from './App.vue'
const json=(v:unknown,status=200)=>new Response(JSON.stringify(v),{status})
async function router(path='/login') {
 const r=createRouter({history:createMemoryHistory(),routes:[{path:'/login',component:Login},{path:'/',component:{template:'<p>管理首页</p>'}},{path:'/api-keys',component:ApiKeys}]})
 await r.push(path);await r.isReady();return r
}
afterEach(()=>{vi.unstubAllGlobals();vi.restoreAllMocks()})
describe('认证与 Key 页面',()=>{
 it('登录获取 CSRF，安全回跳并清除密码',async()=>{
  const f=vi.fn(async(path:string)=>path.endsWith('/csrf')?json({token:'csrf',headerName:'X-CSRF-TOKEN'}):json({username:'admin'}))
  vi.stubGlobal('fetch',f)
  const r=await router('/login?redirect=https://evil.invalid');const w=mount(Login,{global:{plugins:[r]}})
  await w.findAll('input')[0]!.setValue('admin');await w.findAll('input')[1]!.setValue('test-password')
  await w.find('form').trigger('submit');await flushPromises()
  expect(r.currentRoute.value.path).toBe('/')
  expect(f.mock.calls.some(c=>c[0]==='/api/auth/login')).toBe(true);w.unmount()
 })
 it('登录失败展示统一提示且允许重试',async()=>{
  vi.stubGlobal('fetch',vi.fn(async(path:string)=>path.endsWith('/csrf')?json({token:'csrf',headerName:'X-CSRF-TOKEN'}):json({message:'用户名或密码错误',requestId:'rid'},401)))
  const r=await router();const w=mount(Login,{global:{plugins:[r]}})
  await w.findAll('input')[0]!.setValue('admin');await w.findAll('input')[1]!.setValue('bad')
  await w.find('form').trigger('submit');await flushPromises()
  expect(w.get('[role="alert"]').text()).toContain('用户名或密码错误')
  expect((w.findAll('input')[1]!.element as HTMLInputElement).value).toBe('')
  expect(w.find('fieldset').attributes('disabled')).toBeUndefined();w.unmount()
 })
 it('Key 一次展示、关闭清除以及确认撤销',async()=>{
  const key={id:'1',name:'service',masked:'sk_public_…1234',createdAt:'2026-10-19T00:00:00Z',expiresAt:null,revokedAt:null,status:'ACTIVE'}
  const f=vi.fn(async(path:string,init?:RequestInit)=>{
   if(path.endsWith('/csrf'))return json({token:'csrf',headerName:'X-CSRF-TOKEN'})
   if(init?.method==='POST'&&!path.endsWith('/revoke'))return json({key:'only-once-secret',metadata:key},201)
   if(path.endsWith('/revoke'))return json({...key,revokedAt:'2026-10-19T01:00:00Z'})
   return json({items:[key],total:1,page:1,pageSize:20})
  });vi.stubGlobal('fetch',f);vi.stubGlobal('confirm',vi.fn().mockReturnValue(true))
  const r=await router('/api-keys');const w=mount(ApiKeys,{global:{plugins:[r]}});await flushPromises()
  await w.findAll('button').find(b=>b.text()==='创建 API Key')!.trigger('click')
  await w.find('input').setValue('service');await w.find('form').trigger('submit');await flushPromises()
  expect(w.text()).toContain('完整密钥仅显示一次');expect(w.get('textarea').element.value).toBe('only-once-secret')
  await w.findAll('button').find(b=>b.text()==='完成并关闭')!.trigger('click')
  await w.findAll('button').find(b=>b.text()==='确认关闭并清除')!.trigger('click')
  expect(w.text()).not.toContain('only-once-secret')
  await w.findAll('button').find(b=>b.text()==='撤销')!.trigger('click');await flushPromises()
  expect(w.get('[role="dialog"]').text()).toContain('撤销此密钥？')
  await w.findAll('button').find(b=>b.text()==='确认撤销')!.trigger('click');await flushPromises()
  expect(f.mock.calls.some(c=>c[0].endsWith('/1/revoke'))).toBe(true);w.unmount()
 })
 it('会话失效显示重新登录提示并保留当前页面',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>json({username:'admin'})))
  const r=await router('/');const w=mount(App,{global:{plugins:[r]}});await flushPromises()
  window.dispatchEvent(new Event('session-expired'));await flushPromises()
  expect(w.text()).toContain('当前编辑内容仍保留');expect(w.text()).toContain('管理首页');expect(r.currentRoute.value.path).toBe('/');w.unmount()
 })
})
