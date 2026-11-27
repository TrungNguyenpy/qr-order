"""
Kiểm thử schema QR Order (V1 + V2, và V3, V4 nếu đã áp dụng) trên PostgreSQL 16.
Mỗi case chạy trong một transaction riêng và COMMIT (để trigger DEFERRED chạy).
expect=None  -> phải thành công
expect="..." -> phải lỗi và thông báo lỗi chứa chuỗi này
Chạy: python3 tests/test_schema.py "host=/tmp port=5433 dbname=qr_test user=postgres"
"""
import sys
import threading
import uuid

import psycopg

DSN = sys.argv[1]
results = []


def run(case, sql, expect=None, params=None):
    with psycopg.connect(DSN) as conn:
        try:
            with conn.cursor() as cur:
                cur.execute(sql, params)
            conn.commit()
            ok = expect is None
            detail = "OK" if ok else f"mong lỗi '{expect}' nhưng thành công"
        except Exception as e:  # noqa: BLE001
            conn.rollback()
            msg = str(e).splitlines()[0]
            ok = expect is not None and expect in str(e)
            detail = msg if ok else f"lỗi không mong đợi: {msg}"
    results.append((case, ok, detail))


def q(sql, params=None):
    with psycopg.connect(DSN, autocommit=True) as conn:
        cur = conn.execute(sql, params)
        return cur.fetchall() if cur.description else []


def uid():
    return str(uuid.uuid4())


# --------------------------------------------------------------------------- fixture
q("""INSERT INTO users (username, password_hash, full_name, role) VALUES
     ('admin1','x','Admin 1','ADMIN'), ('admin2','x','Admin 2','ADMIN'),
     ('manager1','x','Manager 1','MANAGER'), ('staff1','x','Staff 1','STAFF'),
     ('staff2','x','Staff 2','STAFF'), ('kitchen1','x','Bếp 1','KITCHEN')""")
U = {r[0]: r[1] for r in q("SELECT username, id FROM users")}
T = {r[0]: r[1] for r in q("SELECT code, id FROM dining_table")}
PHO = q("SELECT id FROM product WHERE name='Phở bò tái'")[0][0]


def open_session(table_code):
    sid = q("INSERT INTO table_session (table_id, opened_by_type) VALUES (%s,'GUEST') RETURNING id",
            (T[table_code],))[0][0]
    oid = q("INSERT INTO orders (session_id) VALUES (%s) RETURNING id", (sid,))[0][0]
    return sid, oid


def add_batch(sid, oid, seq, status="ACCEPTED"):
    return q("""INSERT INTO order_batch (order_id, session_id, seq, client_request_id, status,
                requires_confirmation, submitted_by_type, device_id)
                VALUES (%s,%s,%s,%s,%s,false,'GUEST','device-0001') RETURNING id""",
             (oid, sid, seq, uid(), status))[0][0]


def add_item(oid, bid, price, qty=1, status="CONFIRMED"):
    return q("""INSERT INTO order_item (order_id, batch_id, product_id, product_name, unit_price,
                quantity, line_total, status, device_id, confirmed_at)
                VALUES (%s,%s,%s,'Phở bò tái',%s,%s,%s,%s,'device-0001',
                        CASE WHEN %s='CONFIRMED' THEN now() END) RETURNING id""",
             (oid, bid, PHO, price, qty, price * qty, status, status))[0][0]


def lock_and_bill(sid, oid, total, vat_rate="0.0800"):
    """Tính tiền: phiên BILLING, Order LOCKED, tạo Bill ACTIVE (giảm giá 0, phí 0)."""
    q("UPDATE table_session SET status='BILLING', billing_started_at=now() WHERE id=%s", (sid,))
    q("UPDATE orders SET status='LOCKED' WHERE id=%s", (oid,))
    vat = q("SELECT round(%s::numeric * %s::numeric / (1 + %s::numeric))::bigint", (total, vat_rate, vat_rate))[0][0]
    ps = "PAID" if total == 0 else "UNPAID"
    return q(f"""INSERT INTO bill (order_id, bill_number, payment_status, paid_at, subtotal_amount, net_amount,
                service_fee_rate, service_fee_amount, total_amount, vat_rate, vat_amount, created_by)
                VALUES (%s, NULL, %s, {'now()' if ps == 'PAID' else 'NULL'}, %s, %s, 0, 0, %s, %s, %s, %s)
                RETURNING id""", (oid, ps, total, total, total, vat_rate, vat, U['staff1']))[0][0]


def pay(bill_id, amount, new_payment_status, status="SUCCESS"):
    paid_at = "now()" if new_payment_status == "PAID" else "paid_at"
    return f"""INSERT INTO payment_transaction (bill_id, request_id, method, amount, status, created_by,
                   confirmed_by, confirmed_at)
               VALUES ({bill_id}, '{uid()}', 'CASH', {amount}, '{status}', {U['staff1']},
                       {'NULL' if status == 'PENDING' else U['staff1']},
                       {'NULL' if status == 'PENDING' else 'now()'});
               UPDATE bill SET payment_status='{new_payment_status}', paid_at={paid_at}, version=version+1
                WHERE id={bill_id};"""


def refund(bill_id, amount, status, ps=None):
    s = f"""INSERT INTO refund_transaction (bill_id, request_id, method, amount, status, reason, created_by,
                   confirmed_by, confirmed_at)
            VALUES ({bill_id}, '{uid()}', 'CASH', {amount}, '{status}', 'Hoàn tiền Void', {U['manager1']},
                    {'NULL' if status == 'PENDING' else U['manager1']},
                    {'NULL' if status == 'PENDING' else 'now()'});"""
    if ps:
        s += f"UPDATE bill SET payment_status='{ps}' WHERE id={bill_id};"
    return s


# --------------------------------------------------------------------------- 1. cấu hình
run("CFG-01 restaurant_settings chỉ 1 dòng", "INSERT INTO restaurant_settings (id) VALUES (2)", "ck_restaurant_settings_single_row")
run("CFG-02 không xóa restaurant_settings (BR-A03)", "DELETE FROM restaurant_settings", "HARD_DELETE_FORBIDDEN")
run("CFG-03 phí phục vụ > 0 khi service_fee_taxable NULL (BR-P04)",
    "UPDATE restaurant_settings SET service_fee_rate=0.05", "ck_restaurant_settings_service_fee_taxable")
run("CFG-04 phí phục vụ > 0 khi đã chốt taxable", "UPDATE restaurant_settings SET service_fee_rate=0.05, service_fee_taxable=true")
run("CFG-04b trả phí về 0 (giữ cấu hình chuẩn)", "UPDATE restaurant_settings SET service_fee_rate=0, service_fee_taxable=NULL")
run("CFG-05 vat_rate = 100% bị chặn (BR-P03)", "UPDATE restaurant_settings SET vat_rate=1", "ck_restaurant_settings_vat_rate")
run("CFG-06 prices_include_vat=false bị chặn (DEC-10)", "UPDATE restaurant_settings SET prices_include_vat=false", "ck_restaurant_settings_prices_include_vat")
run("CFG-07 order_confirmation_mode sai", "UPDATE restaurant_settings SET order_confirmation_mode='SOME'", "ck_restaurant_settings_confirmation_mode")

# --------------------------------------------------------------------------- 2. dãy số
r = q("SELECT fn_next_document_number('BILL'), fn_next_document_number('BILL'), "
      "fn_next_document_number('PAYMENT_RECEIPT'), fn_next_document_number('REFUND_RECEIPT')")[0]
today = q("SELECT to_char(now() AT TIME ZONE 'Asia/Ho_Chi_Minh','YYYYMMDD')")[0][0]
results.append(("SEQ-01 định dạng và dãy riêng (BR-P12)",
                 r == (f"BILL-{today}-0001", f"BILL-{today}-0002", f"{today}-0001", f"RF-{today}-0001"), str(r)))
