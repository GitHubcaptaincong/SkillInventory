# 实际验证记录：管理员与 API Key 首版

日期：2026-10-19（Asia/Shanghai）。仅本地隔离测试，未部署生产，未清空原有 Skill 数据或删除任何 Docker 卷。

## 后端分包重构验证（2026-10-19）

后端从单一 internal.skills 包拆分为 controller、service、repository、domain、dto、config、security、exception、support，启动类保留在根包。同文件中的控制器分开维护，测试同步归包；Skill 字段通过只读 getter 跨包访问，表结构、JSON 契约和接口路径不变。按要求删除 skill-platform-codex-prompt.md，并更新相关文档链接。

实际从 backend 执行 `mvn -B -ntp -Dmaven.repo.local=.tools/m2 -s ../tools/maven-settings.xml clean test`（本机使用已安装的 JDK 21.0.5 / Maven 3.9.10）。先 clean 清除旧包 class，避免残留类影响 Spring 扫描。结果：**13 项通过，0 失败、0 错误、0 跳过**，真实 MySQL 8.4.6 Testcontainers，完成于 09:41:06 +08:00。日志保留于 `.test-output/package-refactor-tests.log`。

另执行 `mvn -B -ntp -Dmaven.repo.local=.tools/m2 -s ../tools/maven-settings.xml -DskipTests package`，最终可执行 jar 打包通过；该命令复用前述已通过测试，不声称再次运行测试。日志在 `.test-output/package-refactor-build.log`。

本轮未改前端、数据库迁移、Docker 或 API 规则，未重跑 Chrome/Docker 冒烟和生产部署；下方此前验证记录的执行范围保持原样。

## 当前认证版本

- 后端：JDK 21.0.5、Maven 3.9.10、Testcontainers 1.21.4，真实 MySQL 8.4.6。**13 项通过，0 失败、0 错误、0 跳过**。保留全部原业务测试。
- 前端：`npm test`，**11 项通过**；`npm run build`（vue-tsc + Vite）通过。
- Chrome + 实际 Nginx/后端/MySQL：`npx playwright test`，**2 项通过**，验证登录、原 Skill 生命周期、快照隔离、安全 Markdown 预览、Key 一次展示/关闭清除/撤销、退出后管理接口 401。
- 最终 Compose 构建、启动、健康检查通过，仅前端发布端口 `127.0.0.1:18089`。独立项目 `skillinventory-auth-smoke`，独立卷 `skillinventory-auth-smoke_mysql_data`，随机测试配置 `.test-output/auth-smoke.env` 被 Git 忽略。未操作已有 `skillinventory-smoke-20261019` 项目。
- 经 Nginx 冒烟：登录 → 创建并发布 Skill → 创建 Key → 无 Cookie 外部读取三个端点 → ETag/304 → 保存草稿不改变快照 → down/up → 原会话、原管理员密码、原 Key 与 Skill 数据继续有效 → 下架/再发布 → 幂等撤销 → 携带旧 ETag 的外部读取 401 → 退出后会话失效。全部通过，Key 始终只保留在脚本内存，不写状态文件。
- 后端容器实测 uid=999(app)，Nginx nginx -t 通过；未新增宿主数据库/后端端口。
- 额外检查容器日志和数据库 dump（仅捕获到内存，不输出或保存 dump）：没有本次完整 API Key 或明文管理员密码；服务日志中无本次数据库密码。该检查覆盖测试运行的日志，不代表未来第三方代理/监控配置审计。

## 覆盖证据

