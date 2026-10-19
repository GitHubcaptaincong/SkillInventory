# API Key 页面体验改版验证记录

日期：2026-10-19（Asia/Shanghai）。本次只修改前端页面、导航、请求处理和测试，未修改后端接口或认证模型。

## 实现

- `frontend/src/ApiKeys.vue`：标题与轮换提示、默认折叠的接入说明、分页表格、首次 skeleton、空列表、加载失败重试、保留表格的刷新反馈。
- 创建采用 470px 右侧抽屉，小屏全宽；名称校验、7/30/90 天、永不过期、自定义未来时间；默认仍为永不过期。
- 创建成功关闭抽屉，独立弹窗短暂展示完整 Key；复制失败选中文本供手动复制。关闭按钮、Esc、完成按钮需明确确认；遮罩不关闭；路由离开需确认；刷新/关闭标签页使用浏览器原生离开提示。
- `frontend/src/KeyOverlay.vue`：共享弹层、焦点限制和返回、背景 inert、固定操作区与内容滚动。弹层开启时页面导航暂不可操作。
- 撤销使用危险确认弹窗，只对服务端 ACTIVE 行提供操作；成功立即合并服务端返回的 metadata 并刷新当前页，失败反馈与刷新失败分开。
- `App.vue` 保留原登录、退出和新标签页重连流程，管理员信息位于导航右侧；新增 Skill 管理导航及当前页标记。
- `api.ts` 继续 same-origin Cookie 与原 CSRF 流程；请求增加 30 秒超时，401 清除 CSRF 内存并通知失效，403 继续刷新 CSRF 但不自动重放写请求。
- 完整 Key 仅位于当前页面组件与当前创建请求的短暂局部数据，不新增任何持久化、全局 store、查询缓存或日志。关闭/失效/退出/销毁时清除页面明文和文本框值；会话变化后晚到的创建响应不再显示。没有承诺物理擦除浏览器或剪贴板副本。
- 列表使用请求序号处理分页竞态；换页失败时说明当前保留的是哪一页旧数据。创建断连/超时/5xx 明确提示结果未确认，不自动重试或撤销不明记录。

## 实际 API 契约

后端当前目录已分包，已重新检查 `controller/ApiKeyController.java`、`dto/ApiKeyContracts.java`、`service/ApiKeys.java`、`config/SecurityConfig.java` 和 `security/RequestPolicy.java`。

- 列表 GET `/api/admin/api-keys?page=…`，默认 pageSize=20，返回 `items/page/pageSize/total`，服务端按 id DESC 排序。
- metadata：`id/name/masked/createdAt/expiresAt/revokedAt/status`。状态直接采用服务端 ACTIVE/EXPIRED/REVOKED；服务端撤销优先。
- POST 创建仍仅传 `name/expiresAt`，使用响应 `metadata/key`，完整 Key 只返回一次。
- POST `/{id}/revoke` 返回 metadata，后端保持原 COALESCE 幂等撤销。
- 管理接口继续管理员 Cookie 会话，CSRF headerName/token 从 `/api/auth/csrf` 获取。
- 客户端真实只读列表路径 `/api/v1/skills`，鉴权头 `X-API-Key`。curl 使用当前页面 origin 与 `$SKILL_API_KEY` 环境变量占位，不引用刚创建的明文。
- 401 `UNAUTHORIZED` 表示需重新登录；403 `FORBIDDEN` 是权限或 CSRF 失败，不直接作为会话过期。字段错误 `INVALID_ARGUMENT`；其他错误用安全的状态提示，不展示响应原文。
- 已检查请求策略的 `Cache-Control: no-store`、前端请求代码和后端异常日志；未发现记录创建响应或异常参数/明文的现有代码。

## 本次验证

- `npm test`：4 个测试文件，23 项通过。
- `npm run build`：vue-tsc 类型检查与 Vite 生产构建通过。
- `BROWSER_CHANNEL=chrome npx playwright test e2e/api-keys.e2e.ts`：3 项 Chrome 测试通过。测试从 dist 拦截提供生产构建文件，并拦截所有 API 请求返回合成数据，无需本机服务或后端账号。
- 浏览器覆盖 1440/1024/390px 无页面横向溢出、长名称、抽屉、焦点、键盘、遮罩不关闭、复制失败手选、二次关闭、会话清理与重连不恢复、创建成功但刷新失败、创建断连不重试、撤销确认及分页保持。
- 组件测试另外覆盖真实 401/403 响应处理、名称/有效期保留、晚到响应不恢复明文、列表请求竞态、路由离开、首次 skeleton、重复提交、空列表与错误状态区分。
- Maven 3.9.10 + JDK 21：`mvn -B -ntp -s tools/maven-settings.xml -f backend/pom.xml test`，13 项通过，无失败/错误/跳过；数据库为 Testcontainers 新建 MySQL 8.4.6。
- `git diff --check` 通过。
- 最初受限环境中 Maven 离线仓库来源不匹配、Docker 无权访问；随后在获准的本机测试环境重跑成功。Chrome 最初无法访问本机端口，因此最终测试改为拦截提供 dist 文件，不依赖网络连接。
- 1440/1024/390px 合成列表截图仅保留于被忽略的 `.test-output` 作内部视觉检查；未截图完整密钥或真实生产数据。

## 边界与未验证项

- 现有 Key 列表无完整数据集搜索/状态筛选、lastUsedAt、状态汇总；均未新增，也未用当前页伪造全局统计。仅展示后端 total。
- 本次未运行连接真实部署的 `lifecycle.e2e.ts`；已更新其中 Key 流程的页面选择器以适配新界面。Chrome 使用合成响应，不能当作 Docker/Nginx 同域链路的端到端验收。
- 不执行生产部署，不生成生产密钥，不修改现有密钥状态；后端测试只操作独立临时数据库。

交互参考仅用于命名、可选有效期、一次性展示与权限说明原则；不声明复现厂商控制台外观：
- https://platform.claude.com/docs/en/get-api-key
- https://help.openai.com/en/articles/8867743-assign-api-key-permissions