run("SEQ-02 rollback không tiêu số", "SELECT fn_next_document_number('BILL'); SELECT 1/0", "division by zero")
r = q("SELECT fn_next_document_number('BILL')")[0][0]
results.append(("SEQ-02b số sau rollback liền mạch, không tái sử dụng", r == f"BILL-{today}-0003", r))
run("SEQ-03 dãy không được lùi", "UPDATE daily_sequence SET last_value=1 WHERE sequence_type='BILL'", "SEQUENCE_NOT_MONOTONIC")

# --------------------------------------------------------------------------- 3. user, RBAC
run("USR-01 username trùng không phân biệt hoa thường",
    "INSERT INTO users (username,password_hash,full_name,role) VALUES ('ADMIN1','x','x','STAFF')", "ux_users_username_lower")
run("USR-02 role ngoài enum", "INSERT INTO users (username,password_hash,full_name,role) VALUES ('u9','x','x','GUEST')", "ck_users_role")
run("USR-03 hạ role 1 trong 2 ADMIN", f"UPDATE users SET role='MANAGER' WHERE id={U['admin2']}")
run("USR-04 hạ role ADMIN cuối cùng (BR-A09)", f"UPDATE users SET role='MANAGER' WHERE id={U['admin1']}", "LAST_ACTIVE_ADMIN")
run("USR-05 khóa ADMIN cuối cùng (BR-A09)", f"UPDATE users SET status='LOCKED' WHERE id={U['admin1']}", "LAST_ACTIVE_ADMIN")
run("USR-05b khôi phục admin2 là ADMIN", f"UPDATE users SET role='ADMIN' WHERE id={U['admin2']}")

# Hai ADMIN hạ role lẫn nhau cùng lúc: chỉ một thành công (SRS §12.4 #6)
barrier = threading.Barrier(2)
outcome = []


def demote(target):
    with psycopg.connect(DSN) as c:
        try:
            c.execute("SELECT 1")
            barrier.wait()
            c.execute("UPDATE users SET role='MANAGER' WHERE id=%s", (target,))
            c.commit()
            outcome.append("ok")
        except Exception as e:  # noqa: BLE001
            c.rollback()
            outcome.append("LAST_ACTIVE_ADMIN" if "LAST_ACTIVE_ADMIN" in str(e) else str(e))


ths = [threading.Thread(target=demote, args=(U['admin1'],)), threading.Thread(target=demote, args=(U['admin2'],))]
[t.start() for t in ths]
[t.join() for t in ths]
active_admins = q("SELECT count(*) FROM users WHERE role='ADMIN' AND status='ACTIVE'")[0][0]
results.append(("USR-06 hai ADMIN hạ nhau đồng thời: 1 thành công, còn >= 1 ADMIN",
                 sorted(outcome) == ["LAST_ACTIVE_ADMIN", "ok"] and active_admins == 1, f"{outcome}, active={active_admins}"))
q("UPDATE users SET role='ADMIN' WHERE username IN ('admin1','admin2')")

run("USR-07 tự cấp permission không xác thực lại (BR-A07)",
    f"INSERT INTO user_permission (user_id, permission, granted_by, reason) VALUES ({U['admin1']},'REPORT_VIEW',{U['admin1']},'cần xem báo cáo')",
    "ck_user_permission_self_grant_reauth")
run("USR-08 tự cấp permission có xác thực lại",
    f"INSERT INTO user_permission (user_id, permission, granted_by, reason, reauthenticated) VALUES ({U['admin1']},'REPORT_VIEW',{U['admin1']},'cần xem báo cáo',true)")
run("USR-09 cấp trùng permission còn hiệu lực",
    f"INSERT INTO user_permission (user_id, permission, granted_by, reason) VALUES ({U['admin1']},'REPORT_VIEW',{U['admin2']},'x')",
    "ux_user_permission_active")
run("USR-10 cấp permission thiếu lý do",
    f"INSERT INTO user_permission (user_id, permission, granted_by, reason) VALUES ({U['staff1']},'PAYMENT_PROCESS',{U['admin1']},'  ')",
    "ck_user_permission_reason")
run("USR-11 permission ngoài 8 giá trị chuẩn",
    f"INSERT INTO user_permission (user_id, permission, granted_by, reason) VALUES ({U['staff1']},'ADMIN',{U['admin1']},'x')",
    "ck_user_permission_permission")
run("USR-12 thu hồi rồi cấp lại (giữ lịch sử)",
    f"""UPDATE user_permission SET revoked_at=now(), revoked_by={U['admin2']}, revoke_reason='hết nhu cầu' WHERE user_id={U['admin1']};
        INSERT INTO user_permission (user_id, permission, granted_by, reason) VALUES ({U['admin1']},'REPORT_VIEW',{U['admin2']},'cấp lại')""")
run("USR-13 sửa bản cấp đã thu hồi", f"UPDATE user_permission SET revoke_reason='khác' WHERE revoked_at IS NOT NULL", "PERMISSION_ALREADY_REVOKED")
run("USR-14 không xóa cứng user (BR-A03)", f"DELETE FROM users WHERE id={U['kitchen1']}", "HARD_DELETE_FORBIDDEN")
q(f"INSERT INTO user_permission (user_id, permission, granted_by, reason) VALUES ({U['staff1']},'PAYMENT_PROCESS',{U['admin1']},'thu ngân')")

# --------------------------------------------------------------------------- 4. menu
run("MNU-01 giá âm", "UPDATE product SET price=-1 WHERE name='Trà đá'", "ck_product_price")
run("MNU-02 trạng thái món sai", "UPDATE product SET status='OUT' WHERE name='Trà đá'", "ck_product_status")
run("MNU-03 nhóm SINGLE chọn nhiều", "UPDATE option_group SET max_select=2 WHERE name='Kích cỡ'", "ck_option_group_single")
run("MNU-04 nhóm bắt buộc min_select 0", "UPDATE option_group SET min_select=0 WHERE name='Kích cỡ'", "ck_option_group_required")
run("MNU-05 ảnh không phải https", "UPDATE product SET image_url='http://x' WHERE name='Trà đá'", "ck_product_image_url")
run("MNU-06 không xóa món", "DELETE FROM product WHERE name='Trà đá'", "HARD_DELETE_FORBIDDEN")

# --------------------------------------------------------------------------- 5. bàn, QR, phiên
run("TBL-01 hai QR ACTIVE một bàn (BR-S05)",
    f"INSERT INTO qr_code (table_id, token_hash) VALUES ({T['T01']}, repeat('a',64))", "ux_qr_code_active_per_table")
run("TBL-02 xoay QR: ROTATED rồi tạo mới",
    f"""UPDATE qr_code SET status='ROTATED', deactivated_at=now(), deactivated_by={U['admin1']} WHERE table_id={T['T01']} AND status='ACTIVE';
        INSERT INTO qr_code (table_id, token_hash, created_by) VALUES ({T['T01']}, repeat('b',64), {U['admin1']})""")
run("TBL-03 token_hash không phải SHA-256 hex", f"INSERT INTO qr_code (table_id, token_hash) VALUES ({T['T99']}, repeat('Z',64))", "ck_qr_code_token_hash")
s1, o1 = open_session("T02")
run("TBL-04 hai phiên OPEN/BILLING một bàn (BR-S01)",
    f"INSERT INTO table_session (table_id, opened_by_type) VALUES ({T['T02']},'GUEST')", "ux_table_session_active_per_table")
run("TBL-05 bàn đang OCCUPIED không được is_active=false (BR-S08)",
    f"UPDATE dining_table SET status='OCCUPIED' WHERE id={T['T02']}; UPDATE dining_table SET is_active=false WHERE id={T['T02']}",
    "ck_dining_table_inactive_only_available")
run("TBL-06 tạo phiên ở trạng thái BILLING", f"INSERT INTO table_session (table_id, opened_by_type, status) VALUES ({T['T03']},'GUEST','BILLING')", "INVALID_INITIAL_STATUS")
run("TBL-07 force-close thiếu lý do (BR-S06)",
    f"UPDATE table_session SET status='FORCE_CLOSED', closed_at=now(), closed_by={U['manager1']} WHERE id={s1}", "ck_table_session_force_close")
