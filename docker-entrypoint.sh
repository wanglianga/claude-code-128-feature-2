#!/usr/bin/env bash
# 等待 PostgreSQL 可连通后再启动应用（compose 已用 healthcheck，这里兼容单独 docker run）。
set -euo pipefail

DB_HOST="${DB_HOST:-db}"
DB_PORT="${DB_PORT:-5432}"

echo "等待数据库 ${DB_HOST}:${DB_PORT} ..."
for i in $(seq 1 60); do
  if (exec 3<>"/dev/tcp/${DB_HOST}/${DB_PORT}") 2>/dev/null; then
    exec 3>&- 3<&- || true
    echo "数据库已就绪。"
    exec java ${JAVA_OPTS:-} -jar /app/app.jar
  fi
  sleep 2
done
echo "数据库 ${DB_HOST}:${DB_PORT} 等待超时" >&2
exit 1