| 要求 | 当前证据 |
| --- | --- |
| 未登录直接调用管理读/写均 401，Key 不替代管理员 | MockMvc + 真 MySQL；Nginx 冒烟 |
| 正确/错误登录、会话 ID 更新、HttpOnly/Path/SameSite、退出、持久会话过期 | 真实 Session JDBC Cookie 请求链测试；不是 mock 登录代替该检查 |
| BCrypt、环境变量不覆盖已有管理员 | MySQL 检查哈希匹配；重新执行初始化不变；down/up 后原密码登录 |
| 并发初始化无重复 | 独立升级 MySQL 中并发两个初始化器，最终仅一个管理员 |
| 缺失/错误 CSRF 拒绝，合法请求成功 | 真实登录会话测试；Vue Chrome 请求链 |
| Origin / Sec-Fetch-Site、2 MiB 限制、415 | 原测试继续运行，来源检查保留 |
| 原业务生命周期与乐观锁、并发写、分页、YAML | 原 MySQL 测试与 YAML 单测全保留 |
| Key 缺失/错误/过期/撤销、query 不替代 Header、会话不替代 Key | MySQL 测试；三个客户端路径均检查 |
| 有效 Key 不读草稿，发布后 JSON/列表/原文，304，下架 404 | MySQL 测试、Nginx 冒烟和 Chrome |
| 撤销立即生效、重复幂等、旧 ETag 不绕过认证 | MySQL 测试和 Nginx 冒烟 |
| Key 列表无明文、数据库仅摘要、日志无凭证 | MySQL 断言、Nginx 冒烟内存检查 |
| V1 升级 V2 数据保留 | MigrationTest 先迁移至 V1、插入既有 ID/草稿/快照/版本，再执行 V2，前后内容一致 |
| Spring Session 表结构匹配 | 从实际 spring-session-jdbc 3.5.7 jar 读取 schema-mysql.sql 核对，与 V2 会话部分一致 |
| 登录失败节流上界 | AuthThrottleTest 20 次允许认证、21 次 429，未用客户端标识构造无界 map |
| 会话失效反馈和表单保护 | Vue 组件测试检查提示和当前页面保留；现有编辑冲突保留输入测试；失效后不自动跳转或重放写请求 |

## 可复现命令

```powershell
$env:JAVA_HOME='C:\Users\maijiancong\.jdks\corretto-21.0.5'
# 在 backend 下，其他机器可直接用 mvn
& 'C:\Users\maijiancong\.m2\wrapper\dists\apache-maven-3.9.10-bin\53h08a94dg6djh6umvruv7q564\apache-maven-3.9.10\bin\mvn.cmd' -B -ntp '-Dmaven.repo.local=.tools/m2' -s ../tools/maven-settings.xml test
# 在 frontend 下
npm test
npm run build
$env:BROWSER_CHANNEL='chrome'
$env:E2E_BASE_URL='http://127.0.0.1:18089'
npx playwright test
# 仓库根目录；生成器仅首次运行，拒绝覆盖配置
python tools/new-test-env.py
docker compose --env-file .test-output/auth-smoke.env -p skillinventory-auth-smoke config --quiet
docker compose --env-file .test-output/auth-smoke.env -p skillinventory-auth-smoke up --build -d --wait --wait-timeout 240
python tools/smoke.py --restart
```

本次日志：`.test-output/auth-tests-final.log`、`auth-compose-final.log`、`auth-smoke-final.log`、`auth-smoke-credentials.log`、`auth-browser-final.log`；后端报告在 `backend/target/surefire-reports`。这些输出和测试配置不提交 Git。密码哈希维护工具 `tools/PasswordHash.java` 使用实际 Spring Security crypto jar 编译通过，未执行真实管理员密码重置。

## 失败与修正

1. 沙箱内无法访问 Docker 管道、Maven 下载 DNS 受限；通过授权提权后可运行，不以 H2 替代。
2. 手工重建 MockMvc 初次遗漏 RequestPolicy 和 SessionRepositoryFilter，导致来源检查/请求体限制测试失败、会话 Cookie 缺失。已将真实两层过滤器加入测试装配，回归通过。
3. 原 Vue fetch mock 未包含新 CSRF 请求，原 2 条编辑测试初次失败；已为测试取得 CSRF，保留原断言，回归通过。
4. Chrome 测试首次与容器 down/up 重叠造成连接拒绝；之后又发现旧 URL 正则误匹配登录页 query 中的 `/`，过早发起 Key 创建请求。改为等待登录后的管理页面元素，再串行执行部署、重启冒烟和浏览器测试，两条通过。
5. Spring Security provider 导入与 CSRF 配置 API 的编译问题均已修正；最终以真实编译和回归结果为准。

## 未验证项与限制

- 交付前已停止本次独立测试服务，保留命名卷和测试配置供复验。
- 未执行生产部署、真实用户验收、HTTPS/TLS 代理环境的 Secure Cookie 端到端验证、多实例长时间运行、负载或渗透测试。
- 密码重置提供受控维护步骤和工具；未在任何真实管理员数据库执行重置。数据库备份/恢复方案本轮未演练。
- 会话失效后的输入保留已有组件检查；跨多个真实标签页的完整人工恢复流程、完整可访问性和不同浏览器兼容性尚未人工验收。
- 登录节流按进程全局窗口，既不永久锁号也不引入 Redis；不是分布式公网防爆破方案。
- Spring Security 6.5 的 AntPathRequestMatcher 有弃用警告，当前固定版本下可编译运行；升级框架时需迁移匹配器。Mockito/Node 测试工具警告不影响本次结果。

