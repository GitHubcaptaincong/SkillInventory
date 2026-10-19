# 实际验证记录

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
