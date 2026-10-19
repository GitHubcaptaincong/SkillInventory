# 内部 Skill 平台 Codex 开发提示词

认证增量要求以 [AUTH-REQUIREMENTS.md](docs/AUTH-REQUIREMENTS.md) 为准，认证接口与运维见 [AUTH.md](docs/AUTH.md)。

你是一名负责交付的全栈工程师。请在当前仓库实现一个轻量、可运行的公司内部 Skill 管理与分发平台，交付实际代码、数据库迁移、测试、Docker 部署和文档。

## 一 工作方式

1. 先阅读仓库内适用的 AGENTS.md，检查目录、现有代码与配置，遵循已有约定，不覆盖用户现有改动。空仓库则直接初始化项目。
2. 用简短计划说明实施顺序，然后持续实现，直到完成可交付的首版。不要只输出架构、计划、伪代码或待办事项。
3. 以下需求已确认，不要反复询问权限、账号体系、Skill 多文件支持等问题。常规工程细节自行选择并记录。
4. 如仓库已有需求文档，保存本文的已确认要求，并将与之冲突的旧“待确认”文字更新掉。本文需求优先于旧草案。
5. 本次交付止于本地或测试环境实现和验证；不执行生产部署，不清空已有数据库或 Docker 数据卷。

## 二 已确认范围

- 后端 Spring Boot + MySQL；前端 Vue 3 + TypeScript + Vite，可使用 Element Plus。
- Java 建议使用 21；Spring Boot、MySQL、Node 等选择相互兼容、受支持的稳定版本，并固定实际版本或镜像标签。开始实施时检查官方兼容说明，不使用 latest 标签。
- 数据访问优先复用仓库方案；空仓库选择 Spring Data JPA 或 MyBatis 其中一种，不同时引入两套。
- 前端、后端、MySQL 均通过 Docker Compose 管理。
- 仅在受控网络使用。单管理员通过服务端持久会话登录管理；外部三个客户端接口必须使用只读 API Key。认证增量需求见 docs/AUTH.md。不做注册、多角色或审批。
- 每个 Skill 仅管理一个 SKILL.md，不支持附属脚本、资料、图片、ZIP 或其他文件。
- 支持创建、查看、编辑、保存草稿、手动发布、下架和删除。
- 客户端只读取已发布快照。
- 不做历史版本、回滚、回收站、MCP Server、客户端 SDK、Skill 执行或 Agent 编排。
- 单体后端、Skill 业务表以及管理员、API Key 和持久会话表，不增加 Redis、消息队列、搜索服务或对象存储。允许迁移工具维护自身的元数据表。

## 三 Skill 编辑与生成规则

编辑字段：
- name：必填、唯一、1–64 个字符，仅小写英文字母、数字和连字符；不允许首尾连字符或连续连字符，创建后不可修改。
- description：必填，1–1024 个字符，说明用途和适用场景。
- content：必填且不能全空白，Markdown 指令正文，不包含文档级 YAML frontmatter。按 UTF-8 字节长度限制为 256 KiB；校验非空时不要破坏原始 Markdown 的缩进、换行和有效空格。
- lockVersion：系统维护的并发版本，不由用户手动编辑。

name、description 和正文通过表单编辑，不再提供一份独立的完整 YAML 文档供用户重复维护。
服务端使用可靠的 YAML 序列化器组装完整 SKILL.md，正确处理冒号、引号、多行和特殊字符，不手工拼接未经转义的 description。生成结果应稳定、可解析、使用 UTF-8。

输出结构为：
---
name: java-code-review
description: 检查 Java 后端代码中的常见问题。
---

后面追加 Markdown 正文。

不执行正文中的命令、脚本或模板表达式，不抓取其中的 URL。

## 四 Skill 数据与生命周期

业务表 skill 建议包含：
- id：BIGINT 主键，JSON 返回字符串。
- name：唯一索引，不可修改。
- description、content：当前草稿。
- published_snapshot：可空 JSON，保存已发布的 name、description、正文、publishedVersion。
- lock_version：乐观锁。
- created_at、updated_at、published_at。

