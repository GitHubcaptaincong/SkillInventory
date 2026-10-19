# 内部 Skill 管理与分发平台

管理每个 Skill 的一个 `SKILL.md`：创建草稿、编辑、手动发布、下架和删除。Vue 3 管理页面与 Spring Boot API 同域部署，MySQL 的 Skill 表保存草稿和当前发布快照。客户端始终读取已发布快照，编辑保存不会改变正在分发的内容。

确认需求保留在 [skill-platform-codex-prompt.md](skill-platform-codex-prompt.md)，优先于旧方案。已增加单管理员登录及只读 API Key，完整说明见 [认证与运维](docs/AUTH.md)。不执行 Skill，无多文件、版本历史、Agent 或外部模型依赖。

## 启动

需要 Docker Engine / Docker Desktop 和 Compose v2。PowerShell 在仓库根目录执行：

```powershell
Copy-Item .env.example .env
# 编辑 .env：设置数据库独立随机密码、ADMIN_USERNAME 和 ADMIN_INITIAL_PASSWORD（至少 12 字符、至多 72 UTF-8 字节）；检查端口和 ALLOWED_ORIGINS
docker compose config --quiet
docker compose up --build -d --wait --wait-timeout 240
docker compose ps
docker compose logs --tail 100 backend
```

打开 <http://localhost:8088>。默认仅发布 `127.0.0.1:8088` 前端端口，后端和 MySQL 仅容器网络可达。让同事访问时，按公司网络策略设置 `FRONTEND_BIND` 为内网绑定地址，并将实际浏览器 origin（协议、主机、端口，无路径或尾斜杠）添加到 `ALLOWED_ORIGINS`。多个 origin 用逗号分隔；不能使用 `*`。改端口也要同步 origin。未配置密码或 origin 时明确失败；`docker compose config` 的完整输出可能含密码，检查时优先用 `--quiet`。

在页面新建 Skill，分别填写 name、description 和正文。可手工使用 [示例 Skill](docs/examples/java-code-review.md)，将其 frontmatter 元数据填入表单，只把 `# 使用场景` 起的正文放入 Markdown 编辑区。启动不自动导入或发布任何示例。

## 实现与边界

- 固定 Spring Boot **3.5.16**、Java **21**、Spring Data JPA、Flyway **11.16.0**、MySQL **8.4.6**。固定前端包版本和 `package-lock.json`，Docker 构建使用 Node **22.20.0**、Nginx **1.28.0**。完整镜像标签见 Dockerfile / compose.yaml。
- `skill` 保存业务内容；V2 新增管理员、API Key 和 Spring Session JDBC 表，Flyway 维护迁移元数据表。`published_snapshot` 为 MySQL JSON，内容只由服务端构造。数据库 utf8mb4 二进制比较，时间统一 UTC DATETIME(6)，API 返回带时区时间。
- 状态从草稿与快照计算；不保存状态列。发布版本复用发布后的单调 lockVersion。所有变更通过 `WHERE id=? AND lock_version=?` 条件写入，版本冲突为 409。无实质变更也用数据库条件更新验证版本，但不增加版本或发布时间。
- YAML 通过 SnakeYAML 序列化 name、description，正文原样追加。Markdown 原始 HTML 禁用，渲染后经 DOMPurify 清洗。部署 CSP 进一步限制远程资源，正文不执行、不抓取 URL。
- 请求上限 Nginx 和后端均 2 MiB；正文另限 256 KiB UTF-8。仅写接口接受 JSON。精确 Origin 白名单及跨站写入拒绝在服务端执行；无 Origin 的直接内网 HTTP 调用允许。
- 管理接口需管理员会话，写操作需 CSRF；外部客户端需 X-API-Key。继续部署在受控网络，跨不可信网络必须使用 HTTPS，启用 Secure Cookie。
- 首版应用数据库账号由官方 MySQL 初始化脚本创建，仅对业务数据库授权。其权限同时包含 Flyway 建表/变更权限，不使用 root 运行应用；无需全局权限。此首版未拆分迁移与运行账号。
- 下架只停止后续服务端分发，无法撤销已下载副本；客户端负责缓存与数据清理，每次取用应按 ETag 重验证。
- BIGINT id 是字符串；lockVersion/publishedVersion 是 JSON 整数。极端超出 JS 安全整数范围的版本不在首版运行范围内。