run("TBL-08 phiên OPEN -> CLOSED không qua BILLING", f"UPDATE table_session SET status='CLOSED', closed_at=now() WHERE id={s1}", "INVALID_SESSION_TRANSITION")
run("TBL-09 phiên STAFF mở phải có user", f"INSERT INTO table_session (table_id, opened_by_type) VALUES ({T['T04']},'STAFF')", "ck_table_session_opened_by")
run("TBL-10 thiết bị trùng trong phiên",
    f"INSERT INTO session_device (session_id, device_id) VALUES ({s1},'device-0001'),({s1},'device-0001')", "uq_session_device_session_device")

# --------------------------------------------------------------------------- 6. service call
run("SVC-01 CALL_STAFF đầu tiên",
    f"INSERT INTO service_call (session_id, type, created_by_type, created_by_device_id) VALUES ({s1},'CALL_STAFF','GUEST','device-0001')")
run("SVC-02 CALL_STAFF thứ hai khi còn mở (FR-NOT-03)",
    f"INSERT INTO service_call (session_id, type, created_by_type, created_by_device_id) VALUES ({s1},'CALL_STAFF','GUEST','device-0002')",
    "ux_service_call_open_per_type")
run("SVC-03 RESOLVED rồi gọi lại được",
    f"""UPDATE service_call SET status='RESOLVED', resolved_at=now(), resolved_by={U['staff1']} WHERE session_id={s1};
        INSERT INTO service_call (session_id, type, created_by_type, created_by_device_id) VALUES ({s1},'CALL_STAFF','GUEST','device-0001')""")
run("SVC-04 khách tạo MANAGER_SUPPORT",
    f"INSERT INTO service_call (session_id, type, created_by_type, created_by_device_id) VALUES ({s1},'MANAGER_SUPPORT','GUEST','device-0001')",
    "ck_service_call_creator")
run("SVC-05 ACKNOWLEDGED thiếu người nhận",
    f"UPDATE service_call SET status='ACKNOWLEDGED', acknowledged_at=now() WHERE session_id={s1} AND status='OPEN'", "ck_service_call_lifecycle")

# --------------------------------------------------------------------------- 7. order
b1 = add_batch(s1, o1, 1)
run("ORD-01 hai Order cho một phiên (DEC-02)", f"INSERT INTO orders (session_id) VALUES ({s1})", "uq_orders_session")
cr = uid()
run("ORD-02 gửi đợt đầu", f"""INSERT INTO order_batch (order_id, session_id, seq, client_request_id, requires_confirmation, submitted_by_type, device_id)
                               VALUES ({o1},{s1},2,'{cr}',true,'GUEST','device-0001')""")
run("ORD-03 trùng clientRequestId (BR-O04)", f"""INSERT INTO order_batch (order_id, session_id, seq, client_request_id, requires_confirmation, submitted_by_type, device_id)
                               VALUES ({o1},{s1},3,'{cr}',true,'GUEST','device-0001')""", "uq_order_batch_session_client_request")
s9, o9 = open_session("T05")
run("ORD-04 đợt có session_id không khớp Order", f"""INSERT INTO order_batch (order_id, session_id, seq, client_request_id, requires_confirmation, submitted_by_type, device_id)
                               VALUES ({o1},{s9},9,'{uid()}',true,'GUEST','device-0001')""", "fk_order_batch_order_session")
run("ORD-05 claim thiếu claimed_at", f"UPDATE order_batch SET claimed_by={U['staff1']} WHERE client_request_id='{cr}'", "ck_order_batch_claim")
run("ORD-06 từ chối cả đợt thiếu lý do", f"UPDATE order_batch SET status='REJECTED' WHERE client_request_id='{cr}'", "ck_order_batch_reject_reason")
run("ORD-07 line_total sai công thức", f"""INSERT INTO order_item (order_id,batch_id,product_id,product_name,unit_price,option_extra_per_unit,quantity,line_total,device_id)
                               VALUES ({o1},{b1},{PHO},'Phở',65000,10000,2,140000,'device-0001')""", "ck_order_item_line_total")
run("ORD-08 line_total đúng: (65.000 + 10.000) x 2", f"""INSERT INTO order_item (order_id,batch_id,product_id,product_name,unit_price,option_extra_per_unit,quantity,line_total,device_id)
                               VALUES ({o1},{b1},{PHO},'Phở',65000,10000,2,150000,'device-0001')""")
i1 = add_item(o1, b1, 65000, status="PENDING")
run("ORD-09 từ chối món thiếu reasonCode (BR-O08)", f"UPDATE order_item SET status='REJECTED', closed_at=now() WHERE id={i1}", "ck_order_item_reason")
run("ORD-10 PENDING -> READY nhảy cóc (§9.1)", f"UPDATE order_item SET status='READY', confirmed_at=now(), preparing_at=now(), ready_at=now() WHERE id={i1}", "INVALID_ORDER_ITEM_TRANSITION")
run("ORD-11 đổi giá snapshot (BR-O01)", f"UPDATE order_item SET unit_price=1, line_total=1 WHERE id={i1}", "ORDER_ITEM_SNAPSHOT_IMMUTABLE")
run("ORD-12 khách hủy món PENDING (BR-O05)", f"UPDATE order_item SET status='CANCELLED', reason_code='CUSTOMER_CANCELLED', closed_at=now() WHERE id={i1}")
run("ORD-13 sửa món đã CANCELLED", f"UPDATE order_item SET status='CONFIRMED', reason_code=NULL, closed_at=NULL, confirmed_at=now() WHERE id={i1}", "INVALID_ORDER_ITEM_TRANSITION")
i2 = add_item(o1, b1, 65000)
run("ORD-14 bếp PREPARING", f"UPDATE order_item SET status='PREPARING', preparing_at=now() WHERE id={i2}")
run("ORD-15 hủy món PREPARING không wasted (BR-O06)",
    f"UPDATE order_item SET status='CANCELLED', reason_code='CANCEL_APPROVED', closed_at=now() WHERE id={i2}", "ORDER_ITEM_WASTED_REQUIRED")
run("ORD-16 yêu cầu hủy món PREPARING",
    f"INSERT INTO item_cancel_request (order_item_id, item_status_at_request, requested_by_type, requested_by_device_id) VALUES ({i2},'PREPARING','GUEST','device-0001')")
run("ORD-17 hai yêu cầu hủy PENDING cùng món",
    f"INSERT INTO item_cancel_request (order_item_id, item_status_at_request, requested_by_type, requested_by_device_id) VALUES ({i2},'PREPARING','GUEST','device-0001')",
    "ux_item_cancel_request_pending")
run("ORD-18 Manager từ chối yêu cầu thiếu lý do",
    f"UPDATE item_cancel_request SET status='DENIED', decided_by={U['manager1']}, decided_at=now() WHERE order_item_id={i2}", "ck_item_cancel_request_deny_reason")
run("ORD-19 wasted với món chưa hủy/từ chối", f"UPDATE order_item SET wasted=true WHERE id={i2}", "ck_order_item_wasted")
run("ORD-20 món READY thiếu ready_at", f"UPDATE order_item SET status='READY' WHERE id={i2}", "ck_order_item_milestones")
q(f"UPDATE order_item SET status='READY', ready_at=now() WHERE id={i2}")
q(f"UPDATE order_batch SET status='ACCEPTED', decided_at=now(), decided_by={U['staff1']} WHERE client_request_id='{cr}'")
run("ORD-21 needs_attention khi Order OPEN (BR-P17)",
    f"UPDATE orders SET needs_attention=true, attention_reason='AWAITING_REPLACEMENT_BILL', attention_set_at=now() WHERE id={o1}", "ck_orders_attention")