使用 MySQL utf8mb4，统一时间存储约定，API 返回带时区的 ISO 8601 时间，前端按本地时间显示。
发布快照只允许服务端构造；使用独立 DTO，禁止通过通用实体绑定让调用方覆盖系统字段。

状态根据快照和内容计算：
- 无快照：未发布。
- 有快照且草稿内容一致：已发布。
- 有快照且草稿有差异：有未发布修改。
不要额外维护容易与数据不一致的状态列。

行为：
1. 创建只生成草稿，客户端完全不可见。
2. 编辑保存只更新草稿，不影响上次发布。
3. 发布先验证当前草稿，在事务内原子替换快照并记录发布时间；客户端随后读取新快照。
4. 发布已保存内容，页面有未保存修改时必须先提示保存。
5. 内容未改变的保存不制造“有未发布修改”；重复发布相同内容不生成新发布内容。
6. 下架需要确认：清除快照与 published_at，保留草稿，客户端读取返回 404。
7. 删除需要确认：只允许删除无快照的 Skill；已发布记录直接删除返回 409。物理删除，不实现回收站。
8. 删除后允许重新使用 name，客户端可结合 id 区分重建对象。
9. 下架只停止服务端继续分发，不能撤销客户端此前下载的副本，在接入文档中说明缓存责任。

并发要求：
- 保存、发布、下架、删除都携带读取时的 lockVersion。
- 数据库操作必须原子验证版本，例如 UPDATE/DELETE ... WHERE id=? AND lock_version=?，或等效的 ORM 乐观锁；不能先普通查询检查再无条件更新。
- 成功的数据变更更新 lock_version。
- 版本过期返回 409，包括发布和下架；不得把调用者未看到的新草稿发布出去。
- 重复发布、重复下架等无实质变更的请求也应先验证版本，规则保持一致。
- 前端保留冲突时未提交的输入，提示刷新后重新编辑，不静默覆盖。
- publishedVersion 使用该次发布对应的单调版本标记，可允许跳号；同一记录下架后再发布也不得重置。可复用发布事务的新 lockVersion，无需新增版本历史表。

## 五 HTTP 接口契约

管理接口：
GET    /api/admin/skills
POST   /api/admin/skills
GET    /api/admin/skills/{id}
PUT    /api/admin/skills/{id}
POST   /api/admin/skills/{id}/publish
POST   /api/admin/skills/{id}/unpublish
DELETE /api/admin/skills/{id}?lockVersion=...

- 创建请求：name、description、content。
- 更新请求：description、content、lockVersion；不允许修改 name。
- 发布、下架请求：lockVersion。
- 管理详情：当前草稿、当前快照、状态、lockVersion 和相关时间。
- 查询支持 keyword、status、page、pageSize。

客户端只读接口：
GET /api/v1/skills
GET /api/v1/skills/{name}
GET /api/v1/skills/{name}/SKILL.md

- 只读查询中的 description、正文和时间必须来自已发布快照，不能混入当前草稿。
- 列表只返回已发布元数据：id、name、description、publishedVersion、publishedAt、skillMdPath。不要返回正文或任何草稿字段。
- JSON 详情返回上述元数据及完整 skillMd。
- 原文接口返回完整 SKILL.md，Content-Type: text/plain; charset=utf-8，成功时没有 JSON 包裹。
- skillMdPath 使用相对 API 路径，复制绝对地址时使用当前页面 origin，避免信任任意 Host 生成外链。
- name 精确匹配；未发布、已下架或不存在统一返回 404。
- 不存在、已下架时，也不能让旧 ETag 命中返回 304。

分页：
- page 从 1 开始，默认 1；pageSize 默认 20、最大 100；对 page 设置合理上限并防止 offset 溢出。
- keyword 限长，空字符串视为未筛选，按普通文字查询，不将 SQL LIKE 通配符视为用户查询语法。
- status 使用白名单；排序固定为 updated_at DESC、id DESC 或其他合理稳定顺序。
- 用统一分页 JSON，例如 { items, page, pageSize, total }，明确 total 的含义。

