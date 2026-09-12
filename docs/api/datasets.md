# 数据集 API

数据集将一个已保存的数据源和一条只读 SQL 绑定为可复用的数据资产。它是看板、图表和后续数据模型的唯一业务数据入口；不能直接绑定协议驱动。

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

数据集创建和更新时都会校验 SQL；预览时还会再次施加默认 1000、最大 10000 行的行数上限，已有字面量 `LIMIT` 也会被收紧。SQL 中的 `{{变量名}}` 在预览、结构读取、字段血缘、Explain 和导出时都会转为 JDBC 参数，不会通过字符串拼接写入查询。Explain 仅包装该已保存的受限 SQL，不能注入任意命令。导出统一以最多 10,000 行 CSV 返回。

字段血缘由 JDBC `ResultSetMetaData` 返回的 catalog、schema、table 和 column 构成。普通表字段会标记为 `JDBC_METADATA`；表达式、聚合、跨库视图，或 JDBC 驱动未提供来源时会标记为 `DERIVED_OR_DRIVER_UNAVAILABLE`，不会伪造精确血缘。