run("ORD-22 Order mới tạo ở LOCKED", f"INSERT INTO orders (session_id, status) VALUES ({s9}, 'LOCKED')", "INVALID_INITIAL_STATUS")
run("ORD-23 Order OPEN -> PAID nhảy cóc", f"UPDATE orders SET status='PAID', paid_at=now() WHERE id={o1}", "INVALID_ORDER_TRANSITION")

# --------------------------------------------------------------------------- 8. Bill, công thức tiền
# Subtotal hiện tại o1 = 150.000 (ORD-08) + 65.000 (i2 READY) = 215.000
run("BIL-01 ví dụ SRS §8: Sub 220.000, Disc 20.000, r 10% -> VAT 18.182",
    f"""BEGIN; SET CONSTRAINTS ALL IMMEDIATE;
        INSERT INTO bill (order_id, bill_number, subtotal_amount, discount_amount, discount_reason, discounted_by, net_amount,
             service_fee_rate, service_fee_amount, total_amount, vat_rate, vat_amount, created_by)
        VALUES ({o9}, fn_next_document_number('BILL'), 220000, 20000, 'Khách quen', {U['manager1']}, 200000, 0, 0, 200000, 0.1, 18182, {U['staff1']});
        ROLLBACK;""")
run("BIL-02 VAT sai 1 đồng (18.181) bị chặn (BR-P02)",
    f"""INSERT INTO bill (order_id, subtotal_amount, net_amount, service_fee_rate, service_fee_amount, total_amount, vat_rate, vat_amount, created_by)
        VALUES ({o9}, 200000, 200000, 0, 0, 200000, 0.1, 18181, {U['staff1']})""", "ck_bill_vat_amount")
run("BIL-03 giảm giá vượt Subtotal (BR-P07)",
    f"""INSERT INTO bill (order_id, subtotal_amount, discount_amount, discount_reason, discounted_by, net_amount, service_fee_rate, service_fee_amount, total_amount, vat_rate, vat_amount, created_by)
        VALUES ({o9}, 100, 200, 'x', {U['manager1']}, -100, 0, 0, -100, 0.08, 0, {U['staff1']})""", "ck_bill")
run("BIL-04 giảm giá thiếu lý do",
    f"""INSERT INTO bill (order_id, subtotal_amount, discount_amount, net_amount, service_fee_rate, service_fee_amount, total_amount, vat_rate, vat_amount, created_by)
        VALUES ({o9}, 100000, 10000, 90000, 0, 0, 90000, 0.08, 6667, {U['staff1']})""", "ck_bill_discount_reason")
run("BIL-05 phí phục vụ 5% chịu thuế: Net 200.000 -> fee 10.000, Total 210.000, VAT 15.556",
    f"""BEGIN; INSERT INTO bill (order_id, subtotal_amount, net_amount, service_fee_rate, service_fee_taxable, service_fee_amount, total_amount, vat_rate, vat_amount, created_by)
        VALUES ({o9}, 200000, 200000, 0.05, true, 10000, 210000, 0.08, 15556, {U['staff1']}); ROLLBACK;""")
run("BIL-06 Bill có phí > 0 nhưng snapshot taxable NULL (BR-P04)",
    f"""INSERT INTO bill (order_id, subtotal_amount, net_amount, service_fee_rate, service_fee_amount, total_amount, vat_rate, vat_amount, created_by)
        VALUES ({o9}, 200000, 200000, 0.05, 10000, 210000, 0.08, 14815, {U['staff1']})""", "ck_bill_service_fee_taxable")

bill1 = lock_and_bill(s1, o1, 215000)
bn = q(f"SELECT bill_number FROM bill WHERE id={bill1}")[0][0]
results.append(("BIL-07 tự cấp mã BILL-YYYYMMDD-NNNN", bn.startswith(f"BILL-{today}-"), bn))
run("BIL-08 Bill ACTIVE thứ hai cho cùng Order (BR-P06)",
    f"""INSERT INTO bill (order_id, subtotal_amount, net_amount, service_fee_rate, service_fee_amount, total_amount, vat_rate, vat_amount, created_by)
        VALUES ({o1}, 215000, 215000, 0, 0, 215000, 0.08, 15926, {U['staff1']})""", "ux_bill_current_per_order")
run("BIL-09 sửa số tiền Bill tại chỗ (BR-P06)", f"UPDATE bill SET discount_amount=0, total_amount=1, net_amount=1, subtotal_amount=1, vat_amount=0 WHERE id={bill1}", "BILL_SNAPSHOT_IMMUTABLE")
run("BIL-10 Order LOCKED: hủy món bị chặn (BR-O12)",
    f"""UPDATE order_item SET status='CANCELLED', reason_code='CUSTOMER_CANCELLED', closed_at=now()
         WHERE order_id={o1} AND status='PENDING'""", "ORDER_NOT_OPEN")
run("BIL-11 Order LOCKED: chuyển tiến READY -> SERVED vẫn được", f"UPDATE order_item SET status='SERVED', served_at=now() WHERE id={i2}")

# --------------------------------------------------------------------------- 9. Payment
run("PAY-01 payment_status không khớp tiền thu (BR-P11, kiểm tra cuối transaction)",
    pay(bill1, 100000, "PAID"), "BILL_PAYMENT_STATUS_MISMATCH")
run("PAY-02 thu 100.000 -> PARTIALLY_PAID", pay(bill1, 100000, "PARTIALLY_PAID"))
rc = q(f"SELECT receipt_number FROM payment_transaction WHERE bill_id={bill1}")[0][0]
results.append(("PAY-03 mã phiếu YYYYMMDD-NNNN cấp khi SUCCESS", rc.startswith(f"{today}-"), rc))
h = q("SELECT from_status, to_status FROM transaction_status_history WHERE transaction_type='PAYMENT'")
results.append(("PAY-04 lịch sử trạng thái tự ghi", h == [(None, "SUCCESS")], str(h)))
run("PAY-05 thu vượt phần còn thiếu (BR-P10)", pay(bill1, 115001, "PAID"), "PAYMENT_EXCEEDS_REMAINING")
run("PAY-06 sửa số tiền giao dịch SUCCESS", f"UPDATE payment_transaction SET amount=1 WHERE bill_id={bill1}", "TRANSACTION_IMMUTABLE")
run("PAY-07 không xóa giao dịch", f"DELETE FROM payment_transaction WHERE bill_id={bill1}", "HARD_DELETE_FORBIDDEN")
run("PAY-08 PENDING không có mã phiếu, không tính tiền", pay(bill1, 50000, "PARTIALLY_PAID", status="PENDING"))
run("PAY-09 Void khi còn Payment PENDING (BR-P09)",
    f"UPDATE bill SET status='VOID_PENDING', void_requested_by={U['manager1']}, void_requested_at=now(), void_reason='sai món' WHERE id={bill1}",
    "PENDING_PAYMENT_EXISTS")
run("PAY-10 PENDING -> FAILED cần lý do",
    f"UPDATE payment_transaction SET status='FAILED', confirmed_at=now() WHERE bill_id={bill1} AND status='PENDING'", "ck_payment_transaction_failure")
run("PAY-11 PENDING -> FAILED",
    f"UPDATE payment_transaction SET status='FAILED', confirmed_at=now(), confirmed_by={U['staff1']}, failure_reason='POS lỗi' WHERE bill_id={bill1} AND status='PENDING'")
run("PAY-12 FAILED -> SUCCESS bị chặn (bất biến)",
    f"UPDATE payment_transaction SET status='SUCCESS' WHERE bill_id={bill1} AND status='FAILED'", "TRANSACTION_IMMUTABLE")
run("PAY-13 thu đủ 115.000 -> PAID", pay(bill1, 115000, "PAID") + f"UPDATE orders SET status='PAID', paid_at=now() WHERE id={o1};")

# Hai thu ngân thu cùng lúc trên một Bill: không thể vượt Total
s3, o3 = open_session("T06")
b3 = add_batch(s3, o3, 1)
add_item(o3, b3, 100000)
bill3 = lock_and_bill(s3, o3, 100000)
barrier2 = threading.Barrier(2)
out2 = []


