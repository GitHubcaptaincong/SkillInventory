# 外部 HTTP 请求测试 Skill API

脚本 `tools/test-skill-api.py` 使用 Python 3.10+ 标准库，从调用机器直接请求运行中的服务，不依赖 Docker CLI、数据库连接或管理员登录，不修改数据。

先在管理页面创建 API Key，并至少发布一个 Skill。当前后端 `/api/v1/**` 需要 `X-API-Key`；认证、CSRF 与 Key 轮换说明见 `docs/AUTH.md`，README/API 示例已要求携带 Key。

在仓库根目录运行（PowerShell）：

```powershell
python .\tools\test-skill-api.py --base-url http://127.0.0.1:8088
# 按隐藏输入提示粘贴 API Key；默认选取第一页中的第一个已发布 Skill。

# 其他机器调用：替换为实际可达的服务地址及已发布 Skill name
python .\tools\test-skill-api.py --base-url http://192.168.1.20:8088 --name java-code-review --timeout 20
```

也支持从进程环境变量 `SKILL_API_KEY` 读取 Key，适合 CI 注入；脚本不打印或保存 Key，不通过命令行参数接收 Key，不跟随重定向，并使用系统默认 TLS 证书验证。

测试覆盖：缺失/无效 Key 返回 401；列表分页和草稿字段隔离；非法分页返回 400；JSON 详情和 UTF-8 原文一致；三个读取接口的 ETag、304、缓存头；携带有效旧 ETag 的无效 Key 仍返回 401。测试全程只发送 GET；不测试创建、发布、下架或 Key 撤销。并发发布可能导致详情与原文对比失败，可在数据稳定后重跑。

每次 HTTP 断言成功输出 PASS，最终所有断言通过退出码为 0，失败为 1。没有已发布 Skill 时明确失败，不把未执行的详情/原文检查计为通过。外部调用需目标端口绑定到调用机器可达的地址并已按部署要求放通网络。

本次交付的脚本语法和参数帮助已在本机验证；实际目标服务及有效 API Key 的端到端验证需在运行后确认。
