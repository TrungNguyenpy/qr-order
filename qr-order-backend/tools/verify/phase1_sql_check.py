"""Kiểm chứng tại chỗ các câu SQL mà mã Giai đoạn 1 và test sinh ra, trên PostgreSQL 16 thật (V1 -> V2 -> V3)."""
import sys, psycopg, json
dsn = sys.argv[1]
results = []
def case(name, fn, expect_error=None):
    conn = psycopg.connect(dsn, autocommit=False)
    try:
        out = fn(conn)
        conn.commit()
        ok = expect_error is None
        detail = "" if out is None else str(out)
        if not ok: detail = f"mong đợi lỗi {expect_error} nhưng thành công"
    except Exception as e:
        conn.rollback()
        msg = str(e).splitlines()[0]
        ok = expect_error is not None and expect_error in msg
        detail = msg
    finally:
        conn.close()
    results.append(ok)
    print(("PASS" if ok else "FAIL"), "|", name, "|", detail[:150])

def q(conn, sql, *a):
    cur = conn.execute(sql, a); return cur

def setup(conn):
    q(conn, "INSERT INTO users (username,password_hash,full_name,role) VALUES ('admin1','{bcrypt}x','A','ADMIN'),('thu_ngan','{bcrypt}x','Thu ngân','STAFF'),('ql_1','{bcrypt}x','QL','MANAGER')")
case("SETUP users", setup)
uid = lambda name: psycopg.connect(dsn).execute("SELECT id FROM users WHERE username=%s",(name,)).fetchone()[0]
A, S, M = uid('admin1'), uid('thu_ngan'), uid('ql_1')

def touch(conn):
    v0, u0 = q(conn, "SELECT version, updated_at FROM users WHERE id=%s", A).fetchone()
    n = q(conn, "UPDATE users SET last_login_at = now() WHERE id = %s", A).rowcount
    v1, u1, ll = q(conn, "SELECT version, updated_at, last_login_at FROM users WHERE id=%s", A).fetchone()
    assert n == 1 and v0 == v1 and u0 == u1 and ll is not None, (n, v0, v1)
    return "1 dòng, version/updated_at không đổi"
case("P1-01 touchLastLogin", touch)

def grant_revoke(conn):
    gid = q(conn, "INSERT INTO user_permission (user_id, permission, granted_by, reason, reauthenticated) VALUES (%s,'PAYMENT_PROCESS',%s,'Ca tối',false) RETURNING id", S, A).fetchone()[0]
    conn.commit()
    sql = "UPDATE user_permission SET revoked_at = now(), revoked_by = %s, revoke_reason = %s WHERE id = %s AND revoked_at IS NULL"
    n1 = q(conn, sql, A, 'Hết ca', gid).rowcount; conn.commit()
    n2 = q(conn, sql, A, 'Lần hai', gid).rowcount
    assert (n1, n2) == (1, 0), (n1, n2)
    return "lần 1: 1 dòng, lần 2: 0 dòng (không lỗi trigger)"
case("P1-02 revoke permission có điều kiện", grant_revoke)
case("P1-03 cấp lại sau thu hồi", lambda c: q(c, "INSERT INTO user_permission (user_id, permission, granted_by, reason) VALUES (%s,'PAYMENT_PROCESS',%s,'Ca sáng')", S, A) and None)
case("P1-04 trùng bản cấp hiệu lực", lambda c: q(c, "INSERT INTO user_permission (user_id, permission, granted_by, reason) VALUES (%s,'PAYMENT_PROCESS',%s,'x')", S, A), "ux_user_permission_active")
case("P1-05 tự cấp không xác thực lại", lambda c: q(c, "INSERT INTO user_permission (user_id, permission, granted_by, reason, reauthenticated) VALUES (%s,'REPORT_VIEW',%s,'x',false)", A, A), "ck_user_permission_self_grant_reauth")
case("P1-06 tự cấp có xác thực lại", lambda c: q(c, "INSERT INTO user_permission (user_id, permission, granted_by, reason, reauthenticated) VALUES (%s,'REPORT_VIEW',%s,'x',true)", A, A) and None)
case("P1-07 revoke cùng transaction với cấp (now() bằng nhau, CHECK revoked_at >= granted_at)", lambda c: (
    q(c, "INSERT INTO user_permission (user_id, permission, granted_by, reason) VALUES (%s,'BILL_VOID',%s,'x') RETURNING id", S, A),
    q(c, "UPDATE user_permission SET revoked_at = now(), revoked_by=%s, revoke_reason='y' WHERE user_id=%s AND permission='BILL_VOID' AND revoked_at IS NULL", A, S)) and None)