def concurrent_pay():
    with psycopg.connect(DSN) as c:
        try:
            barrier2.wait()
            c.execute(pay(bill3, 60000, "PARTIALLY_PAID"))
            c.commit()
            out2.append("ok")
        except Exception as e:  # noqa: BLE001
            c.rollback()
            out2.append("EXCEEDS" if "PAYMENT_EXCEEDS_REMAINING" in str(e) else str(e)[:80])


ths = [threading.Thread(target=concurrent_pay) for _ in range(2)]
[t.start() for t in ths]
[t.join() for t in ths]
tps3 = q(f"SELECT COALESCE(sum(amount),0) FROM payment_transaction WHERE bill_id={bill3} AND status='SUCCESS'")[0][0]
results.append(("PAY-14 hai lần thu đồng thời 60.000 trên Bill 100.000: chỉ 1 thành công",
                 sorted(out2) == ["EXCEEDS", "ok"] and tps3 == 60000, f"{out2}, TPS={tps3}"))

# --------------------------------------------------------------------------- 10. Void, Refund (SRS §12.1, §12.3)
run("RF-01 Refund trên Bill ACTIVE (BR-P13 (4))", refund(bill1, 1000, "PENDING"), "REFUND_REQUIRES_VOID_PENDING")
run("VOID-01 Manager yêu cầu Void Bill PAID 215.000: VOID_PENDING, Order PAID -> LOCKED",
    f"""UPDATE bill SET status='VOID_PENDING', void_requested_by={U['manager1']}, void_requested_at=now(), void_reason='Sai món' WHERE id={bill1};
        UPDATE orders SET status='LOCKED', status_before_void='PAID' WHERE id={o1};""")
run("VOID-02 thu thêm khi VOID_PENDING", pay(bill1, 1, "PAID"), "PAYMENT_BILL_NOT_ACTIVE")
run("RF-02 (§12.1 #1) hoàn tối đa TPS: 215.001 bị chặn", refund(bill1, 215001, "PENDING"), "REFUND_EXCEEDS_REFUNDABLE")
run("RF-03 Refund SUCCESS 100.000 (TRS=100.000)", refund(bill1, 100000, "SUCCESS", ps="PARTIALLY_PAID"))
rf = q(f"SELECT receipt_number FROM refund_transaction WHERE bill_id={bill1} AND status='SUCCESS'")[0][0]
results.append(("RF-04 mã phiếu RF-YYYYMMDD-NNNN khi SUCCESS", rf.startswith(f"RF-{today}-"), rf))
run("RF-05 (§12.1 #2) remaining 115.000: yêu cầu 115.001 bị chặn", refund(bill1, 115001, "PENDING"), "REFUND_EXCEEDS_REFUNDABLE")
run("VOID-03 rút Void khi đã có Refund SUCCESS (BR-P14)",
    f"""UPDATE bill SET status='ACTIVE', void_withdrawn_by={U['manager1']}, void_withdrawn_at=now(), void_withdraw_reason='nhầm' WHERE id={bill1};""",
    "REFUND_ONLY_FOR_VOID")
run("RF-06 (§12.1 #3) Refund PENDING 115.000 -> TRP=115.000, remaining 0", refund(bill1, 115000, "PENDING"))
run("RF-07 (§12.1 #3) không hoàn thêm 1 đồng khi remaining 0", refund(bill1, 1, "PENDING"), "REFUND_EXCEEDS_REFUNDABLE")
run("VOID-04 hoàn tất Void khi còn Refund PENDING (BR-P13 (5))",
    f"UPDATE bill SET status='VOIDED', voided_at=now(), voided_by={U['manager1']}, void_type='MANAGER_VOID' WHERE id={bill1}", "VOID_NOT_COMPLETE")
run("RF-08 (§12.1 #6) Refund PENDING -> FAILED, không tính TRS/TRP",
    f"""UPDATE refund_transaction SET status='FAILED', confirmed_at=now(), confirmed_by={U['manager1']}, failure_reason='Khách không nhận'
         WHERE bill_id={bill1} AND status='PENDING'""")
run("RF-09 (§12.1 #5) Refund mới 115.000 SUCCESS + hoàn tất Void + Order needs_attention (BR-P17)",
    refund(bill1, 115000, "SUCCESS", ps="UNPAID") +
    f"""UPDATE bill SET status='VOIDED', voided_at=now(), voided_by={U['manager1']}, void_type='MANAGER_VOID' WHERE id={bill1};
        UPDATE orders SET needs_attention=true, attention_reason='AWAITING_REPLACEMENT_BILL', attention_set_at=now() WHERE id={o1};""")
run("VOID-05 sửa Bill VOIDED (trạng thái cuối)", f"UPDATE bill SET void_reason='khác' WHERE id={bill1}", "BILL_FINAL_STATE")
run("VOID-06 Refund trên Bill đã VOIDED", refund(bill1, 1, "PENDING"), "REFUND_EXCEEDS_REFUNDABLE")
h = q(f"""SELECT count(*) FROM transaction_status_history h JOIN refund_transaction r ON r.id = h.refund_transaction_id
         WHERE r.bill_id={bill1}""")[0][0]
results.append(("RF-10 lịch sử Refund: 3 lần tạo + 1 lần FAILED = 4 dòng", h == 4, str(h)))
run("VOID-07 Bill thay thế (BR-P16) thuộc cùng Order, xóa needs_attention",
    f"""INSERT INTO bill (order_id, replaces_bill_id, subtotal_amount, net_amount, service_fee_rate, service_fee_amount, total_amount, vat_rate, vat_amount, created_by)
        VALUES ({o1}, {bill1}, 215000, 215000, 0, 0, 215000, 0.08, 15926, {U['manager1']});
        UPDATE orders SET needs_attention=false, attention_reason=NULL, attention_set_at=NULL WHERE id={o1};""")
run("VOID-08 Bill thay thế trỏ Bill của Order khác",
    f"""INSERT INTO bill (order_id, replaces_bill_id, subtotal_amount, net_amount, service_fee_rate, service_fee_amount, total_amount, vat_rate, vat_amount, created_by)
        VALUES ({o9}, {bill1}, 1000, 1000, 0, 0, 1000, 0.08, 74, {U['manager1']})""", "fk_bill_replaces")

# §12.1 #4: TRS 200.000, TRP 200.000 trên TPS 500.000 -> còn hoàn tối đa 100.000
s4, o4 = open_session("T07")
b4 = add_batch(s4, o4, 1)
add_item(o4, b4, 500000)
bill4 = lock_and_bill(s4, o4, 500000)
q(pay(bill4, 500000, "PAID"))
q(f"""UPDATE bill SET status='VOID_PENDING', void_requested_by={U['manager1']}, void_requested_at=now(), void_reason='Sai' WHERE id={bill4};
      UPDATE orders SET status='LOCKED', status_before_void='LOCKED' WHERE id={o4};""")
q(refund(bill4, 200000, "SUCCESS", ps="PARTIALLY_PAID"))
q(refund(bill4, 200000, "PENDING"))
run("RF-11 (§12.1 #4) TRS 200k, TRP 200k: hoàn thêm 100.001 bị chặn", refund(bill4, 100001, "PENDING"), "REFUND_EXCEEDS_REFUNDABLE")
run("RF-12 (§12.1 #4) TRS 200k, TRP 200k: hoàn thêm 100.000 được", refund(bill4, 100000, "PENDING"))

# Rút Void khi chỉ có Refund FAILED (§12.3 #5)
s5, o5 = open_session("T08")
b5 = add_batch(s5, o5, 1)
add_item(o5, b5, 80000)
bill5 = lock_and_bill(s5, o5, 80000)
q(pay(bill5, 80000, "PAID") + f"UPDATE orders SET status='PAID', paid_at=now() WHERE id={o5};")
q(f"""UPDATE bill SET status='VOID_PENDING', void_requested_by={U['manager1']}, void_requested_at=now(), void_reason='Sai' WHERE id={bill5};
      UPDATE orders SET status='LOCKED', status_before_void='PAID' WHERE id={o5};""")