响应：
- 成功 JSON 可直接返回资源或分页 DTO，前后端保持一致。
- 错误至少包含 { code, message, requestId }。
- 400：参数错误；404：不存在或不可读取；409：重名、乐观锁冲突、未下架删除；413：请求过大；415：媒体类型不支持；403：不允许的来源；500：服务异常。
- 不将所有错误包装为 HTTP 200。
- BIGINT id 序列化为字符串；校验版本参数为非负整数。

缓存：
- 客户端列表、JSON 详情、原文均提供基于各自实际响应内容的 ETag，支持 If-None-Match/304。
- 列表 ETag 覆盖实际筛选和分页结果，不能只用一个 Skill 的更新时间代替。
- 使用 Cache-Control: private, no-cache；错误响应 no-store。
- 先确认存在且已发布，再判断条件缓存。
- 处理标准 HTTP 条件头语义，优先使用框架支持。
- 第一版不引入应用业务缓存和 Redis，避免发布、下架后的失效复杂度。

## 六 Vue 页面

至少提供列表页、新建和编辑详情页。

列表：
- 展示 name、description 摘要、状态、更新时间、发布时间。
- 支持关键词、状态筛选和分页。
- 提供新建、编辑、发布、下架、删除、复制 name 和发布原文地址。
- 未发布时不提供可用的发布地址；已发布时引导先下架再删除。
- 有加载、空数据、失败和操作反馈。

编辑：
- name、description 表单，Markdown 编辑区和预览。
- 编辑已有记录时 name 只读。
- 能分别查看草稿和当前已发布内容；不要求实现差异比较。
- 提供正文初始模板、保存、发布、下架、返回。
- 未保存离开提醒；提交期间禁用重复按钮。
- 发布确认明确提示其会影响客户端读取内容。
- 冲突提示保留用户尚未提交的编辑内容。
- UI 简洁适合内部工具，不添加与首版无关的产品模块。

## 七 基本安全

1. 所有 SQL 值参数绑定，排序等动态结构使用固定值或白名单。
2. Markdown 禁用原始 HTML，并使用维护中的清洗库处理渲染结果，过滤脚本、事件属性及 javascript: 等危险协议；不要用简单正则充当 HTML 清洗。
3. 写接口只接受 application/json，拒绝 form/text/plain 等不符合契约的媒体类型；GET 不修改数据。
4. 默认同域部署，开发跨域仅允许显式配置的来源，不使用通配符。
5. 不只配置 CORS：服务端实际拒绝带有未允许 Origin 的写请求，并结合 Sec-Fetch-Site 等信号拒绝浏览器跨站写入；同源浏览器请求正常可用，无 Origin 的内网直接 HTTP 调用仍必须通过会话与 CSRF 或 API Key 认证。健康检查不受写入策略影响。
6. 部署时显式配置合法管理页面 origin；配置错误应有清楚诊断，不能为了省事默认全部允许。
7. 校验 path 参数，不读写服务器任意路径，不执行内容，不提供远程 URL 抓取。
8. 请求体上限建议 2 MiB，与前端 Nginx 和后端保持一致；正文另行校验 256 KiB UTF-8 字节上限。
9. 全局错误处理不返回 SQL、堆栈、密码或数据库连接串；日志不记录完整 Skill 正文与密码。
10. 提供 requestId，控制调试及管理端点暴露；健康端点只返回必要状态，不输出敏感详情。
11. 后端使用最小权限数据库账户；配置来自环境变量，真实 .env 不提交。
12. 在 README 明确：Origin/CORS 与输入校验不提供身份认证，本版安全依赖受控内网，所有能直接访问管理接口的人员都有管理能力。

## 八 Docker Compose 必须交付

提供 compose.yaml，包含：
- frontend：Vue 多阶段构建，最终由 Nginx 提供静态页面，/api 反代 backend；Vue Router history 模式支持刷新回退，/api 错误不能回退为 index.html。
- backend：Spring Boot 多阶段构建，生产镜像按非 root 用户运行。
- mysql：固定受支持版本，持久化命名 volume，健康检查验证数据库可用。

