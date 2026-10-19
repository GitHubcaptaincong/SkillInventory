# 管理员、会话和只读 API Key

此文与 API.md 共同固定当前认证契约。管理仅单管理员，无注册或公开找回密码。仍限受控网络；跨不可信网络必须 HTTPS。

## 首启、升级与 Cookie

复制 `.env.example`，设置独立数据库密码、`ADMIN_USERNAME`（1–64 字符）和 `ADMIN_INITIAL_PASSWORD`（至少 12 字符，至多 72 UTF-8 字节）。没有默认密码。不提交 `.env`，不打印完整 Compose 配置。

执行 `docker compose up --build -d --wait --wait-timeout 240`，打开前端 `/login`。后端仅在管理员表为空时初始化 BCrypt cost=12 哈希；固定主键与数据库 CHECK 限制只有一个管理员，并发初始化不覆盖已有值。后续重启、环境变量变化均不修改已有密码。部署配置仍需提供初始化变量；首次启动后可将初始密码替换成未使用的随机占位值，登录继续使用数据库已有密码。

升级前备份数据库，保留相同 Compose 项目和命名卷。V1 不变，V2 仅新增表，Flyway 管理 Spring Session 3.5.x MySQL 标准结构。禁止用删除卷解决迁移或凭证问题。

`SESSION_TIMEOUT=30m` 是空闲有效期，活跃请求延长会话。Cookie `SESSION`：HttpOnly、SameSite=Lax、Path=/，仅 Cookie 追踪，不使用 URL 会话 ID。本地 HTTP 明确配置 `SESSION_COOKIE_SECURE=false`；HTTPS 部署必须设为 `true`，并由可信反向代理终止 TLS。不要把 HTTP 调试配置用于不可信网络。MySQL 持久会话跨普通重启和 down/up 保留，在有效期内可继续使用。

## 管理认证契约

所有响应均不缓存；错误为 `{code,message,requestId}`，requestId 同时在响应头。管理员会话不代替外部 API Key；API Key 不代替管理会话。

| 方法和地址 | 输入 | 成功响应 |
| --- | --- | --- |
| GET /api/auth/csrf | 无 | 200 `{token,headerName:"X-CSRF-TOKEN"}`，建立会话 |
| POST /api/auth/login | JSON `{username,password}`，携带会话 Cookie 和 CSRF 头 | 200 `{username}`，更新会话 ID 和 CSRF |
| GET /api/auth/me | 会话 Cookie | 200 `{username}`；未登录 401 |
| POST /api/auth/logout | JSON `{}`，会话 Cookie 和 CSRF | 204，会话失效，清 Cookie |

写接口包括登录、退出都需要 CSRF。首先 GET csrf，使用相同 Cookie jar 将返回 token 放入 `X-CSRF-TOKEN`，再发送 JSON；登录和退出后重新 GET csrf。Vue 自动完成这些步骤，不使用 localStorage。直接调用管理接口时用安全保存的 Cookie jar，同样获取 token，不要把密码写入 shell 历史。

示意（所有凭证均为占位符）：

```sh
curl -c <受控Cookie文件> http://部署地址/api/auth/csrf
curl -b <受控Cookie文件> -c <受控Cookie文件> \
  -H 'Content-Type: application/json' -H 'X-CSRF-TOKEN: <token>' \
  --data-binary @<受控登录JSON文件> http://部署地址/api/auth/login
# 登录后重新获取 token
curl -b <受控Cookie文件> http://部署地址/api/auth/csrf
curl -b <受控Cookie文件> -H 'Content-Type: application/json' \
  -H 'X-CSRF-TOKEN: <新token>' --data '{}' http://部署地址/api/auth/logout
```

登录 JSON 文件内容为 `{ "username":"<管理员>", "password":"<密码>" }`。使用完删除受控临时登录文件与 Cookie 文件。凭证只从安全配置读取，不提交或输出。登录错误统一为 401 INVALID_CREDENTIALS。每进程每 60 秒最多 20 次有效格式的登录尝试（成功和失败都计数），超限 429 LOGIN_THROTTLED；窗口自动恢复，不永久锁定。全局有界计数不会被随机用户名/IP 撑大，但会短暂影响其他管理员尝试；多实例各自节流，受控网络之外还需网关限流，本版不声称公网生产级防护。

