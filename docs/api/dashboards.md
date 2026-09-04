# 看板 API

看板草稿保存为 JSON 内容，关联可选的数据模型；发布时产生不可变快照。运行时应读取发布快照，编辑草稿不会改变既有发布版本。

| Endpoint | 说明 |
| --- | --- |
| `GET/POST /api/v1/dashboards` | 列表与创建；列表可传 `folder_id` |
| `GET/PUT/DELETE /api/v1/dashboards/{id}` | 读取、编辑、删除草稿 |
| `POST /api/v1/dashboards/{id}/publish` | 创建不可变发布快照 |
| `GET /api/v1/dashboards/{id}/snapshots` | 查看发布快照历史 |
| `PUT /api/v1/dashboards/{id}/document` | 保存受校验的低代码画布文档 |
| `GET /api/v1/dashboards/{id}/preview` | 读取当前草稿文档 |
| `GET /api/v1/dashboards/{id}/published` | 读取当前已发布快照文档 |
| `GET /api/v1/dashboards/{id}/export` | 导出可移植的 `iview-dashboard` v1 文档包 |
| `POST /api/v1/dashboards/import` | 导入 `iview-dashboard` v1 文档包；可用 `folder_id` 与 `name` 覆盖目标文件夹和名称 |
| `/api/v1/dashboard-folders` | 看板文件夹 CRUD，支持嵌套；非空文件夹不可删除 |

文档当前支持 `grid`、`free` 两种布局，以及 `chart`、`table`、`metric`、`text`、`image`、`decoration`、`container`、`iframe` 组件类型。组件绑定字段时只能引用已发布数据模型；文档不含、也不允许直接包含协议驱动或数据库连接信息。

文档可定义最多 50 个动态变量（名称为 1–64 位字母、数字或下划线，且不得以数字开头）。每个组件最多配置 20 条条件样式和 20 个交互：条件样式支持 `eq`、`ne`、`gt`、`gte`、`lt`、`lte`、`contains`、`is_null`、`not_null`；交互支持 `click`/`change` 事件，以及 `filter`、`set_variable`、`navigate` 动作。`navigate` 必须提供正数 `target_dashboard_id`，参数使用 `parameters` 传递。

低代码编辑器前端、组件具体渲染和模板库管理仍在后续迁移范围。导入导出仅接受 iView 自身的 `iview-dashboard` v1 包，并在保存前重新校验组件绑定、变量、条件样式和交互定义。
