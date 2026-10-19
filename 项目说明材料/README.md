# Skill Inventory 项目说明材料

编制日期：2026-10-19。适用版本：当前工作区中的管理员登录与只读 API Key 首版，项目制品版本 `1.0.0`，数据库迁移至 V2。

本材料依据当前源码、SQL 迁移、前后端配置和已有验证记录编写。它描述当前工作区内容，可能包含尚未提交的文件，不把某个 Git 提交号当成完整交付边界。后续修改业务、接口或配置时，应同步修订本目录。

## 文档导航

| 文档 | 内容 | 主要读者 |
| --- | --- | --- |
| [01-系统功能说明.md](01-系统功能说明.md) | 产品用途、身份边界、Skill 生命周期、API Key、功能限制 | 产品、使用者、交接人员 |
| [02-数据库结构说明.md](02-数据库结构说明.md) | 全部应用表、字段、索引、关联、派生状态、迁移和数据约定 | 后端、数据库维护人员 |
| [03-技术架构说明.md](03-技术架构说明.md) | 部署图、模块职责、核心请求链路、认证、并发和缓存 | 前后端开发、技术负责人 |
| [04-前端操作文档.md](04-前端操作文档.md) | 管理页面操作、异常恢复、前端启动、测试及接口联调 | 管理员、前端开发 |
| [05-后端操作文档.md](05-后端操作文档.md) | 部署初始化、后端开发、配置、接口调用、测试及运维 | 后端开发、运维、外部调用方 |

业务交接先读 01，再按岗位阅读 04 或 05；技术交接先读 03，再读 02 和对应操作文档。Mermaid 图在支持 Mermaid 的 Markdown 阅读器中显示，不支持时可直接阅读图下方说明和表格。

## 版本依据与证据范围

主要依据：

- [根目录 README](../README.md)、[API 契约](../docs/API.md)、[认证说明](../docs/AUTH.md)。
- [业务实现](../backend/src/main/java/internal/skills/service/SkillService.java)、[认证配置](../backend/src/main/java/internal/skills/config/SecurityConfig.java)、[Key 实现](../backend/src/main/java/internal/skills/service/ApiKeys.java)。
- [V1 迁移](../backend/src/main/resources/db/migration/V1__create_skill.sql)、[V2 迁移](../backend/src/main/resources/db/migration/V2__authentication.sql)。
- [Compose](../compose.yaml)、[后端配置](../backend/src/main/resources/application.yaml)、[前端入口](../frontend/src/main.ts)。

已有 [验证记录](../docs/VALIDATION.md) 记载：后端 13 项测试、前端 11 项测试、Chrome 2 条端到端流程通过，另完成真实 MySQL、Nginx 冒烟及独立项目 down/up 持久化验证。这些是既有本地验证记录，本次材料编写没有重新运行应用测试，也不构成生产、客户或用户验收。未验证项以该记录为准，包括真实 HTTPS 部署、多实例长时间运行及真实管理员密码重置。

## 阅读与使用约定

- 本目录只新增说明材料，不变更业务代码、运行配置或数据库。
- 示例中的密码、Cookie、CSRF Token、API Key 均为占位符，不能直接当作实际凭证。
- 命令注明执行目录。仓库根目录指包含 `compose.yaml` 的目录；本机为 `C:\Users\maijiancong\Desktop\SkillInventory`。文内相对源码链接均以本目录为基准，可随仓库一起移动。
- 部署和测试要使用各自的配置、项目名、数据库及卷；不要在真实数据环境运行创建、撤销、重启或恢复类测试。

## 本次材料检查

已核对 5 张应用表的全部 30 个字段，检查 61 个本地文档/源码链接可达、Markdown 代码围栏成对，以及 16 段 PowerShell 示例的静态语法。命令只做语法解析，未执行其中的部署、写入、备份或恢复操作。