来源白名单仍校验 Origin / Sec-Fetch-Site。允许凭证的 CORS 仅返回显式 Origin，允许 `X-CSRF-TOKEN,X-API-Key`，不返回通配符。401 未认证、403 CSRF/来源/授权、400 参数、404 不存在、409 冲突、413 大正文、415 媒体类型、429 节流、500 服务错误均保留实际状态和 no-store。未知接口不开放客户端读取旁路。

会话过期时 Vue 保留当前页面和输入，显示新标签页登录入口；登录后原页面重试写入时若旧 CSRF 被拒绝，请重新获取页面会话的 token（可在原页点击重新连接），不自动重放写请求。

## API Key 契约

| 方法和地址 | 输入 | 成功响应 |
| --- | --- | --- |
| GET /api/admin/api-keys?page=1&pageSize=20 | 页码 1–100000，每页 1–100 | `{items,page,pageSize,total}`，按 id 倒序 |
| POST /api/admin/api-keys | `{name,expiresAt:null或ISO时间}` | 201 `{metadata,key}`；完整 key 仅此一次 |
| POST /api/admin/api-keys/{id}/revoke | `{}` | 200 metadata；重复撤销保留原 revokedAt |

metadata：`{id:string,name,masked,createdAt,expiresAt,revokedAt,status}`。status 读取时根据时间计算 ACTIVE/EXPIRED/REVOKED，不持久化。名称非空最多 100 字符，创建过期时间必须在未来。

在管理页面创建后立即保存到调用方的安全配置；关闭或离开 Key 页面后清除前端明文引用。列表没有查看明文功能。丢失只能重建。轮换：创建新 Key → 更新调用方 → 验证 → 撤销旧 Key。撤销不可恢复，立即影响下一请求（已在执行中的请求不回溯撤回）。

Key 格式 `sk_<24位公开十六进制标识>_<64位随机十六进制密钥>`，密钥 256 bit 来自 SecureRandom；数据库只保存 SHA-256 摘要、公开标识、脱敏尾号及元数据。比较使用 MessageDigest.isEqual，无额外业务缓存和每请求写统计。

以下三个 GET 接口仅接受 `X-API-Key`，不接受 query、URL 或会话替代。未提供、错误、撤销、过期统一 401，不区分状态；认证先于业务读取和条件缓存。有效 Key 对不存在/草稿/已下架均 404。

```sh
curl -H 'X-API-Key: <从安全配置读取的Key>' 'http://部署地址/api/v1/skills'
curl -H 'X-API-Key: <从安全配置读取的Key>' 'http://部署地址/api/v1/skills/java-code-review'
curl -H 'X-API-Key: <从安全配置读取的Key>' 'http://部署地址/api/v1/skills/java-code-review/SKILL.md'
curl -i -H 'X-API-Key: <从安全配置读取的Key>' \
  -H 'If-None-Match: "<上次ETag>"' 'http://部署地址/api/v1/skills/java-code-review/SKILL.md'
```

成功与 304 为 `Cache-Control: private, no-cache`，`Vary: X-API-Key` 并保留 Origin 等现有 Vary。所有错误 no-store。Nginx 不缓存，透传 Key，禁用访问日志以防 query/Cookie/凭证泄露；应用不记录请求正文或凭证。部署方添加代理日志时不得记录 Cookie、API Key、密码和 Skill 正文。

## 受控管理员密码重置/轮换

无公开重置 API。维护员先备份并停止 backend，在受控机器使用项目 Spring Security BCrypt 生成新哈希，再通过交互式 MySQL 管理连接执行事务：

```sql
START TRANSACTION;
UPDATE administrator SET password_hash='<新BCrypt哈希>' WHERE id=1;
DELETE FROM SPRING_SESSION;
COMMIT;
```

清理全部会话使旧会话失效；不删除管理员或 Skill，不修改 Flyway。检查影响行数为 1，重新启动 backend，用新密码登录。初始化环境变量不会重置已有密码。生成哈希可使用 `tools/PasswordHash.java`（JDK 21，classpath 包含 Spring Security crypto 及 spring-jcl jar；Windows 用分号分隔），从交互终端隐藏输入读取密码，不使用命令行密码或日志记录。哈希也按敏感配置保管，切勿把真实密码发给聊天或提交 Git。

官方机制参考：[Spring Security 会话认证与显式持久化](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html)、[Spring Session JDBC](https://docs.spring.io/spring-session/reference/configuration/jdbc.html)。
