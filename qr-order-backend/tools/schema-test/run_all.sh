#!/usr/bin/env bash
# Chạy toàn bộ kiểm thử schema trên PostgreSQL 16 (mỗi migration trong 1 transaction như Flyway).
# Bộ kiểm thử nhanh, không cần JVM. Bộ kiểm thử chính thức là integration test JUnit + Testcontainers
# (./gradlew test); script này giữ lại để rà soát schema nhanh bằng psql.
# Dùng (từ thư mục qr-order-backend): PGHOST=/tmp PGPORT=5432 PGUSER=postgres bash tools/schema-test/run_all.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
P="psql -v ON_ERROR_STOP=1 -q"
V1=src/main/resources/db/migration/V1__init_schema.sql
V2=src/main/resources/db/seed-dev/V2__seed_dev.sql
V3=src/main/resources/db/migration/V3__add_refresh_token_and_item_history.sql
V4=src/main/resources/db/migration/V4__add_bank_transfer_settings.sql
DSN="host=${PGHOST:-/tmp} port=${PGPORT:-5432} user=${PGUSER:-postgres}"
fresh() { $P -d postgres -c "DROP DATABASE IF EXISTS $1" -c "CREATE DATABASE $1"; }
migrate() { local db=$1; shift; for f in "$@"; do $P -d "$db" --single-transaction -f "$f"; done; }

echo "== [1] Production: V1 -> V3 -> V4 (không seed)"
fresh qr_prod; migrate qr_prod $V1 $V3 $V4
$P -d qr_prod -Atc "SELECT 'bảng=' || count(*) FROM information_schema.tables WHERE table_schema='public'"

echo "== [2] Dev: V1 -> V2 -> V3 -> V4, chạy toàn bộ test"
fresh qr_test; migrate qr_test $V1 $V2 $V3 $V4
python3 tools/schema-test/test_schema.py "$DSN dbname=qr_test"

echo "== [3] Nâng cấp có dữ liệu: V1 -> V2 -> test (phần V1) -> V3, kiểm tra backfill lịch sử món"
fresh qr_upgrade; migrate qr_upgrade $V1 $V2
python3 tools/schema-test/test_schema.py "$DSN dbname=qr_upgrade" > /dev/null || true
migrate qr_upgrade $V3 $V4
$P -d qr_upgrade -Atc "SELECT CASE WHEN count(*) > 0 AND bool_and(n = 1) THEN 'PASS | BF-01 mỗi món cũ có đúng 1 dòng lịch sử backfill SYSTEM (' || count(*) || ' món)' ELSE 'FAIL | BF-01' END
  FROM (SELECT i.id, count(h.id) FILTER (WHERE h.changed_by_type='SYSTEM' AND h.to_status=i.status) n
          FROM order_item i LEFT JOIN order_item_status_history h ON h.order_item_id=i.id GROUP BY i.id) x"
