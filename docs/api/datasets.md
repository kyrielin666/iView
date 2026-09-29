# 数据集 API

数据集将一个已保存的数据源和一份受限查询定义绑定为可复用的数据资产。PostgreSQL 使用只读 SQL，MongoDB、HTTP 和文件源使用 JSON 查询定义。它是看板、图表和后续数据模型的统一业务数据入口。

| Endpoint | 说明 |
| --- | --- |
| `GET /api/v1/datasets?source_id=` | 列出数据集，可按数据源筛选 |
| `POST /api/v1/datasets` | 创建数据集 |
| `GET/PUT/DELETE /api/v1/datasets/{id}` | 读取、更新、删除 |
| `POST /api/v1/datasets/{id}/copy` | 复制数据集，请求体传入新 `name` |
| `PUT /api/v1/datasets/{id}/folder` | 移动到 `folder_id`；传 `null` 回到根目录 |
| `POST /api/v1/datasets/{id}/preview` | 用关联数据源执行受限预览查询；请求体可传 `max_rows`、`variables` |
| `POST /api/v1/datasets/{id}/query` | 按字段白名单执行投影、筛选、分组聚合、HAVING、排序和分页；请求体传 `fields`、`filters`、`group_by`、`aggregations`、`having`、`order_by`、`limit`、`offset`、`variables` |
| `POST /api/v1/datasets/{id}/schema` | 读取结果字段结构，不取业务数据；请求体传 `variables` |
| `POST /api/v1/datasets/{id}/lineage` | 读取字段级 JDBC 元数据血缘及 PostgreSQL `FROM/JOIN`、CTE 关系；请求体传 `variables` |
| `POST /api/v1/datasets/{id}/explain` | 读取 PostgreSQL JSON 执行计划；请求体传 `variables` |
| `POST /api/v1/datasets/{id}/export` | 执行受限 SQL 并下载最多 10,000 行 CSV；请求体传 `variables` |

数据集文件夹使用 `/api/v1/dataset-folders` 的 `GET/POST/PUT/DELETE`。文件夹可嵌套；非空文件夹（包含数据集或子文件夹）不可删除，以避免隐式移动或删除资产。

创建示例：

```json
{
  "source_id": 1,
  "name": "今日订单",
  "sql": "SELECT order_no, quantity, created_at FROM orders WHERE created_at >= CURRENT_DATE",
  "description": "看板订单趋势数据"
}
```

数据集创建和更新时都会按数据源类型校验查询定义；预览默认 1000 行、最大 10000 行。SQL 中的 `{{变量名}}` 会转为 JDBC 参数；JSON 查询先完成语法解析，再只替换字符串值中的变量，因此变量不能改变请求方法、字段结构或注入查询对象。Explain 仅适用于 PostgreSQL，其他类型返回明确的能力说明。导出统一以最多 10,000 行 CSV 返回。

字段血缘响应包含 `fields`、`nodes`、`edges`、`relations` 和汇总信息。PostgreSQL 的 `relations` 以启发式方式列出识别到的基础表与 CTE，不等于完整 SQL 语法解析；字段级关系优先使用 JDBC 元数据。复杂表达式、跨数据源关联和嵌套 JSON 不会伪造成数据库级精确血缘。

结构化查询的筛选操作包括 `equals`、`not_equals`、`greater_than`、`greater_than_or_equal`、`less_than`、`less_than_or_equal`、`in`、`not_in`、`like`、`is_null` 和 `is_not_null`。请求字段必须存在于实际结果结构中，最大返回 1,000 行。

PostgreSQL 会把投影、筛选、`group by`、`count/sum/avg/min/max`、`having`、排序、分页和总数统计包装在原始只读数据集 SQL 外层并下推执行；因此原始数据集 SQL 可以安全表达多表 `join`，可视化查询层继续在其结果字段白名单上工作。字段名、聚合函数和别名不会直接信任请求文本，值全部使用 JDBC 参数绑定。HTTP、文件和 MongoDB 使用最多 10,000 行的便携内存执行路径，并明确拒绝分组聚合请求，避免返回不完整的聚合结果。

聚合示例：

```json
{
  "filters": [{"field":"state","operator":"not_equals","value":"offline"}],
  "group_by": ["line"],
  "aggregations": [{"field":"quantity","function":"sum","alias":"total_quantity"}],
  "having": [{"field":"total_quantity","operator":"greater_than","value":"100"}],
  "order_by": [{"field":"total_quantity","direction":"DESC"}],
  "limit": 100,
  "offset": 0,
  "variables": {}
}
```
