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
| `POST /api/v1/datasets/{id}/schema` | 读取结果字段结构，不取业务数据；请求体传 `variables` |
| `POST /api/v1/datasets/{id}/lineage` | 读取字段级 JDBC 元数据血缘；请求体传 `variables` |
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

字段血缘响应包含 `fields`、`nodes`、`edges` 和汇总信息。当前按实际查询结果结构与数据源建立字段来源关系；复杂表达式和嵌套 JSON 不会伪造成数据库级精确血缘。
