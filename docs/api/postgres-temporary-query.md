# PostgreSQL temporary query API

These endpoints accept PostgreSQL credentials only for the current request; iView does not persist
them in this slice.

- `POST /api/v1/data-sources/postgres/test`
- `POST /api/v1/data-sources/postgres/schemas`
- `POST /api/v1/data-sources/postgres/columns?schema=public&table=example`
- `POST /api/v1/data-sources/postgres/query`

Connection body uses `jdbc_url`, `username`, and `password`. Query bodies add `sql` and optional
`max_rows` (default 1000; maximum 10000). Only one comment-free `SELECT` or `WITH` statement is
accepted; write and DDL keywords are rejected and a missing `LIMIT` is appended.