---

下方是认证改造前已存在的历史记录，**不作为当前认证版本的测试证据**，其旧命令和旧测试数量仅供追溯。当前使用上方命令与结果。

# 认证改造前的历史验证记录

执行日期：2026-10-19，Asia/Shanghai。范围是本地隔离测试环境，未执行生产部署，也不代表用户或客户验收。

## 环境和执行命令

- Windows PowerShell，宿主 Node 22.14.0 / npm 10.9.2。
- 使用本机已有 Corretto JDK 21.0.5 和 Maven 3.9.10；没有修改系统 Java 默认值（仍为 Java 8）。
- Docker Engine 29.6.1 / Docker Desktop Linux containers。
- MySQL 集成测试为 Testcontainers 1.21.4 创建的全新 MySQL 8.4.6，使用 `skill_test` 数据库。没有使用 H2，没有连接用户已有数据库。
- Compose 项目名 `skillinventory-smoke-20261019`，独立命名卷 `skillinventory-smoke-20261019_mysql_data`，随机测试密码存放于被 Git 忽略的 `.test-output/smoke.env`，未输出密码。

宿主后端实际执行（路径是本机现有 Maven 安装，其他机器可换成 `mvn`）：

```powershell
$env:JAVA_HOME='C:\Users\maijiancong\.jdks\corretto-21.0.5'
cd backend
& 'C:\Users\maijiancong\.m2\wrapper\dists\apache-maven-3.9.10-bin\53h08a94dg6djh6umvruv7q564\apache-maven-3.9.10\bin\mvn.cmd' -B -ntp '-Dmaven.repo.local=.tools/m2' -s ../tools/maven-settings.xml test
```

最终结果：**9 项测试，0 失败，0 错误，0 跳过**，最终一次完成于 08:41:07 +08:00。日志保留在 `.test-output/backend-tests.log`，Surefire 报告在 `backend/target/surefire-reports/`，均不提交 Git。

## 覆盖与结果

| 验证 | 实际结果 |
| --- | --- |
| 草稿不可读取；发布后元数据与原文一致 | MySQL 集成测试通过 |
| description/正文保存后快照不泄露；再次发布才更新 | MySQL 集成测试、公开入口冒烟及 Chrome 测试通过 |
| 下架清除快照，旧 ETag 不会变为 304 | MySQL 集成测试和公开入口冒烟通过 |
| 已发布不可删除；下架后删除；删除后同名新 id | MySQL 集成测试通过 |
| 保存、发布、下架、删除旧版本拒绝 | MySQL 集成测试通过 |
| 两个真实并发写请求 | MySQL 下恰好 200 与 409，最终版本只增加一次 |
| 同内容保存、重复发布/下架无变化；重新发布版本递增 | MySQL 集成测试通过 |
| YAML 冒号、引号、多行中文及原始缩进/换行 | YAML 解析往返与字节上限单元测试通过 |
| 非法 name、重名、超长描述、UTF-8 正文上限、请求上限 | MySQL 集成测试通过；Nginx 入口 413 冒烟通过 |
| 分页、状态/文字筛选、LIKE 字面量、SQL 注入式关键词 | MySQL 集成测试通过 |
| 三种客户端响应 ETag/304，弱标签、多标签、星号 | MySQL 集成测试通过 |
| 未允许 Origin、cross-site、错误媒体类型、错误 DTO | MySQL 集成测试通过；公开入口来源拒绝通过 |
| 原始 HTML、事件、javascript URL、图片 URL | JSDOM Markdown 测试通过；Chrome 检查预览无 img 通过 |
| 409 保留表单输入，未保存时阻止发布，新建后跳转 | JSDOM 组件测试通过 |
| 生产类型检查与 Vite 打包 | 宿主和 Docker 构建均通过 |

前端执行：`npm ci --no-audit --no-fund`、`npm test`、`npm run build`。最终 **7 项测试通过**。`npm audit --omit=dev --audit-level=moderate` 结果为 **0 vulnerabilities**；此结果仅对应执行时 npm 数据库中的运行依赖，不代表完整系统安全审计。npm 安装提示测试工具的传递 glob 包已弃用；运行包不包含该测试工具。

## Docker 和浏览器

实际执行：