q(refund(bill5, 80000, "PENDING"))
q(f"""UPDATE refund_transaction SET status='FAILED', confirmed_at=now(), confirmed_by={U['manager1']}, failure_reason='Hủy' WHERE bill_id={bill5}""")
run("VOID-09 (§12.3 #5) rút Void khi chỉ có Refund FAILED: Bill ACTIVE, Order về PAID",
    f"""UPDATE bill SET status='ACTIVE', void_withdrawn_by={U['manager1']}, void_withdrawn_at=now(), void_withdraw_reason='Khách đồng ý' WHERE id={bill5};
        UPDATE orders SET status='PAID' WHERE id={o5};""")

# Staff mở lại phiên khi Bill chưa có Payment SUCCESS (BR-P08 a, §12.3 #1)
run("REOPEN-01 Staff mở lại phiên: Bill VOIDED (SESSION_REOPEN), Order OPEN, phiên OPEN",
    f"""UPDATE bill SET status='VOIDED', voided_at=now(), voided_by={U['staff1']}, void_type='SESSION_REOPEN' WHERE id={bill3};""",
    "VOID_NOT_COMPLETE")  # Bill3 đã có 60.000 SUCCESS -> phải chặn
s6, o6 = open_session("T09")
b6 = add_batch(s6, o6, 1)
add_item(o6, b6, 50000)
bill6 = lock_and_bill(s6, o6, 50000)
run("REOPEN-02 Bill chưa có tiền: mở lại phiên thành công",
    f"""UPDATE bill SET status='VOIDED', voided_at=now(), voided_by={U['staff1']}, void_type='SESSION_REOPEN' WHERE id={bill6};
        UPDATE orders SET status='OPEN' WHERE id={o6};
        UPDATE table_session SET status='OPEN' WHERE id={s6};""")

# --------------------------------------------------------------------------- 11. Force-close, thất thu (§12.2)
run("FC-01 (§12.2 #3) Bill 100.000 đã thu 60.000: WRITTEN_OFF, shortfall 40.000",
    f"""UPDATE bill SET status='WRITTEN_OFF', written_off_at=now() WHERE id={bill3};
        UPDATE orders SET status='WRITTEN_OFF', shortfall_amount=40000, writeoff_reason='Khách bỏ đi',
               written_off_by={U['manager1']}, written_off_at=now() WHERE id={o3};
        UPDATE table_session SET status='FORCE_CLOSED', closed_at=now(), closed_by={U['manager1']}, close_reason='Khách bỏ đi' WHERE id={s3};""")
run("FC-02 (§12.2 #9) thu thêm sau WRITTEN_OFF", pay(bill3, 1000, "PARTIALLY_PAID"), "PAYMENT_BILL_NOT_ACTIVE")
run("FC-03 (§12.2 #9) Refund sau WRITTEN_OFF", refund(bill3, 1000, "PENDING"), "REFUND_REQUIRES_VOID_PENDING")
run("FC-04 (§12.2 #9) đổi trạng thái Bill WRITTEN_OFF", f"UPDATE bill SET status='ACTIVE' WHERE id={bill3}", "BILL_FINAL_STATE")
run("FC-05 (§12.2 #9) đổi trạng thái Order WRITTEN_OFF", f"UPDATE orders SET writeoff_reason='x' WHERE id={o3}", "ORDER_FINAL_STATE")
run("FC-06 WRITTEN_OFF với shortfall 0", f"""UPDATE orders SET status='WRITTEN_OFF', shortfall_amount=0, writeoff_reason='x',
        written_off_by={U['manager1']}, written_off_at=now() WHERE id={o9}""", "ck_orders_writeoff")
run("FC-07 (§12.2 #1) Order OPEN chưa có Bill, món 100.000: WRITTEN_OFF không cần Bill",
    f"""UPDATE orders SET status='WRITTEN_OFF', shortfall_amount=100000, writeoff_reason='Khách bỏ đi',
            written_off_by={U['manager1']}, written_off_at=now() WHERE id={o9};
        UPDATE table_session SET status='FORCE_CLOSED', closed_at=now(), closed_by={U['manager1']}, close_reason='Khách bỏ đi' WHERE id={s9};""")
run("FC-08 phiên FORCE_CLOSED là trạng thái cuối", f"UPDATE table_session SET status='OPEN' WHERE id={s9}", "SESSION_FINAL_STATE")
run("FC-09 (§12.2 #6) force-close khi Bill VOID_PENDING (Bill thành WRITTEN_OFF)",
    f"UPDATE bill SET status='WRITTEN_OFF', written_off_at=now() WHERE id={bill4}", "INVALID_BILL_TRANSITION")

# --------------------------------------------------------------------------- 12. Audit
run("AUD-01 ghi audit đổi role có xác thực lại",
    f"""INSERT INTO audit_log (actor_type, actor_user_id, action, entity_type, entity_id, old_value, new_value, reason, reauthenticated)
        VALUES ('USER', {U['admin1']}, 'USER_ROLE_CHANGED', 'users', {U['manager1']}, '{{"role":"MANAGER"}}', '{{"role":"ADMIN"}}', 'Bổ nhiệm', true)""")
run("AUD-02 đổi role không xác thực lại (BR-A08)",
    f"""INSERT INTO audit_log (actor_type, actor_user_id, action, entity_type, entity_id, reason)
        VALUES ('USER', {U['admin1']}, 'USER_ROLE_CHANGED', 'users', {U['manager1']}, 'x')""", "ck_audit_log_role_change")
run("AUD-03 force-close audit thiếu lý do",
    f"""INSERT INTO audit_log (actor_type, actor_user_id, action, entity_type, entity_id)
        VALUES ('USER', {U['manager1']}, 'SESSION_FORCE_CLOSED', 'table_session', {s3})""", "ck_audit_log_reason_required")
run("AUD-04 sửa audit_log (bất biến)", "UPDATE audit_log SET reason='x'", "APPEND_ONLY")
run("AUD-05 xóa audit_log", "DELETE FROM audit_log", "APPEND_ONLY")
run("AUD-06 sửa lịch sử giao dịch", "UPDATE transaction_status_history SET reason='x'", "APPEND_ONLY")
run("DEL-01 không xóa Order (BR-A03)", f"DELETE FROM orders WHERE id={o1}", "HARD_DELETE_FORBIDDEN")
run("DEL-02 không xóa Bill (BR-A03)", f"DELETE FROM bill WHERE id={bill1}", "HARD_DELETE_FORBIDDEN")

