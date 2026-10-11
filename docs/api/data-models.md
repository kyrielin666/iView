# 数据模型 API

数据模型是数据集的版本化字段契约。看板可以绑定已发布模型，也可以显式绑定授权设备遥测；两者都不会直接配置协议驱动或任意数据库连接。

| Endpoint | 说明 |
| --- | --- |
| `GET /api/v1/data-models` | 列出模型及发布/最新字段版本 |
| `POST /api/v1/data-models` | 创建模型，必须关联数据集 |
| `GET/PUT/DELETE /api/v1/data-models/{id}` | 读取、更新、删除；看板草稿或发布快照仍依赖模型时拒绝删除 |
| `POST /api/v1/data-models/{id}/sync` | 从数据集读取零行字段元数据并创建快照（字段未变化时不创建新版本）；参数化数据集请求体传 `variables` |
| `GET /api/v1/data-models/{id}/schemas` | 查看字段快照历史 |
| `GET /api/v1/data-models/{id}/publish-plan` | 比较当前发布版本与最新字段版本，返回字段变化、破坏性变更、看板草稿与发布快照的依赖影响 |
| `POST /api/v1/data-models/{id}/publish` | 发布当前字段快照版本；请求体可传 `expected_schema_version` 防止确认后字段版本被并发更新；破坏性变更需 `confirm_breaking` |
| `GET /api/v1/data-models/{id}/rollback-plan?target_schema_version=v` | 预览回滚到指定字段版本的变化和依赖影响 |
| `POST /api/v1/data-models/{id}/rollback` | 回滚已发布字段版本；请求体含 `target_schema_version`、`expected_published_schema_version`，有依赖影响时需 `confirm_impact` |
| `POST /api/v1/data-models/{id}/heatmap` | 按已发布模型的 X/Y 维度分组、对数值字段求和；请求体含 `x_field`、`y_field`、`value_field` 和可选 `variables` |

模型状态：

- `draft`：尚未发布。
- `published`：当前字段快照已发布。
- `drifted`：发布后检测到字段变化；新快照已经保留，但需要显式再次发布，避免下游看板静默变更。

同步只包装经过数据集校验的只读 SQL，并以 `WHERE 1=0` 读取字段元数据，不读取业务行数据。数据集的 `{{变量名}}` 同样使用 JDBC 参数绑定；变量缺失时同步会明确失败，不会尝试拼接 SQL。

前端发布流程先读取发布计划。删除字段、修改字段类型、将可空字段收紧为非空会标记为破坏性变更并要求再次确认；提交时携带计划中的字段版本，若同步任务已生成更新版本，发布会被拒绝并要求重新检查。

依赖分析分别扫描看板当前草稿和已发布快照，使用各自保存的默认模型 ID，合并同一看板的组件和受影响字段，并在 `sources` 中标明 `draft` / `published`。直接绑定设备遥测的组件不算作数据模型依赖。无法解析任一文档时计划会失败，不会把未知影响误报为“无依赖”。该分析只定位显式字段引用和模型绑定，不保证发现表达式或跨数据源的语义依赖。
破坏性发布在提交前会重新计算依赖范围；字段快照版本仍由 `expected_schema_version` 做并发校验。当前没有对看板草稿/快照建立跨请求锁，运营时仍需在发布窗口复核计划与看板配置。

热力图聚合只允许使用已发布模型中两个维度字段和一个数值字段。PostgreSQL 数据集使用安全结构化查询在数据库端执行 `GROUP BY`/`SUM`，不会受普通看板预览的 100 行限制。其他数据源最多读取 10,000 行进行受控聚合；若触及该上限，接口拒绝返回可能不完整的总数。响应返回 `columns`、`rows`、`total_groups`、`truncated`，最多给出 400 个坐标组合；`truncated=true` 时前端必须明确提示。变量仍按数据集原有安全规则绑定。
CSV 等文本型数据源的同步结果可能把数值列标记为 `VARCHAR`；非 PostgreSQL 聚合会逐行安全解析数值，忽略空值和无效数值。PostgreSQL 仅接受已发布的数值类型，并在分组前剔除空维度与空数值。拥有同一模型 `POST .../{id}/preview` 精确权限的用户也可读取该模型的热力图聚合；不授予发布、编辑等额外权限。
