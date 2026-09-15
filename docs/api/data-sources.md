# 数据源 API

数据源用于数据集与看板读取业务数据，支持 PostgreSQL、MongoDB、HTTP JSON，以及受限目录内的 CSV/JSON 文件。设备协议和实时采集不会直接暴露给看板查询。

## 凭据与安全边界

- `POST /api/v1/data-sources` 创建数据源，`PUT /api/v1/data-sources/{id}` 更新；PostgreSQL 新建必须传 `password`，其他类型按需提供，更新不传则保留原密码。
- 密码仅在请求中使用，数据库存储 AES-GCM 密文；任何读取接口都不会返回密码或密文。
- 生产环境必须设置稳定的 `IVIEW_DATASOURCE_SECRET`。更换该值后，旧密文不可解密，需要重新录入相应密码。
- PostgreSQL 只接受一条无注释的 `SELECT` 或 `WITH ... SELECT`，禁止写入/DDL/多语句，并限制结果行数。
- MongoDB 只接受 JSON 查询定义，支持 `database`、`collection`、`filter`、`projection`、`sort`、`limit`，不开放任意命令。
- HTTP 仅允许 GET/POST、同源相对 `path`、30 秒超时和最大 10MB JSON 响应；默认禁止回环和内网地址，可信工业内网可设置 `IVIEW_HTTP_ALLOW_PRIVATE=true`。
- 文件地址只能是 `IVIEW_FILE_SOURCE_ROOT` 下的相对路径，禁止目录穿越，仅接受最大 10MB 的 CSV/JSON。

## 端点

| Endpoint | 说明 |
| --- | --- |
| `GET /api/v1/data-sources` | 列出数据源（不含密码） |
| `POST /api/v1/data-sources` | 创建任一受支持的数据源 |
| `POST /api/v1/data-sources/files` | 上传 CSV/JSON 文件，返回受控相对路径 |
| `GET/PUT/DELETE /api/v1/data-sources/{id}` | 读取、更新、删除数据源 |
| `POST /api/v1/data-sources/{id}/test` | 用保存的凭据测试连接 |
| `GET /api/v1/data-sources/{id}/schemas` | 探测表和视图 |
| `GET /api/v1/data-sources/{id}/columns?schema=public&table=orders` | 探测列 |
| `POST /api/v1/data-sources/{id}/query` | 执行该类型的受限查询定义 |

创建请求示例：

```json
{
  "name": "生产 MES",
  "type": "postgresql",
  "jdbc_url": "jdbc:postgresql://db.internal:5432/mes",
  "username": "iview_reader",
  "password": "仅在创建或改密时提交",
  "description": "MES 只读数据源"
}
```

HTTP 数据集查询示例：

```json
{"path":"/records","method":"GET","query":{"line":"{{line}}"},"data_path":"items"}
```

MongoDB 数据集查询示例：

```json
{"database":"factory","collection":"telemetry","filter":{"line":"{{line}}"},"sort":{"timestamp":-1},"limit":1000}
```