# --------------------------------------------------------------------------- 14. V3: refresh_token (DB-01)
HAS_V3 = bool(q("SELECT to_regclass('public.refresh_token')")[0][0])
if HAS_V3:
    def hx(seed):
        return q("SELECT encode(sha256(convert_to(%s,'UTF8')),'hex')", (seed,))[0][0]

    def new_token(user, fam, seed, hours=24):
        return q("""INSERT INTO refresh_token (user_id, family_id, token_hash, expires_at)
                    VALUES (%s,%s,%s, now() + make_interval(hours => %s)) RETURNING id""",
                 (user, fam, hx(seed), hours))[0][0]

    def rotate_sql(old_id, user, fam, seed):
        return f"""UPDATE refresh_token SET revoked_at=now(), revoke_reason='ROTATED' WHERE id={old_id} AND revoked_at IS NULL;
                   WITH n AS (INSERT INTO refresh_token (user_id, family_id, token_hash, expires_at)
                              VALUES ({user}, '{fam}', '{hx(seed)}', now() + interval '7 days') RETURNING id)
                   UPDATE refresh_token SET replaced_by_id = (SELECT id FROM n) WHERE id={old_id};"""

    F1 = uid()
    t1 = new_token(U['staff1'], F1, "rt-1")
    run("RT-01 token đầu family hợp lệ", "SELECT 1")
    run("RT-02 hai token chưa thu hồi cùng family",
        f"INSERT INTO refresh_token (user_id, family_id, token_hash, expires_at) VALUES ({U['staff1']}, '{F1}', '{hx('rt-x')}', now() + interval '1 day')",
        "ux_refresh_token_family_active")
    run("RT-03 rotation 3 bước trong 1 transaction (FR-AUTH-02)", rotate_sql(t1, U['staff1'], F1, "rt-2"))
    chain = q(f"SELECT revoke_reason, replaced_by_id IS NOT NULL FROM refresh_token WHERE id={t1}")[0]
    head = q(f"SELECT count(*) FROM refresh_token WHERE family_id='{F1}' AND revoked_at IS NULL")[0][0]
    results.append(("RT-04 sau rotation: token cũ ROTATED trỏ token mới, family còn đúng 1 token hiệu lực",
                    chain == ("ROTATED", True) and head == 1, f"{chain}, head={head}"))
    t2 = q(f"SELECT id FROM refresh_token WHERE family_id='{F1}' AND revoked_at IS NULL")[0][0]
    run("RT-05 rotation bỏ dở (không gắn token kế tiếp)",
        f"""UPDATE refresh_token SET revoked_at=now(), revoke_reason='ROTATED' WHERE id={t2};
            INSERT INTO refresh_token (user_id, family_id, token_hash, expires_at) VALUES ({U['staff1']}, '{F1}', '{hx('rt-3')}', now() + interval '1 day')""",
        "REFRESH_TOKEN_ROTATION_INCOMPLETE")
    F2 = uid()
    t9 = new_token(U['staff1'], F2, "rt-other")
    run("RT-06 token kế tiếp thuộc family khác",
        f"UPDATE refresh_token SET revoked_at=now(), revoke_reason='ROTATED', replaced_by_id={t9} WHERE id={t2}",
        "fk_refresh_token_replaced_by")
    run("RT-07 bỏ thu hồi token đã ROTATED", f"UPDATE refresh_token SET revoked_at=NULL, revoke_reason=NULL, replaced_by_id=NULL WHERE id={t1}",
        "REFRESH_TOKEN_ALREADY_REVOKED")
    run("RT-08 đổi token_hash", f"UPDATE refresh_token SET token_hash='{hx('zz')}' WHERE id={t2}", "REFRESH_TOKEN_IMMUTABLE")
    run("RT-09 expires_at trước issued_at",
        f"INSERT INTO refresh_token (user_id, family_id, token_hash, expires_at) VALUES ({U['staff1']}, '{uid()}', '{hx('rt-e')}', now() - interval '1 hour')",
        "ck_refresh_token_expiry")
    run("RT-10 thu hồi thiếu lý do", f"UPDATE refresh_token SET revoked_at=now() WHERE id={t2}", "ck_refresh_token_revoked")
    run("RT-11 lý do thu hồi ngoài danh mục", f"UPDATE refresh_token SET revoked_at=now(), revoke_reason='EXPIRED' WHERE id={t2}", "ck_refresh_token_revoke_reason")
    run("RT-12 trùng token_hash", f"INSERT INTO refresh_token (user_id, family_id, token_hash, expires_at) VALUES ({U['staff2']}, '{uid()}', '{hx('rt-1')}', now() + interval '1 day')",
        "uq_refresh_token_hash")
    run("RT-13 replaced_by_id khi lý do không phải ROTATED",
        f"UPDATE refresh_token SET revoked_at=now(), revoke_reason='LOGOUT', replaced_by_id={t1} WHERE id={t9}", "ck_refresh_token_rotation")

    # Hai request refresh đồng thời dùng cùng 1 token: chỉ một bên rotate được (bên kia = reuse)
    F3 = uid()
    t3 = new_token(U['staff2'], F3, "rt-race")
    barrier3 = threading.Barrier(2)
    out3 = []

    def concurrent_refresh(k):
        with psycopg.connect(DSN) as c:
            barrier3.wait()
            n = c.execute(f"UPDATE refresh_token SET revoked_at=now(), revoke_reason='ROTATED' WHERE id={t3} AND revoked_at IS NULL").rowcount
            if n == 1:
                nid = c.execute(f"""INSERT INTO refresh_token (user_id, family_id, token_hash, expires_at)
                                    VALUES ({U['staff2']}, '{F3}', '{hx('rt-race-' + str(k))}', now() + interval '1 day') RETURNING id""").fetchone()[0]
                c.execute(f"UPDATE refresh_token SET replaced_by_id={nid} WHERE id={t3}")
            c.commit()
            out3.append(n)

    ths = [threading.Thread(target=concurrent_refresh, args=(k,)) for k in range(2)]
    [t.start() for t in ths]
    [t.join() for t in ths]
    heads = q(f"SELECT count(*) FROM refresh_token WHERE family_id='{F3}' AND revoked_at IS NULL")[0][0]
    results.append(("RT-14 hai refresh đồng thời cùng token: 1 rotate, 1 nhận 0 dòng (reuse)",
                    sorted(out3) == [0, 1] and heads == 1, f"rowcounts={out3}, heads={heads}"))

    new_token(U['staff2'], uid(), "rt-lock-a")
    new_token(U['staff2'], uid(), "rt-lock-b")
    run("RT-15 khóa tài khoản (BR-A04)", f"UPDATE users SET status='LOCKED' WHERE id={U['staff2']}")
    r = q(f"SELECT count(*) FILTER (WHERE revoked_at IS NULL), count(*) FILTER (WHERE revoke_reason='USER_LOCKED') FROM refresh_token WHERE user_id={U['staff2']}")[0]
    results.append(("RT-16 khóa tài khoản thu hồi ngay mọi token còn hiệu lực (USER_LOCKED)", r == (0, 3), str(r)))
    q(f"UPDATE users SET status='ACTIVE' WHERE id={U['staff2']}")
    new_token(U['kitchen1'], uid(), "rt-role")
    run("RT-17 đổi role", f"UPDATE users SET role='STAFF' WHERE id={U['kitchen1']}")
    r = q(f"SELECT revoke_reason FROM refresh_token WHERE user_id={U['kitchen1']}")
    results.append(("RT-18 đổi role thu hồi token (ROLE_CHANGED)", r == [("ROLE_CHANGED",)], str(r)))
    run("RT-19 dọn theo cả family (Scheduler) được phép", f"DELETE FROM refresh_token WHERE family_id='{F3}'")
    run("RT-20 xóa token đang được token khác trỏ tới", f"DELETE FROM refresh_token WHERE id={t2}", "fk_refresh_token_replaced_by")

    # ----------------------------------------------------------------------- 15. V3: order_item_status_history (DB-02)
    s7, o7 = open_session("T10")
    b7 = add_batch(s7, o7, 1, status="PENDING")
    it = add_item(o7, b7, 30000, status="PENDING")

    def hist(item):
        return q(f"""SELECT from_status, to_status, changed_by_type, changed_by_user_id, device_id, reason_code
                     FROM order_item_status_history WHERE order_item_id={item} ORDER BY id""")

    results.append(("OH-01 tạo món ghi NULL -> PENDING (GUEST, device)",
                    hist(it) == [(None, "PENDING", "GUEST", None, "device-0001", None)], str(hist(it))))
    run("OH-02 Staff xác nhận",
        f"""UPDATE order_item SET status='CONFIRMED', confirmed_at=now(), status_changed_by={U['staff1']},
                   status_changed_by_type='STAFF', status_changed_by_device_id=NULL WHERE id={it}""")
    run("OH-03 Bếp PREPARING", f"UPDATE order_item SET status='PREPARING', preparing_at=now(), status_changed_by={U['kitchen1']}, status_changed_by_type='STAFF' WHERE id={it}")
    run("OH-04 Bếp READY", f"UPDATE order_item SET status='READY', ready_at=now(), status_changed_by={U['kitchen1']}, status_changed_by_type='STAFF' WHERE id={it}")
    run("OH-05 Staff SERVED", f"UPDATE order_item SET status='SERVED', served_at=now(), status_changed_by={U['staff1']}, status_changed_by_type='STAFF' WHERE id={it}")
    h = hist(it)
    exp = [(None, "PENDING", "GUEST", None, "device-0001", None),
           ("PENDING", "CONFIRMED", "STAFF", U['staff1'], None, None),
           ("CONFIRMED", "PREPARING", "STAFF", U['kitchen1'], None, None),
           ("PREPARING", "READY", "STAFF", U['kitchen1'], None, None),
           ("READY", "SERVED", "STAFF", U['staff1'], None, None)]
    results.append(("OH-06 đủ 5 lần chuyển, đúng người và thứ tự (BR-O09)", h == exp, str(h)))
    run("OH-07 sửa cột khác status không ghi lịch sử", f"UPDATE order_item SET version=version+1 WHERE id={it}")
    results.append(("OH-07b số dòng lịch sử vẫn là 5", len(hist(it)) == 5, str(len(hist(it)))))
    run("OH-08 chuyển sai (SERVED -> CANCELLED) bị chặn, không ghi lịch sử",
        f"UPDATE order_item SET status='CANCELLED', reason_code='X1', closed_at=now() WHERE id={it}", "INVALID_ORDER_ITEM_TRANSITION")
    results.append(("OH-08b lịch sử không đổi sau lỗi", len(hist(it)) == 5, str(len(hist(it)))))
    ig = add_item(o7, b7, 20000, status="PENDING")
    run("OH-09 khách hủy món (GUEST, thiết bị khác người đặt)",
        f"""UPDATE order_item SET status='CANCELLED', reason_code='CUSTOMER_CANCELLED', closed_at=now(),
                   status_changed_by_type='GUEST', status_changed_by_device_id='device-0002' WHERE id={ig}""")
    results.append(("OH-09b lịch sử ghi GUEST, device-0002, reasonCode",
                    hist(ig)[-1] == ("PENDING", "CANCELLED", "GUEST", None, "device-0002", "CUSTOMER_CANCELLED"), str(hist(ig)[-1])))
    isys = add_item(o7, b7, 20000, status="PENDING")
    run("OH-10 hệ thống tự REJECTED khi SOLD_OUT (BR-O13), không có người",
        f"UPDATE order_item SET status='REJECTED', reason_code='OUT_OF_STOCK', closed_at=now(), status_changed_by_type='SYSTEM' WHERE id={isys}")
    results.append(("OH-10b lịch sử ghi SYSTEM, OUT_OF_STOCK", hist(isys)[-1][:3] == ("PENDING", "REJECTED", "SYSTEM")
                    and hist(isys)[-1][5] == "OUT_OF_STOCK", str(hist(isys)[-1])))
    i5 = add_item(o7, b7, 20000, status="PENDING")
    run("OH-11 khai báo STAFF nhưng thiếu user",
        f"UPDATE order_item SET status='CONFIRMED', confirmed_at=now(), status_changed_by_type='STAFF' WHERE id={i5}",
        "ck_order_item_changed_by_actor")
    run("OH-12 khai báo GUEST nhưng thiếu thiết bị",
        f"UPDATE order_item SET status='CANCELLED', reason_code='CUSTOMER_CANCELLED', closed_at=now(), status_changed_by_type='GUEST' WHERE id={i5}",
        "ck_order_item_changed_by_actor")
    run("OH-13 sửa lịch sử món", "UPDATE order_item_status_history SET reason_code='X'", "APPEND_ONLY")
    run("OH-14 xóa lịch sử món", "DELETE FROM order_item_status_history", "APPEND_ONLY")
    istaff = q(f"""INSERT INTO order_item (order_id, batch_id, product_id, product_name, unit_price, quantity, line_total, created_by_user_id)
                  VALUES ({o7},{b7},{PHO},'Phở',65000,1,65000,{U['staff1']}) RETURNING id""")[0][0]
    results.append(("OH-15 Staff order hộ: lịch sử tạo ghi STAFF", hist(istaff) == [(None, "PENDING", "STAFF", U['staff1'], None, None)], str(hist(istaff))))