官方兼容说明（实施前已查阅）：[Spring Boot 3.5 系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)、[Vite / Node 要求](https://vite.dev/guide/)、[MySQL LTS 模型](https://dev.mysql.com/doc/refman/8.4/en/mysql-releases.html)。Java 21 在 Boot 兼容范围内，Node 22.20 满足 Vite 22.12+ 下限。镜像使用具体标签，后续安全更新应经测试后显式升级标签和依赖锁文件。

## API 与客户端接入

完整字段、分页、状态码、错误、并发及调用例子见 [API 契约](docs/API.md)。客户端示例：

```sh
curl -i -H 'X-API-Key: <从安全配置读取的Key>' 'http://localhost:8088/api/v1/skills?page=1&pageSize=20'
curl -i -H 'X-API-Key: <从安全配置读取的Key>' 'http://localhost:8088/api/v1/skills/java-code-review'
curl -i -H 'X-API-Key: <从安全配置读取的Key>' 'http://localhost:8088/api/v1/skills/java-code-review/SKILL.md'
curl -i -H 'X-API-Key: <从安全配置读取的Key>' -H 'If-None-Match: "上次响应的标签"' 'http://localhost:8088/api/v1/skills/java-code-review/SKILL.md'
```

客户端读取成功为 `Cache-Control: private, no-cache`，错误 `no-store`。不存在、未发布或下架均 404，旧 ETag 不能使已下架资源返回 304。元数据列表不含正文和草稿字段；JSON 详情额外提供完整 `skillMd`。复制原文绝对地址由页面使用自身 origin 组装，不接受任意 Host 构造外链。

## 日常运维与升级

```sh
docker compose ps
docker compose logs --tail 100 frontend backend mysql
docker compose stop
docker compose start
docker compose down
docker compose up -d --wait
# 升级：先备份并检查迁移，再拉取代码、构建和重建
docker compose up --build -d --wait --wait-timeout 240
```

普通 `down` 保留命名卷，后续使用相同项目名称及目录启动会继续读取原数据。`down -v` 会删除数据卷，**不是更新步骤，不要随意执行**。本仓库脚本不执行该命令。修改 Compose 项目名会使用不同卷，不代表原卷丢失。服务依赖健康检查，数据库连接首启等待至多 60 秒并由 Flyway 重试；失败查看 backend / mysql 日志，检查凭证、数据库名、origin 配置与数据库版本。

MySQL volume 初始化后，修改 `.env` 的数据库名或密码**不会更改已创建账户**。密码轮换应在受控维护窗口，通过 `docker compose exec mysql mysql -uroot -p` 交互输入密码后执行 `ALTER USER 'skill_app'@'%' IDENTIFIED BY '新密码';`（替换实际用户名），再同步 `.env` 并重建 backend；不要把真实密码写入共享日志、命令历史或 Git。保留 volume，不能靠删除卷修复凭证。

后端仅以非 root 用户运行；健康检查使用编译进镜像的 Java 类，不依赖 curl。MySQL 健康检查使用自身 mysql 客户端，实际连接应用数据库执行 SELECT 1。Nginx Alpine 使用镜像内 BusyBox wget 检查 `/healthz`。仅暴露 Actuator health 状态，不暴露环境、配置或敏感详情。

## MySQL 备份与恢复

备份可能含敏感 Skill，置于仓库外受控位置。以下 POSIX shell 示例在数据库容器中用环境变量读取密码，避免命令行明文密码。dump 先在容器生成再 `docker compose cp`，避免 Windows PowerShell 的重定向编码转换损坏文件。

```sh
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysqldump -u"$MYSQL_USER" --single-transaction --no-tablespaces --set-gtid-purged=OFF "$MYSQL_DATABASE" > /tmp/skill-backup.sql'
docker compose cp mysql:/tmp/skill-backup.sql ./skill-backup.sql
```

将 `skill-backup.sql` 移至仓库外并保护权限；不要提交 Git。恢复只对**隔离恢复项目或经过批准的目标数据库**操作：准备独立 `.env.restore`、独立项目名和空数据库卷，先仅启动 mysql，然后：

```sh
docker compose --env-file .env.restore -p skill-restore up -d mysql --wait
docker compose --env-file .env.restore -p skill-restore cp ./skill-backup.sql mysql:/tmp/skill-backup.sql
docker compose --env-file .env.restore -p skill-restore exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" < /tmp/skill-backup.sql'
docker compose --env-file .env.restore -p skill-restore up -d --wait
```

备份包含 Flyway 元数据；恢复应先使用与备份兼容的应用/迁移版本，再升级。不要对使用中的数据库直接覆盖恢复。不要提交 SQL dump、生产数据或卷目录。

## 开发与验证

本地工具：JDK 21、Maven 3.9、Node 22.12+、Docker。无需安装本机 MySQL，后端集成测试使用 Testcontainers 创建全新 MySQL 8.4.6；缺少 Docker 时应报错，不会以 H2 替代或静默跳过。

```sh
cd backend
mvn -B -ntp -s ../tools/maven-settings.xml test
cd ../frontend
npm ci
npm test
npm run build
```

已启动隔离 Compose 环境时，可进一步运行真实浏览器测试：

```powershell
cd frontend
# 使用已安装的 Chrome；也可不设置 channel 并先 npx playwright install chromium
$env:BROWSER_CHANNEL='chrome'
npx playwright test
```

浏览器测试默认连接 `http://127.0.0.1:18088`，可用 `E2E_BASE_URL` 修改；测试只创建随机 name 的记录。

`tools/maven-settings.xml` 是空白 Maven Central 配置，避免用户全局私有 mirror 影响复现。开发运行 backend 时提供 `DB_HOST, DB_NAME, DB_USER, DB_PASSWORD, ALLOWED_ORIGINS, ADMIN_USERNAME, ADMIN_INITIAL_PASSWORD`，再 `mvn spring-boot:run`；前端 `npm run dev` 代理 `/api` 到本机 8080，允许的开发 origin 需显式加入 `http://127.0.0.1:5173`。

在独立测试项目执行 Docker 冒烟（新卷、18089 端口；Key 仅保留在进程内存）：

```sh
python tools/new-test-env.py
docker compose --env-file .test-output/auth-smoke.env -p skillinventory-auth-smoke config --quiet
docker compose --env-file .test-output/auth-smoke.env -p skillinventory-auth-smoke up --build -d --wait --wait-timeout 240
python tools/smoke.py --restart
docker compose --env-file .test-output/auth-smoke.env -p skillinventory-auth-smoke stop
```

生成器拒绝覆盖配置。冒烟中的 down/up 仅针对显式独立项目，保留命名卷，不执行 down -v。请勿传入生产项目。浏览器测试设置 E2E_BASE_URL=http://127.0.0.1:18089，并使用 E2E_ADMIN_USERNAME / E2E_ADMIN_PASSWORD 环境变量或本地测试配置。

实际执行结果、环境阻碍、未验证项见 [验证记录](docs/VALIDATION.md)。JSDOM 组件测试不等同真实浏览器人工验收，测试环境通过不代表生产部署或用户验收。

## 无外网的内网部署

在可联网且与内网 CPU 架构相同的构建机先 `docker compose build`、`docker compose pull mysql`，再导出：

```sh
docker save -o skill-platform-images.tar skill-platform-backend:1.0.0 skill-platform-frontend:1.0.0 mysql:8.4.6
# 将镜像包、compose.yaml、.env.example 安全转移到内网
docker load -i skill-platform-images.tar
# 配置独立 .env 后，不再构建或拉取
docker compose up -d --no-build --pull never --wait --wait-timeout 240
```

运行阶段仅依赖容器网络中的 MySQL 和后端，不访问外网 API。镜像构建阶段需要 npm、Maven 和基础镜像仓库；也可以使用公司批准的镜像仓库与依赖镜像。以上止于本地/测试环境交付，未执行生产部署。
