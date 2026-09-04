# PostgreSQL 数据源 API

数据源用于数据集与看板读取业务数据。当前仅支持 PostgreSQL；设备协议、实时采集和数据看板运行时不会直接访问该连接。

## 凭据与安全边界

- `POST /api/v1/data-sources` 创建数据源，`PUT /api/v1/data-sources/{id}` 更新；新建必须传 `password`，更新不传则保留原密码。
- 密码仅在请求中使用，数据库存储 AES-GCM 密文；任何读取接口都不会返回密码或密文。
- 生产环境必须设置稳定的 `IVIEW_DATASOURCE_SECRET`。更换该值后，旧密文不可解密，需要重新录入相应密码。
- 已保存数据源的查询只接受一条无注释的 `SELECT` 或 `WITH ... SELECT`，禁止写入/DDL/多语句，未提供 `LIMIT` 时会追加上限（默认 1000，最大 10000）。这是一层防误用保护，不替代数据库账号的最小权限控制。

## 端点

| Endpoint | 说明 |
| --- | --- |
| `GET /api/v1/data-sources` | 列出数据源（不含密码） |
| `POST /api/v1/data-sources` | 创建 PostgreSQL 数据源 |
| `GET/PUT/DELETE /api/v1/data-sources/{id}` | 读取、更新、删除数据源 |
| `POST /api/v1/data-sources/{id}/test` | 用保存的凭据测试连接 |
| `GET /api/v1/data-sources/{id}/schemas` | 探测表和视图 |
| `GET /api/v1/data-sources/{id}/columns?schema=public&table=orders` | 探测列 |
| `POST /api/v1/data-sources/{id}/query` | 执行受限制的只读 SQL |

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