def audit_full(conn):
    rid = q(conn, """INSERT INTO audit_log (actor_type, actor_user_id, action, entity_type, entity_id, old_value, new_value, reason,
                 reauthenticated, correlation_id, ip_address) VALUES ('USER',%s,'USER_ROLE_CHANGED','users',%s,%s::jsonb,%s::jsonb,'Bổ nhiệm',true,'corr-1','203.0.113.7'::inet) RETURNING id""",
        A, M, json.dumps({"role": "MANAGER"}), json.dumps({"role": "ADMIN"})).fetchone()[0]
    row = q(conn, """SELECT actor_type, old_value::text, new_value::text, host(ip_address), correlation_id FROM audit_log WHERE id=%s""", rid).fetchone()
    assert row[3] == '203.0.113.7' and json.loads(row[2])["role"] == "ADMIN", row
    return row
case("P1-08 audit đủ 11 trường + truy vấn auditRows (host(ip), ::text)", audit_full)
case("P1-09 audit SYSTEM không actor_user_id", lambda c: q(c, "INSERT INTO audit_log (actor_type, action, entity_type, entity_id, new_value, reason) VALUES ('SYSTEM','USER_CREATED','users',1,'{\"role\":\"ADMIN\"}','Khởi tạo')") and None)
case("P1-10 USER_ROLE_CHANGED thiếu xác thực lại", lambda c: q(c, "INSERT INTO audit_log (actor_type, actor_user_id, action, entity_type, reason) VALUES ('USER',%s,'USER_ROLE_CHANGED','users','x')", A), "ck_audit_log_role_change")
case("P1-11 PERMISSION_REVOKED thiếu lý do", lambda c: q(c, "INSERT INTO audit_log (actor_type, actor_user_id, action, entity_type) VALUES ('USER',%s,'PERMISSION_REVOKED','user_permission')", A), "ck_audit_log_reason_required")
case("P1-12 SETTINGS_CHANGED/USER_LOCKED/USER_PASSWORD_RESET không cần lý do", lambda c: [q(c, "INSERT INTO audit_log (actor_type, actor_user_id, action, entity_type, entity_id) VALUES ('USER',%s,%s,%s,1)", A, a, e) for a, e in [("SETTINGS_CHANGED","restaurant_settings"),("USER_LOCKED","users"),("USER_DISABLED","users"),("USER_PASSWORD_RESET","users"),("USER_PASSWORD_CHANGED","users"),("USER_UPDATED","users")]] and None)
case("P1-13 advisory lock InitialAdminRunner", lambda c: q(c, "SELECT pg_advisory_xact_lock(hashtext('qr_order.initial_admin'))") and None)
case("P1-14 ADMIN đầu tiên created_by NULL", lambda c: q(c, "INSERT INTO users (username,password_hash,full_name,role) VALUES ('root_admin','{bcrypt}x','Root','ADMIN')") and None)

def search(conn):
    rows = q(conn, r"SELECT username FROM users WHERE lower(username) LIKE %s ESCAPE '\' OR lower(full_name) LIKE %s ESCAPE '\'", r"%thu\_%", r"%thu\_%").fetchall()
    none = q(conn, r"SELECT count(*) FROM users WHERE lower(username) LIKE %s ESCAPE '\'", r"%\%%").fetchone()[0]
    assert [r[0] for r in rows] == ['thu_ngan'] and none == 0, (rows, none)
    return "thu\\_ -> thu_ngan; \\% -> 0 dòng"
case("P1-15 tìm kiếm user LIKE ESCAPE", search)
case("P1-16 đếm ADMIN ACTIVE", lambda c: q(c, "SELECT count(*) FROM users WHERE role='ADMIN' AND status='ACTIVE'").fetchone())

