# 身份认证 API

首次启动空数据库时，服务会创建 `admin` 管理员和 `ADMIN` 角色。开发环境的初始密码是
`ChangeMe123!`；部署生产环境前必须显式配置 `IVIEW_BOOTSTRAP_ADMIN_PASSWORD` 与
`IVIEW_AUTH_TOKEN_SECRET`，并在首次登录后更换管理员密码。

- `POST /api/v1/auth/login`：提交 `{ "username", "password" }`，返回 15 分钟访问令牌和 7 天刷新令牌。
- `POST /api/v1/auth/refresh`：提交 `{ "refresh_token" }`，轮换刷新令牌并返回新的令牌对。
- `POST /api/v1/auth/logout`：提交 `{ "refresh_token" }`，立即撤销该刷新令牌。
- `GET /api/v1/auth/me`：传入 `Authorization: Bearer <access_token>`，返回当前用户及角色。

密码采用 PBKDF2-HMAC-SHA256（随机盐、210,000 次迭代）保存；刷新令牌只保存 SHA-256 摘要。
访问令牌为 HMAC-SHA256 签名。权限拦截已默认对 `/api/**` 启用，并按角色关联的权限码校验；登录、刷新和退出接口除外。系统提供引导管理员、用户/角色/权限管理 API 和前端系统管理界面；用户停用后，既有访问令牌会立即失效。

管理员接口位于 `/api/v1/admin`，包括用户、角色、权限 CRUD 和 `/audit` 操作审计。权限编码采用 `HTTP方法:请求路径`，例如 `GET:/api/v1/devices/**`；`*` 匹配一段路径，`**` 匹配后续多段路径，系统保留的单独 `*` 表示全部接口权限。管理接口和备份接口还会二次检查 `ADMIN` 角色，不能仅靠普通接口权限进入。

用户可配置设备数据范围。管理员默认拥有全部设备范围；非管理员仅能读取和操作被显式分配的设备，设备列表、详情、诊断、采集、实时值、历史值、控制执行和控制日志都会按此范围过滤或拒绝。部门组织与自定义菜单目录仍待后续实现。