```sh
python tools/new-test-env.py
docker compose --env-file .test-output/smoke.env -p skillinventory-smoke-20261019 config --quiet
docker compose --env-file .test-output/smoke.env -p skillinventory-smoke-20261019 up --build -d --wait --wait-timeout 240
docker compose --env-file .test-output/smoke.env -p skillinventory-smoke-20261019 ps
python tools/smoke.py --phase before-restart
```

结果：三个服务均 **healthy**；只有前端发布 `127.0.0.1:18088`，backend 和 mysql 无宿主端口映射。`backend id` 实测为 `uid=999(app) gid=999(app)`。`frontend nginx -t` 成功；首页实际收到 CSP 与 nosniff 响应头。后端健康检查 Java 类、前端 wget、MySQL SELECT 1 健康检查都在实际镜像中运行成功。

公开入口脚本通过：创建 → 发布 → 修改草稿 → 客户端仍读旧内容 → 再发布 → 下架 → 删除；同时检查 SPA history 回退、API 404 无 index.html 回退、ETag、恶意来源及 2 MiB 限制。

真实浏览器命令：在 frontend 中设置 `$env:BROWSER_CHANNEL='chrome'` 后执行 `npx playwright test`。结果 **1 项端到端测试通过**，使用全新临时 Chrome profile，对实际 Docker 环境操作；未使用 API mock。测试没有 pageerror，检查了编辑/预览、未保存发布提醒、快照隔离、下架、列表筛选及删除。已查看生成的编辑页截图，布局正常。截图位于 `.test-output/editor.png` 和 `.test-output/list.png`。

持久化实际执行：

```sh
docker compose --env-file .test-output/smoke.env -p skillinventory-smoke-20261019 down
docker compose --env-file .test-output/smoke.env -p skillinventory-smoke-20261019 up -d --wait --wait-timeout 240
python tools/smoke.py --phase after-restart
```

结果：容器和网络重建后，保留记录的 id、草稿、lockVersion、发布快照及原文完全一致；随后脚本下架并删除该测试记录。保留了命名卷，**未执行 `down -v`**。

测试服务交付时继续运行，可打开 <http://127.0.0.1:18088> 检查空目录。停止它们：

```sh
docker compose --env-file .test-output/smoke.env -p skillinventory-smoke-20261019 stop
```

## 发现并修复

1. 初始沙箱内 Docker 套接字和 npm DNS 不可用；经工具授权访问后解除，未将受阻检查标成通过。
2. 初次 npm 依赖解析触发 npm 10 的 peer 解析异常，固定兼容的 Vitest 版本后完成安装和锁定。
3. 前端类型检查发现模板直接引用 confirm，已移入脚本函数并构建通过。
4. 初次集成测试 URL 错用 `&` 起始参数，修复 `?`/`&` 选择后重跑。
5. 框架条件缓存对裸星号 GET 未返回 304；保留框架标签处理，增加框架 HttpHeaders 解析的星号分支后全部通过。
6. 统一时间到 MySQL 微秒精度；验证相同内容保存的完整资源不变。
7. Boot 默认 Flyway 对较新 MySQL 提示兼容警告，固定 Flyway 11.16.0 后，实际迁移和测试通过，最终日志不再出现该警告。
8. 检查 Nginx header 继承并修正，实际验证首页安全头；使用 Docker DNS 动态解析上游，便于后端重建。

## 未验证项与已知限制

- 没有执行生产部署、公司内网设备验收、客户验收或长时间压测。
- 浏览器覆盖本机 Chrome 桌面自动化；未逐项人工验收所有浏览器、移动端、剪贴板权限、浏览器关闭提醒和双标签页交互。冲突输入保留已有 JSDOM 测试及后端并发测试，仍建议真实双标签页人工演练。
- README 的 MySQL 备份恢复流程、账号密码轮换、完全断网的 save/load 转移已提供命令，本次未实际执行恢复或断网演练。
- 尚未对镜像执行全面漏洞扫描；未进行渗透测试。内网无鉴权边界是确认范围内的明确限制。
- lockVersion/publishedVersion 使用 JSON 整数；超出 JavaScript 安全整数范围的极端长期记录不在首版适用范围。无历史版本、无已下载副本撤销。

建议后续人工验证：复制 name/发布地址；在两个标签页同时编辑并确认 409 后输入仍在；未保存时返回和关闭页面；按 README 在隔离项目完成备份恢复及离线镜像转移。不要把这些建议写成已经通过。