部署要求：
- 默认只发布 frontend 端口，可通过环境变量配置；backend 与 mysql 不映射宿主机端口。
- 容器间通过 Compose 服务名通信，数据库 host 使用 mysql，不使用 localhost；Nginx 上游使用 backend。
- Compose 为必要依赖配置 service_healthy 条件、合理的健康检查与重启策略；后端具备合理的数据库连接重试或失败诊断。
- 所有健康检查命令必须能在实际镜像中执行，不假设镜像自带 curl、wget 或其他工具。
- 提供数据库版本化迁移，应用启动时执行，持久化 MySQL 数据在重建容器后保留。
- 用独立应用账户访问业务数据库，不用 root；数据库迁移权限在首版启动方案中一并解释。
- 提供 .env.example，至少包括前端端口、应用数据库名、应用用户名、应用密码、MySQL root 密码、允许的前端 Origin。
- 必要密码缺失时明确失败；不内置固定生产密码。
- 提供正确 .gitignore、.dockerignore 和前端锁文件；运行 npm ci 或等效锁定安装。
- 不要求 Kubernetes。
- README 记录 docker compose config、up --build -d、ps、logs、stop/start、down、升级重建及 MySQL 备份恢复。
- 明确普通 down 保留卷，down -v 会删除数据。不要自动执行 down -v，不将其列为常规更新步骤。
- README 说明已有 MySQL volume 初始化后，仅修改 .env 不会自动改变已创建数据库用户密码，应通过受控 SQL 操作修改。
- 镜像准备好后，运行阶段不依赖外网 API；对不能访问外部仓库的内网提供镜像预构建、save/load 说明。
- 不将生产数据、真实密码或卷内容提交到仓库。

建议结构，可按现有仓库调整：
backend/
frontend/
deploy/nginx/
docs/
compose.yaml
.env.example
README.md

## 九 验证与交付

优先验证业务边界和实际 MySQL 行为，不堆砌重复测试。测试数据库必须与用户现有数据库隔离，可使用 Testcontainers 或专用测试 MySQL；不能只通过 H2 就声称 MySQL 验证完成。

至少覆盖：
1. 草稿不可被客户端列表、详情和原文读取。
2. 发布后元数据与 SKILL.md 内容一致；description 编辑后不泄露未发布内容。
3. 保存新草稿不影响已发布原文；再次发布后才变化。
4. 下架后列表移除，详情和原文为 404，旧 ETag 也不能返回 304。
5. 已发布记录不能直接删除，下架后可以删除。
6. 重名、非法 name、超长描述、UTF-8 正文字节超限和过大请求。
7. 保存、发布、下架、删除的过期 lockVersion 均正确拒绝，实际并发不丢失更新。
8. 同内容保存、重复发布、删除重建的行为符合契约。
9. YAML 中的冒号、引号、多行中文能够被可靠解析。
10. 分页筛选、ETag/304、错误格式和状态码。
11. SQL 注入式输入、Markdown XSS、跨站写入来源、错误媒体类型。
12. 前端生产构建通过。

Docker 冒烟：
- 配置测试 .env 后 docker compose config 通过。
- docker compose up --build -d 能启动三个健康服务。
- 默认只发布前端端口，浏览器经 /api 可访问后端。
- 经公开入口验证创建 → 发布 → 修改草稿 → 客户端仍读旧内容 → 再发布 → 下架 → 删除。
- 对保留数据的记录验证重启或普通 down/up 后数据仍存在。
- 在隔离测试项目和卷中验证，不破坏现有数据。

如果当前环境没有 Docker、Java、Node 或网络导致不能完成某项验证，继续完成能完成的代码和检查，明确列出阻碍、未验证项及可执行验证命令，不伪造通过结果。

最终交付：
- 完整源代码、迁移、测试、Dockerfile、Nginx、Compose、环境变量样例。
- README 和明确的 API 契约；提供客户端列表、详情、原文、条件请求调用示例。
- 可手动使用的示例 Skill，不在生产启动时自动插入或发布。
- 实际执行过的验证命令、结果、未验证项。
- 简短说明关键实现、已知限制、启动步骤，不声称已完成生产部署。

现在先检查仓库并开始实施。常规选择自行推进，不增加需求之外的模块。