# --------------------------------------------------------------------------- 13. Index dùng đúng (NFR-01)
plans = {
    "KDS": "SELECT id FROM order_item WHERE status IN ('CONFIRMED','PREPARING') ORDER BY confirmed_at",
    "Đợt chờ xác nhận": "SELECT id FROM order_batch WHERE status='PENDING' ORDER BY submitted_at",
    "Doanh thu ngày": "SELECT sum(amount) FROM payment_transaction WHERE status='SUCCESS' AND confirmed_at >= now() - interval '1 day'",
    "Phiên active của bàn": f"SELECT id FROM table_session WHERE table_id={T['T02']} AND status IN ('OPEN','BILLING')",
}
if HAS_V3:
    plans["Thu hồi token theo user"] = f"SELECT id FROM refresh_token WHERE user_id={U['staff1']} AND revoked_at IS NULL"
    plans["Lịch sử món"] = "SELECT * FROM order_item_status_history WHERE order_item_id=1 ORDER BY changed_at"
with psycopg.connect(DSN) as c:
    c.execute("SET enable_seqscan = off")
    for name, sql in plans.items():
        plan = "\n".join(r[0] for r in c.execute("EXPLAIN " + sql).fetchall())
        idx = [w for w in plan.replace("(", " ").split() if w.startswith(("ix_", "ux_"))]
        results.append((f"IDX {name} dùng index", bool(idx), ", ".join(sorted(set(idx))) or plan.splitlines()[0]))

# --------------------------------------------------------------------------- 16. V4: tài khoản ngân hàng VietQR
HAS_V4 = bool(q("SELECT 1 FROM information_schema.columns WHERE table_name='restaurant_settings' AND column_name='bank_bin'"))
if HAS_V4:
    S = "UPDATE restaurant_settings SET "
    run("V4: mặc định chưa cấu hình ngân hàng (cả ba NULL)",
        "SELECT 1 FROM restaurant_settings WHERE bank_bin IS NULL AND bank_account_no IS NULL AND bank_account_name IS NULL")
    run("V4: đủ ba trường hợp lệ được lưu",
        S + "bank_bin='970436', bank_account_no='0011001234567', bank_account_name='NGUYEN VAN A'")
    run("V4: thiếu một trường bị chặn (all-or-none)", S + "bank_account_no = NULL", "ck_restaurant_settings_bank_all_or_none")
    run("V4: BIN không đủ 6 chữ số bị chặn", S + "bank_bin='9704'", "ck_restaurant_settings_bank_bin")
    run("V4: BIN có chữ cái bị chặn", S + "bank_bin='97043a'", "ck_restaurant_settings_bank_bin")
    run("V4: số tài khoản < 6 chữ số bị chặn", S + "bank_account_no='12345'", "ck_restaurant_settings_bank_account_no")
    run("V4: số tài khoản > 19 chữ số bị chặn", S + "bank_account_no='12345678901234567890'", "value too long")
    run("V4: số tài khoản có ký tự lạ bị chặn", S + "bank_account_no='0011-0012'", "ck_restaurant_settings_bank_account_no")
    run("V4: tên chủ tài khoản rỗng bị chặn", S + "bank_account_name='   '", "ck_restaurant_settings_bank_account_name")
    run("V4: xóa cấu hình (cả ba NULL) được phép",
        S + "bank_bin=NULL, bank_account_no=NULL, bank_account_name=NULL")

# --------------------------------------------------------------------------- report
passed = sum(1 for _, ok, _ in results if ok)
for case, ok, detail in results:
    print(f"{'PASS' if ok else 'FAIL'} | {case} | {detail}")
print(f"\nTổng: {passed}/{len(results)} PASS")
sys.exit(0 if passed == len(results) else 1)
