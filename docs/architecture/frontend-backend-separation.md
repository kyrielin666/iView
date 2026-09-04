# iView 前后端分离部署

iView 前端与后端是两个独立项目：前端位于同级目录 `D:\\code\\iView-frontend`，后端位于 `D:\\code\\iView`。两者通过 `/api/v1/**` 与 `/actuator/**` HTTP 接口通信。

## 本地开发

启动前端的 `server.mjs` 后访问 `http://localhost:3000`。该开发服务器会把 API 请求代理给 `http://localhost:8080`，因此不需要 CORS 配置。

## 独立部署

1. 构建并部署 `iView-frontend/dist` 到任意静态 Web 服务器。
2. 在 HTML 的 `iview-api-base` meta 标签中设置后端根地址，或在页面加载前设置 `window.IVIEW_API_BASE`，例如 `https://api.iview.example.com`。
3. 后端设置 `IVIEW_CORS_ALLOWED_ORIGINS=https://console.iview.example.com`。多个来源用逗号分隔。

默认不启用 CORS；不要使用通配来源。生产环境应由网关终止 TLS，并分别部署前端静态资源与 JVM 后端。