def settings_ok(conn):
    v = q(conn, "SELECT version FROM restaurant_settings WHERE id=1").fetchone()[0]
    n = q(conn, "UPDATE restaurant_settings SET vat_rate=0.1000, service_fee_rate=0.0500, service_fee_taxable=true, updated_by=%s, updated_at=now(), version=version+1 WHERE id=1 AND version=%s", A, v).rowcount
    stale = q(conn, "UPDATE restaurant_settings SET max_item_quantity=20, version=version+1 WHERE id=1 AND version=%s", v).rowcount
    assert (n, stale) == (1, 0)
    return f"version {v}->{v+1}; ghi theo version cũ: 0 dòng (optimistic lock)"
case("P1-17 cập nhật settings + @Version", settings_ok)
case("P1-18 BR-P04 hàng rào DB", lambda c: q(c, "UPDATE restaurant_settings SET service_fee_rate=0.05, service_fee_taxable=NULL WHERE id=1"), "ck_restaurant_settings_service_fee_taxable")
case("P1-19 VAT = 1 bị CHECK chặn", lambda c: q(c, "UPDATE restaurant_settings SET vat_rate=1 WHERE id=1"), "ck_restaurant_settings_vat_rate")
case("P1-20 VAT NULL (chưa cấu hình) hợp lệ", lambda c: q(c, "UPDATE restaurant_settings SET vat_rate=NULL WHERE id=1") and None)
case("P1-21 refresh token hết hạn chèn trực tiếp (fixture test)", lambda c: q(c, "INSERT INTO refresh_token (user_id, family_id, token_hash, issued_at, expires_at) VALUES (%s, gen_random_uuid(), repeat('a',64), now() - interval '10 days', now() - interval '3 days')", S) and None)

def trigger_revoke(conn):
    q(conn, "INSERT INTO refresh_token (user_id, family_id, token_hash, expires_at) VALUES (%s, gen_random_uuid(), repeat('b',64), now() + interval '7 days')", M)
    conn.commit()
    q(conn, "UPDATE users SET role='ADMIN', version=version+1 WHERE id=%s", M)
    r = q(conn, "SELECT revoke_reason FROM refresh_token WHERE user_id=%s", M).fetchall()
    assert r == [('ROLE_CHANGED',)], r
    return r
case("P1-22 đổi role (UPDATE kiểu Hibernate) -> trigger thu hồi ROLE_CHANGED", trigger_revoke)

def cleaner(conn):
    q(conn, "SET LOCAL session_replication_role = replica")
    for t in ["refresh_token","user_permission","audit_log","transaction_status_history","refund_transaction","payment_transaction","bill","order_item_status_history","order_item_option","item_cancel_request","order_item","order_batch","orders","service_call","session_device","table_session","daily_sequence","users"]:
        q(conn, f"DELETE FROM {t}")
    q(conn, "UPDATE dining_table SET status = 'AVAILABLE', status_changed_at = now()")
    q(conn, """UPDATE restaurant_settings SET vat_rate = 0.0800, service_fee_rate = 0, service_fee_taxable = NULL,
                       session_idle_timeout_minutes = 180, guest_jwt_ttl_minutes = 15, batch_claim_ttl_seconds = 60,
                       max_pending_batches_per_session = 3, join_rate_limit_per_minute = 10,
                       order_rate_limit_per_minute = 5, max_item_quantity = 99,
                       order_confirmation_mode = 'FIRST_BATCH_ONLY', require_table_pin = false,
                       cleaning_auto_release = false, updated_by = NULL, version = 1
                 WHERE id = 1""")
case("P1-23 DbCleaner (xóa + đặt lại settings khi updated_by trỏ user bị xóa)", cleaner)
case("P1-24 sau DbCleaner: settings = seed", lambda c: q(c, "SELECT vat_rate, service_fee_rate, updated_by, version FROM restaurant_settings").fetchone())
case("P1-25 sau DbCleaner: ghi settings với updated_by mới (FK)", lambda c: (q(c, "INSERT INTO users (username,password_hash,full_name,role) VALUES ('admin2','x','A','ADMIN')"), q(c, "UPDATE restaurant_settings SET updated_by=(SELECT id FROM users WHERE username='admin2'), version=version+1 WHERE id=1")) and None)

print(f"\nTổng: {sum(results)}/{len(results)} PASS")
sys.exit(0 if all(results) else 1)
