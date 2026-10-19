# API 契约

基础路径为同域 `/api`；JSON 成功响应直接返回资源。所有 ID 是十进制字符串；时间是 UTC ISO 8601（带 `Z`），页面按浏览器本地时区显示。版本是非负 64 位整数，通常远小于 JavaScript 安全整数上限；本版不适合超过该上限的记录。

认证、会话和 API Key 契约见 [AUTH.md](AUTH.md)。管理接口均需要会话，写请求还需 CSRF；以下客户端接口均需 X-API-Key。

## 管理接口

| 方法 | 路径 | 请求 | 响应 |
| --- | --- | --- | --- |
| GET | `/api/admin/skills` | keyword、status、page、pageSize | 管理资源分页 |
| POST | `/api/admin/skills` | name、description、content | 201 管理详情 |
| GET | `/api/admin/skills/{id}` | 正整数 id | 管理详情 |
| PUT | `/api/admin/skills/{id}` | description、content、lockVersion | 管理详情 |
| POST | `/api/admin/skills/{id}/publish` | lockVersion | 管理详情 |
| POST | `/api/admin/skills/{id}/unpublish` | lockVersion | 管理详情 |
| DELETE | `/api/admin/skills/{id}?lockVersion=0` | 无正文 | 204 |

写请求包括 DELETE 都须携带 `Content-Type: application/json`。不支持未知 JSON 字段；禁止传入 name 更新、snapshot、id、时间等系统字段。版本必须是 JSON 整数，不能是浮点数或字符串。状态白名单为 `UNPUBLISHED`、`PUBLISHED`、`MODIFIED`，分别表示无快照、草稿与快照相同、草稿与快照不同。

管理详情形状：

```json
{"id":"1","name":"java-code-review","description":"草稿描述","content":"# 正文","publishedSnapshot":null,"status":"UNPUBLISHED","lockVersion":0,"createdAt":"2026-10-19T00:00:00Z","updatedAt":"2026-10-19T00:00:00Z","publishedAt":null}
```

`publishedSnapshot` 非空时包含 `name, description, content, publishedVersion, publishedAt`。首次保存版本 0；每次实质变更递增。相同内容保存、重复发布、重复下架原子验证版本但不递增。发布版本取本次变更后的 lockVersion，下架不会重置计数。409 时应保留表单输入，重新读取后由用户合并；不能自动重新提交。

## 客户端接口

| 方法 | 路径 | 内容 |
| --- | --- | --- |
| GET | `/api/v1/skills` | 已发布元数据分页，可筛 keyword |
| GET | `/api/v1/skills/{name}` | 元数据和完整 skillMd |
| GET | `/api/v1/skills/{name}/SKILL.md` | `text/plain; charset=UTF-8` 原文 |

列表元素只包含 `id, name, description, publishedVersion, publishedAt, skillMdPath`；正文只在详情 `skillMd` 和原文接口返回。元数据取发布快照。列表按当前记录更新时间、id 降序，草稿编辑可能影响顺序，但不泄露草稿字段。name 精确匹配；未发布、下架、不存在均为 404。`skillMdPath` 是相对路径，使用可信的当前页面 origin 拼接。

分页统一为 `{ "items": [], "page": 1, "pageSize": 20, "total": 0 }`。`total` 是筛选后所有匹配记录数，不是本页条数。page 范围 1–100000，pageSize 范围 1–100。keyword 最多 128 字符，空白视为不筛选，`%`、`_`、`!` 按普通字符查询，不区分查询语法；默认数据库二进制排序规则下区分大小写。所有动态查询值绑定，排序固定。

```sh
curl -i -H 'X-API-Key: <从安全配置读取的Key>' 'http://localhost:8088/api/v1/skills?page=1&pageSize=20'
curl -i -H 'X-API-Key: <从安全配置读取的Key>' 'http://localhost:8088/api/v1/skills/java-code-review'
curl -i -H 'X-API-Key: <从安全配置读取的Key>' 'http://localhost:8088/api/v1/skills/java-code-review/SKILL.md'
# 将上次响应的 ETag 原样带回；支持弱标签、多个标签和 *。
curl -i -H 'X-API-Key: <从安全配置读取的Key>' -H 'If-None-Match: "实际响应标签"' 'http://localhost:8088/api/v1/skills/java-code-review/SKILL.md'
```

三个读取接口均按实际响应字节生成 ETag，成功为 `Cache-Control: private, no-cache`，匹配为 304；先校验 API Key，再查发布存在性，下架后旧标签也返回 404。客户端每次使用前应重验证；下架不能撤销已下载副本，缓存保留/销毁由客户端负责。服务端不提供离线副本撤销。

## 输入与错误

name 1–64 字符，仅小写字母数字和单连字符，不允许首尾连字符，创建后不可修改，删除后可重用。description 非空白且最多 1024 Java UTF-16 字符。正文非空白、UTF-8 最多 262144 字节，保留原始空格和换行；禁止文档级 YAML frontmatter。完整请求上限 2097152 字节。序列化器自动生成 frontmatter。

错误形状：`{"code":"VERSION_CONFLICT","message":"记录已变更…","requestId":"UUID"}`，错误 `Cache-Control: no-store`，请求 ID 也在 `X-Request-Id`。400 参数，401 未认证，403 来源/CSRF/授权，429 登录节流，404 不存在/不可读取，409 重名/版本冲突/已发布删除，413 过大，415 媒体类型，405 方法，500 服务异常。反向代理过大请求使用 Nginx requestId。异常不返回 SQL 或堆栈。

管理接口需要管理员会话及写请求 CSRF；客户端读取必须提供 X-API-Key。详见 [认证契约](AUTH.md)。写入接受精确配置 Origin，拒绝未允许 Origin 和 `Sec-Fetch-Site: cross-site`。无 Origin 的直接 HTTP 调用仍受会话、CSRF 和 API Key 规则保护。来源策略是额外防护。
