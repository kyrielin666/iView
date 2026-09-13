# 身份认证 API

首次启动空数据库时，服务会创建 `admin` 管理员和 `ADMIN` 角色。开发环境的初始密码是
`ChangeMe123!`；部署生产环境前必须显式配置 `IVIEW_BOOTSTRAP_ADMIN_PASSWORD` 与
`IVIEW_AUTH_TOKEN_SECRET`，并在首次登录后更换管理员密码。

- `POST /api/v1/auth/login`：提交 `{ "username", "password" }`，返回 15 分钟访问令牌和 7 天刷新令牌。
- `POST /api/v1/auth/refresh`：提交 `{ "refresh_token" }`，轮换刷新令牌并返回新的令牌对。
- `POST /api/v1/auth/logout`：提交 `{ "refresh_token" }`，立即撤销该刷新令牌。
- `GET /api/v1/auth/me`：传入 `Authorization: Bearer <access_token>`，返回当前用户及角色。

密码采用 PBKDF2-HMAC-SHA256（随机盐、210,000 次迭代）保存；刷新令牌只保存 SHA-256 摘要。
访问令牌为 HMAC-SHA256 签名。权限拦截已默认对 `/api/**` 启用，并按角色关联的权限码校验；登录、刷新和退出接口除外。当前仅提供引导管理员及底层用户、角色、权限表，还没有用户/角色管理 API 和管理界面，因此属于 RBAC 基础能力，不应表述为完整的组织权限系统。
