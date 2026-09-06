# 数据模型 API

数据模型是数据集的版本化字段契约。看板在后续迁移中将绑定已发布模型，而非直接使用设备驱动或任意数据库连接。

| Endpoint | 说明 |
| --- | --- |
| `GET /api/v1/data-models` | 列出模型及发布/最新字段版本 |
| `POST /api/v1/data-models` | 创建模型，必须关联数据集 |
| `GET/PUT/DELETE /api/v1/data-models/{id}` | 读取、更新、删除 |
| `POST /api/v1/data-models/{id}/sync` | 从数据集读取零行字段元数据并创建快照（字段未变化时不创建新版本）；参数化数据集请求体传 `variables` |
| `GET /api/v1/data-models/{id}/schemas` | 查看字段快照历史 |
| `POST /api/v1/data-models/{id}/publish` | 发布当前字段快照版本 |

模型状态：

- `draft`：尚未发布。
- `published`：当前字段快照已发布。
- `drifted`：发布后检测到字段变化；新快照已经保留，但需要显式再次发布，避免下游看板静默变更。

同步只包装经过数据集校验的只读 SQL，并以 `WHERE 1=0` 读取字段元数据，不读取业务行数据。数据集的 `{{变量名}}` 同样使用 JDBC 参数绑定；变量缺失时同步会明确失败，不会尝试拼接 SQL。
