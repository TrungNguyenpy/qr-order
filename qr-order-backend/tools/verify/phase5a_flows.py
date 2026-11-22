"""
Cách chạy: pip install psycopg2-binary; PGHOST=... PGDATABASE=... python3 phase5a_flows.py (database dùng riêng để thử).

GĐ5a - kiểm chứng ở mức SQL trên PostgreSQL 16 thật (schema V1 + seed V2 + V3, không sửa schema):
mô phỏng đúng chuỗi lệnh mà các service GĐ5a phát ra (theo thứ tự flush của Hibernate, cập nhật mọi cột updatable của
entity) và COMMIT thật để trigger DEFERRABLE trg_bill_invariants chạy. Mỗi kịch bản in PASS/FAIL.
"""
import os
import sys
import threading
import time
import uuid

import psycopg2

# Database đã chạy V1 -> V2 -> V3 (đường dev). Cấu hình qua biến môi trường PGHOST, PGPORT, PGUSER, PGPASSWORD, PGDATABASE.
DSN = dict(host=os.environ.get('PGHOST', 'localhost'), port=int(os.environ.get('PGPORT', '5432')),
           user=os.environ.get('PGUSER', 'postgres'), password=os.environ.get('PGPASSWORD'), dbname=os.environ.get('PGDATABASE', 'qr_order'))
results = []


def conn():
    c = psycopg2.connect(**DSN)
    c.autocommit = False
    return c


def check(name, cond, detail=''):
    results.append((name, bool(cond)))
    print(('PASS ' if cond else 'FAIL ') + name + (f'  [{detail}]' if detail and not cond else ''))


def expect_error(name, code, fn):
    c = conn()
    try:
        fn(c.cursor())
        c.commit()
        check(name, False, 'không có lỗi')
    except psycopg2.Error as e:
        msg = (e.pgerror or str(e)).strip()
        check(name, code in msg, msg.splitlines()[0])
    finally:
        c.rollback()
        c.close()


def one(cur, sql, *a):
    cur.execute(sql, a)
    return cur.fetchone()[0]


# ------------------------------------------------------------------ dữ liệu nền
c = conn(); cur = c.cursor()
cur.execute("DELETE FROM users WHERE username LIKE 'v5a_%'")  # không có ràng buộc nào tới user mới (DB mới)
cashier = one(cur, "INSERT INTO users (username,password_hash,full_name,role) VALUES ('v5a_cashier','x','Thu ngân','STAFF') RETURNING id")
manager = one(cur, "INSERT INTO users (username,password_hash,full_name,role) VALUES ('v5a_manager','x','Quản lý','MANAGER') RETURNING id")
pho = one(cur, "SELECT id FROM product WHERE name = 'Phở bò tái'")
c.commit()


def open_table(code, items=((65000, 2, 'SERVED'), (29000, 1, 'CONFIRMED'))):
    """Phiên OPEN + Order OPEN + đợt ACCEPTED + món theo trạng thái. Trả (table_id, session_id, order_id, [item ids])."""
    c = conn(); cur = c.cursor()
    tid = one(cur, "SELECT id FROM dining_table WHERE code = %s", code)
    cur.execute("UPDATE dining_table SET status = 'OCCUPIED' WHERE id = %s", (tid,))
    sid = one(cur, "INSERT INTO table_session (table_id, opened_by_type) VALUES (%s,'GUEST') RETURNING id", tid)
    oid = one(cur, "INSERT INTO orders (session_id) VALUES (%s) RETURNING id", sid)
    bid = one(cur, """INSERT INTO order_batch (order_id, session_id, seq, client_request_id, status, requires_confirmation,
                      submitted_by_type, device_id) VALUES (%s,%s,1,%s,'ACCEPTED',false,'GUEST','device-0001') RETURNING id""",
              oid, sid, str(uuid.uuid4()))
    ids = []
    for price, qty, st in items:
        iid = one(cur, """INSERT INTO order_item (order_id, batch_id, product_id, product_name, unit_price, quantity, line_total,
                          status, device_id, confirmed_at) VALUES (%s,%s,%s,'Món',%s,%s,%s,'CONFIRMED','device-0001',now()) RETURNING id""",
                  oid, bid, pho, price, qty, price * qty)
        for nxt, col in (('PREPARING', 'preparing_at'), ('READY', 'ready_at'), ('SERVED', 'served_at')):
            if st == 'CONFIRMED' or (st == 'PREPARING' and nxt != 'PREPARING') or (st == 'READY' and nxt == 'SERVED'):
                break
            cur.execute(f"UPDATE order_item SET status = %s, {col} = now(), status_changed_by = %s, status_changed_by_type = 'STAFF' WHERE id = %s",
                        (nxt, cashier, iid))
        ids.append(iid)
    c.commit(); c.close()
    return tid, sid, oid, ids


ORDER_UPDATE = """UPDATE orders SET status=%s, status_before_void=NULL, needs_attention=false, attention_reason=NULL,
    attention_set_at=NULL, shortfall_amount=%s, writeoff_reason=%s, written_off_by=%s, written_off_at=%s, paid_at=%s,
    voided_at=NULL, updated_at=now(), version=version+1 WHERE id=%s"""

BILL_INSERT = """INSERT INTO bill (order_id, subtotal_amount, discount_amount, net_amount, service_fee_rate, service_fee_amount,
    total_amount, vat_rate, vat_amount, created_by, status, payment_status, discount_reason, discounted_by, service_fee_taxable,
    replaces_bill_id, paid_at, void_type, void_requested_by, void_requested_at, void_reason, voided_by, voided_at,
    void_withdrawn_by, void_withdrawn_at, void_withdraw_reason, written_off_at, updated_at, version)
    VALUES (%(order)s, %(sub)s, %(disc)s, %(net)s, %(sfr)s, %(sf)s, %(tot)s, 0.0800, %(vat)s, %(by)s, 'ACTIVE', %(ps)s, %(reason)s,
            %(dby)s, NULL, %(repl)s, %(paid)s, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, now(), 0)
    RETURNING id, bill_number"""

# Cập nhật Bill như Hibernate: mọi cột updatable (cột snapshot có updatable = false nên không nằm trong SET).
BILL_UPDATE = """UPDATE bill SET status=%(status)s, payment_status=%(ps)s, paid_at=%(paid)s, void_type=%(vt)s,
    void_requested_by=%(vrb)s, void_requested_at=%(vra)s, void_reason=%(vr)s, voided_by=%(vb)s, voided_at=%(va)s,
    void_withdrawn_by=NULL, void_withdrawn_at=NULL, void_withdraw_reason=NULL, written_off_at=%(wo)s, updated_at=now(),
    version=version+1 WHERE id=%(id)s"""


def bill_update(cur, bill_id, **kw):
    cur.execute("SELECT status, payment_status, paid_at, void_type, void_requested_by, void_requested_at, void_reason, voided_by, voided_at, written_off_at FROM bill WHERE id=%s", (bill_id,))
    r = cur.fetchone()
    p = dict(status=r[0], ps=r[1], paid=r[2], vt=r[3], vrb=r[4], vra=r[5], vr=r[6], vb=r[7], va=r[8], wo=r[9], id=bill_id)
    p.update(kw)
    cur.execute(BILL_UPDATE, p)


def vat_of(cur, amount):
    return one(cur, "SELECT round(%s::numeric * 0.08 / 1.08)::bigint", amount)


def create_bill(cur, tid, sid, oid, sub, disc=0, by=None, reason=None, repl=None):
    """BillService.create: khóa bàn -> phiên; Order LOCKED (flush); chèn Bill; phiên BILLING; bàn PAYMENT_PENDING."""
    cur.execute("SELECT id FROM dining_table WHERE id=%s FOR UPDATE", (tid,))
    cur.execute("SELECT id FROM table_session WHERE id=%s FOR UPDATE", (sid,))
    cur.execute(ORDER_UPDATE, ('LOCKED', None, None, None, None, None, oid))
    net = sub - disc
    total = net
    row = None
    cur.execute(BILL_INSERT, dict(order=oid, sub=sub, disc=disc, net=net, sfr=0, sf=0, tot=total, vat=vat_of(cur, net),
                                  by=by or cashier, ps='PAID' if total == 0 else 'UNPAID', reason=reason if disc else None,
                                  dby=(by or cashier) if disc else None, repl=repl, paid='now' if total == 0 else None))
    row = cur.fetchone()
    cur.execute("UPDATE table_session SET status='BILLING', billing_started_at=now(), last_activity_at=now(), version=version+1 WHERE id=%s", (sid,))
    cur.execute("UPDATE dining_table SET status='PAYMENT_PENDING', status_changed_at=now(), version=version+1 WHERE id=%s", (tid,))
    return row


def insert_payment(cur, bill_id, amount, status, method='CASH', provider=None, txn=None):
    cur.execute("""INSERT INTO payment_transaction (bill_id, request_id, method, amount, status, created_by, provider,
                   provider_txn_id, note, failure_reason, confirmed_by, confirmed_at)
                   VALUES (%s,%s,%s,%s,%s,%s,%s,%s,NULL,NULL,%s,%s) RETURNING id, receipt_number""",
                (bill_id, str(uuid.uuid4()), method, amount, status, cashier, provider, txn,
                 cashier if status == 'SUCCESS' else None, 'now' if status == 'SUCCESS' else None))
    return cur.fetchone()


def money(cur, bill_id):
    cur.execute("""SELECT coalesce(sum(amount) FILTER (WHERE status='SUCCESS'),0), coalesce(sum(amount) FILTER (WHERE status='PENDING'),0)
                   FROM payment_transaction WHERE bill_id=%s""", (bill_id,))
    return cur.fetchone()


def apply_success(cur, tid, sid, oid, bill_id, total):
    """PaymentService.applySuccess + SessionSettlementService.closeIfSettled."""
    tps, _ = money(cur, bill_id)
    ps = 'PAID' if tps >= total else ('PARTIALLY_PAID' if tps > 0 else 'UNPAID')
    bill_update(cur, bill_id, ps=ps, paid=('now' if ps == 'PAID' else None))
    if ps == 'PAID':
        cur.execute(ORDER_UPDATE, ('PAID', None, None, None, None, 'now', oid))
        unfinished = one(cur, "SELECT count(*) FROM order_item WHERE order_id=%s AND status IN ('PENDING','CONFIRMED','PREPARING','READY')", oid)
        if unfinished == 0:
            cur.execute("""UPDATE service_call SET status='RESOLVED', resolved_by=NULL, resolved_at=now(), resolution_note='đóng',
                           version=version+1 WHERE session_id=%s AND status IN ('OPEN','ACKNOWLEDGED')""", (sid,))
            cur.execute("""UPDATE table_session SET status='CLOSED', closed_at=now(), closed_by=%s, close_reason=NULL,
                           needs_attention=false, attention_reason=NULL, attention_set_at=NULL, version=version+1 WHERE id=%s""", (cashier, sid))
            cur.execute("UPDATE dining_table SET status='CLEANING', status_changed_at=now(), version=version+1 WHERE id=%s", (tid,))
            return True
    return False


def audit(cur, action, entity, eid, reason=None, user=None):
    cur.execute("""INSERT INTO audit_log (actor_type, actor_user_id, action, entity_type, entity_id, reason)
                   VALUES (%s,%s,%s,%s,%s,%s)""", ('USER' if (user or cashier) else 'SYSTEM', user or cashier, action, entity, eid, reason))


# ================================================================== 1. Tính tiền + REQUEST_PAYMENT tự đóng + audit
tid, sid, oid, items = open_table('T01')
c = conn(); cur = c.cursor()
call = one(cur, "INSERT INTO service_call (session_id, type, created_by_type, created_by_device_id) VALUES (%s,'REQUEST_PAYMENT','GUEST','device-0001') RETURNING id", sid)
c.commit()
bill_id, bill_no = create_bill(cur, tid, sid, oid, 159000)
cur.execute("UPDATE service_call SET status='RESOLVED', resolved_by=NULL, resolved_at=now(), resolution_note='Đã tính tiền', version=version+1 WHERE id=%s", (call,))
audit(cur, 'BILL_CREATED', 'bill', bill_id)
c.commit()
cur.execute("SELECT b.status, b.payment_status, o.status, s.status, t.status FROM bill b JOIN orders o ON o.id=b.order_id JOIN table_session s ON s.id=o.session_id JOIN dining_table t ON t.id=s.table_id WHERE b.id=%s", (bill_id,))
check('1 create bill: ACTIVE/UNPAID, Order LOCKED, phiên BILLING, bàn PAYMENT_PENDING', cur.fetchone() == ('ACTIVE', 'UNPAID', 'LOCKED', 'BILLING', 'PAYMENT_PENDING'))
check('1 mã Bill do trigger cấp BILL-YYYYMMDD-NNNN', bill_no.startswith('BILL-') and len(bill_no) == 18, bill_no)
check('1 ServiceCall REQUEST_PAYMENT RESOLVED với resolved_by NULL', one(cur, "SELECT status FROM service_call WHERE id=%s", call) == 'RESOLVED')
expect_error('1 Bill thứ hai cho cùng Order bị ux_bill_current_per_order chặn', 'ux_bill_current_per_order',
             lambda k: k.execute(BILL_INSERT, dict(order=oid, sub=1, disc=0, net=1, sfr=0, sf=0, tot=1, vat=0, by=cashier, ps='UNPAID', reason=None, dby=None, repl=None, paid=None)))
c.close()

# ================================================================== 2. Chia tiền: SUCCESS + PENDING -> xác nhận SUCCESS -> PAID; phiên giữ BILLING vì còn món
c = conn(); cur = c.cursor()
p1, r1 = insert_payment(cur, bill_id, 100000, 'SUCCESS')
apply_success(cur, tid, sid, oid, bill_id, 159000)
c.commit()
check('2 SUCCESS cấp mã phiếu YYYYMMDD-NNNN, Bill PARTIALLY_PAID', r1 and len(r1) == 13 and one(cur, "SELECT payment_status FROM bill WHERE id=%s", bill_id) == 'PARTIALLY_PAID', r1)
p2, r2 = insert_payment(cur, bill_id, 59000, 'PENDING', 'CARD_POS')
c.commit()
check('2 PENDING không có mã phiếu, Bill vẫn PARTIALLY_PAID khi COMMIT', r2 is None)
expect_error('2 PENDING giữ chỗ: thu thêm 1 đồng -> PAYMENT_EXCEEDS_REMAINING', 'PAYMENT_EXCEEDS_REMAINING', lambda k: insert_payment(k, bill_id, 1, 'SUCCESS'))
# Xác nhận PENDING -> SUCCESS như Hibernate (mọi cột updatable của PaymentTransaction).
cur.execute("""UPDATE payment_transaction SET status='SUCCESS', provider=NULL, provider_txn_id=NULL, note=NULL, failure_reason=NULL,
               confirmed_by=%s, confirmed_at=now() WHERE id=%s RETURNING receipt_number""", (cashier, p2))
r2b = cur.fetchone()[0]
closed = apply_success(cur, tid, sid, oid, bill_id, 159000)
c.commit()
check('2 xác nhận SUCCESS cấp mã phiếu kế tiếp, Bill PAID, Order PAID', r2b and r2b != r1 and one(cur, "SELECT payment_status FROM bill WHERE id=%s", bill_id) == 'PAID' and one(cur, "SELECT status FROM orders WHERE id=%s", oid) == 'PAID', r2b)
check('2 còn món CONFIRMED -> phiên giữ BILLING', not closed and one(cur, "SELECT status FROM table_session WHERE id=%s", sid) == 'BILLING')
hist = [r[0] for r in (cur.execute("SELECT to_status FROM transaction_status_history WHERE payment_transaction_id=%s ORDER BY id", (p2,)) or cur.fetchall())]
check('2 lịch sử PENDING -> SUCCESS do trigger ghi', hist == ['PENDING', 'SUCCESS'], hist)

# ================================================================== 3. Order PAID: món vẫn đi tiếp; món cuối SERVED -> CLOSED, bàn CLEANING
last = items[1]
expect_error('3 Order PAID: không từ chối món CONFIRMED (ORDER_NOT_OPEN, BR-O12)', 'ORDER_NOT_OPEN',
             lambda k: k.execute("UPDATE order_item SET status='REJECTED', reason_code='X1', closed_at=now(), wasted=false WHERE id=%s", (last,)))
for nxt, col in (('PREPARING', 'preparing_at'), ('READY', 'ready_at'), ('SERVED', 'served_at')):
    cur.execute(f"UPDATE order_item SET status=%s, {col}=now(), status_changed_by=%s, status_changed_by_type='STAFF' WHERE id=%s", (nxt, cashier, last))
cur.execute("SELECT id FROM dining_table WHERE id=%s FOR UPDATE", (tid,))
unfinished = one(cur, "SELECT count(*) FROM order_item WHERE order_id=%s AND status IN ('PENDING','CONFIRMED','PREPARING','READY')", oid)
cur.execute("""UPDATE table_session SET status='CLOSED', closed_at=now(), closed_by=%s, close_reason=NULL, needs_attention=false,
               attention_reason=NULL, attention_set_at=NULL, version=version+1 WHERE id=%s""", (cashier, sid))
cur.execute("UPDATE dining_table SET status='CLEANING', status_changed_at=now(), version=version+1 WHERE id=%s", (tid,))
audit(cur, 'SESSION_CLOSED', 'table_session', sid)
c.commit()
check('3 Order PAID: PREPARING/READY/SERVED vẫn được (BR-O12 chỉ chặn hủy/từ chối); đóng phiên CLOSED', unfinished == 0 and one(cur, "SELECT status FROM table_session WHERE id=%s", sid) == 'CLOSED')
expect_error('3 Bill PAID: thu thêm -> PAYMENT_EXCEEDS_REMAINING', 'PAYMENT_EXCEEDS_REMAINING', lambda k: insert_payment(k, bill_id, 1, 'SUCCESS'))
c.close()

# ================================================================== 4. Phát hành lại Bill khi đổi giảm giá (BE-29)
tid, sid, oid, items = open_table('T02')
c = conn(); cur = c.cursor()
old_id, old_no = create_bill(cur, tid, sid, oid, 159000)
c.commit()
cur.execute("SELECT id FROM dining_table WHERE id=%s FOR UPDATE", (tid,))
bill_update(cur, old_id, status='VOIDED', vt='MANAGER_VOID', vrb=manager, vra='now', vr='Điều chỉnh giảm giá: Sinh nhật', vb=manager, va='now')
cur.execute(BILL_INSERT, dict(order=oid, sub=159000, disc=31800, net=127200, sfr=0, sf=0, tot=127200, vat=vat_of(cur, 127200), by=manager,
                              ps='UNPAID', reason='Sinh nhật', dby=manager, repl=old_id, paid=None))
new_id, new_no = cur.fetchone()
audit(cur, 'BILL_VOIDED', 'bill', old_id, 'Điều chỉnh giảm giá: Sinh nhật', manager)
audit(cur, 'BILL_DISCOUNT_APPLIED', 'bill', new_id, 'Sinh nhật', manager)
c.commit()
check('4 phát hành lại: Bill cũ VOIDED (MANAGER_VOID), Bill mới ACTIVE replaces_bill_id, mã mới', one(cur, "SELECT status FROM bill WHERE id=%s", old_id) == 'VOIDED'
      and one(cur, "SELECT replaces_bill_id FROM bill WHERE id=%s", new_id) == old_id and new_no != old_no)
expect_error('4 audit BILL_DISCOUNT_APPLIED không lý do bị CHECK chặn', 'ck_audit_log_reason_required', lambda k: audit(k, 'BILL_DISCOUNT_APPLIED', 'bill', new_id, None, manager))
expect_error('4 sửa giảm giá tại chỗ bị BILL_SNAPSHOT_IMMUTABLE chặn', 'BILL_SNAPSHOT_IMMUTABLE',
             lambda k: k.execute("UPDATE bill SET discount_amount=0, net_amount=159000, total_amount=159000, vat_amount=11778 WHERE id=%s", (new_id,)))
c.close()

# ================================================================== 5. Mở lại phiên (P1): Bill chỉ có Payment FAILED
c = conn(); cur = c.cursor()
pf, _ = insert_payment(cur, new_id, 127200, 'PENDING', 'BANK_TRANSFER')
c.commit()
expect_error('5 Bill còn Payment PENDING: VOIDED bị PENDING_PAYMENT_EXISTS chặn lúc COMMIT', 'PENDING_PAYMENT_EXISTS',
             lambda k: bill_update(k, new_id, status='VOIDED', vt='SESSION_REOPEN', vr='x', vb=cashier, va='now'))
cur.execute("UPDATE payment_transaction SET status='FAILED', failure_reason='Không thấy tiền', confirmed_by=%s, confirmed_at=now() WHERE id=%s", (cashier, pf))
c.commit()
cur.execute("SELECT id FROM dining_table WHERE id=%s FOR UPDATE", (tid,))
bill_update(cur, new_id, status='VOIDED', vt='SESSION_REOPEN', vr='Khách gọi thêm', vb=cashier, va='now')
cur.execute(ORDER_UPDATE, ('OPEN', None, None, None, None, None, oid))
cur.execute("UPDATE table_session SET status='OPEN', billing_started_at=NULL, last_activity_at=now(), version=version+1 WHERE id=%s", (sid,))
cur.execute("UPDATE dining_table SET status='OCCUPIED', status_changed_at=now(), version=version+1 WHERE id=%s", (tid,))
audit(cur, 'SESSION_REOPENED', 'table_session', sid, 'Khách gọi thêm')
c.commit()
cur.execute("SELECT b.status, b.void_type, o.status, s.status, t.status FROM bill b JOIN orders o ON o.id=b.order_id JOIN table_session s ON s.id=o.session_id JOIN dining_table t ON t.id=s.table_id WHERE b.id=%s", (new_id,))
check('5 mở lại: Bill VOIDED SESSION_REOPEN, Order OPEN, phiên OPEN, bàn OCCUPIED', cur.fetchone() == ('VOIDED', 'SESSION_REOPEN', 'OPEN', 'OPEN', 'OCCUPIED'))
third_id, _ = create_bill(cur, tid, sid, oid, 159000)
c.commit()
check('5 tính tiền lại sau mở lại: Bill mới không replaces', one(cur, "SELECT replaces_bill_id FROM bill WHERE id=%s", third_id) is None)
c.close()

# ================================================================== 6. Bill Total = 0 -> PAID ngay; mở lại từ Order PAID (PAID -> LOCKED -> OPEN)
tid, sid, oid, items = open_table('T03', items=((0, 2, 'CONFIRMED'),))
c = conn(); cur = c.cursor()
zid, _ = create_bill(cur, tid, sid, oid, 0)
cur.execute(ORDER_UPDATE, ('PAID', None, None, None, None, 'now', oid))
c.commit()
check('6 Bill Total 0 PAID khi COMMIT (BR-P11), Order PAID', one(cur, "SELECT payment_status FROM bill WHERE id=%s", zid) == 'PAID')
expect_error('6 Order PAID -> OPEN trực tiếp bị trigger chặn', 'INVALID_ORDER_TRANSITION', lambda k: k.execute(ORDER_UPDATE, ('OPEN', None, None, None, None, None, oid)))
bill_update(cur, zid, status='VOIDED', vt='SESSION_REOPEN', vr='x', vb=cashier, va='now')
cur.execute(ORDER_UPDATE, ('LOCKED', None, None, None, None, 'now', oid))
cur.execute(ORDER_UPDATE, ('OPEN', None, None, None, None, None, oid))
cur.execute("UPDATE table_session SET status='OPEN', billing_started_at=NULL WHERE id=%s", (sid,))
c.commit()
check('6 mở lại từ Order PAID qua LOCKED: COMMIT thành công, Bill VOIDED giữ PAID (net 0 >= Total 0)', one(cur, "SELECT status FROM orders WHERE id=%s", oid) == 'OPEN')
c.close()

# ================================================================== 7. Force-close có Bill thu một phần (SRS §12.2, BR-P15, P3)
tid, sid, oid, items = open_table('T04')
c = conn(); cur = c.cursor()
fid, _ = create_bill(cur, tid, sid, oid, 159000)
c.commit()
insert_payment(cur, fid, 59000, 'SUCCESS'); apply_success(cur, tid, sid, oid, fid, 159000); c.commit()
pp, _ = insert_payment(cur, fid, 1000, 'PENDING', 'CARD_POS'); c.commit()
expect_error('7 còn Payment PENDING: Bill WRITTEN_OFF bị chặn lúc COMMIT (validator chặn trước ở service)', 'PENDING_PAYMENT_EXISTS',
             lambda k: bill_update(k, fid, status='WRITTEN_OFF', wo='now'))
cur.execute("UPDATE payment_transaction SET status='FAILED', failure_reason='Khách hủy', confirmed_by=%s, confirmed_at=now() WHERE id=%s", (cashier, pp)); c.commit()
cur.execute("SELECT id FROM dining_table WHERE id=%s FOR UPDATE", (tid,))
bill_update(cur, fid, status='WRITTEN_OFF', wo='now')
cur.execute(ORDER_UPDATE, ('WRITTEN_OFF', 100000, 'Khách bỏ về', manager, 'now', None, oid))
audit(cur, 'ORDER_WRITTEN_OFF', 'orders', oid, 'Khách bỏ về', manager)
audit(cur, 'BILL_WRITTEN_OFF', 'bill', fid, 'Khách bỏ về', manager)
cur.execute("""UPDATE table_session SET status='FORCE_CLOSED', closed_at=now(), closed_by=%s, close_reason='Khách bỏ về', needs_attention=false,
               attention_reason=NULL, attention_set_at=NULL WHERE id=%s""", (manager, sid))
cur.execute("UPDATE dining_table SET status='CLEANING', status_changed_at=now() WHERE id=%s", (tid,))
c.commit()
cur.execute("SELECT b.status, b.payment_status, o.status, o.shortfall_amount FROM bill b JOIN orders o ON o.id=b.order_id WHERE b.id=%s", (fid,))
check('7 force-close: Bill WRITTEN_OFF giữ PARTIALLY_PAID, Order WRITTEN_OFF thất thu 100000', cur.fetchone() == ('WRITTEN_OFF', 'PARTIALLY_PAID', 'WRITTEN_OFF', 100000))
expect_error('7 Bill WRITTEN_OFF không nhận thu thêm (PAYMENT_BILL_NOT_ACTIVE)', 'PAYMENT_BILL_NOT_ACTIVE', lambda k: insert_payment(k, fid, 1000, 'SUCCESS'))
expect_error('7 Bill WRITTEN_OFF không chuyển trạng thái nữa (BILL_FINAL_STATE)', 'BILL_FINAL_STATE', lambda k: bill_update(k, fid, status='VOIDED', vt='MANAGER_VOID', vr='x', vb=manager, va='now'))
c.close()

# ================================================================== 8. ServiceCall: vòng đời + 1 yêu cầu mở mỗi loại
tid, sid, oid, items = open_table('T05')
c = conn(); cur = c.cursor()
sc = one(cur, "INSERT INTO service_call (session_id, type, created_by_type, created_by_device_id, note) VALUES (%s,'CALL_STAFF','GUEST','device-0001','đá') RETURNING id", sid)
c.commit()
expect_error('8 CALL_STAFF thứ hai đang mở bị ux_service_call_open_per_type chặn', 'ux_service_call_open_per_type',
             lambda k: k.execute("INSERT INTO service_call (session_id, type, created_by_type, created_by_device_id) VALUES (%s,'CALL_STAFF','GUEST','device-0002')", (sid,)))
cur.execute("UPDATE service_call SET status='ACKNOWLEDGED', acknowledged_by=%s, acknowledged_at=now(), version=version+1 WHERE id=%s", (cashier, sc))
cur.execute("UPDATE service_call SET status='RESOLVED', resolved_by=%s, resolved_at=now(), resolution_note='xong', version=version+1 WHERE id=%s", (cashier, sc))
ms = one(cur, "INSERT INTO service_call (session_id, type, created_by_type, created_by_user_id, note) VALUES (%s,'MANAGER_SUPPORT','STAFF',%s,'cần giảm giá') RETURNING id", sid, cashier)
cur.execute("UPDATE service_call SET status='RESOLVED', resolved_by=NULL, resolved_at=now(), resolution_note='Phiên bị đóng', version=version+1 WHERE id=%s", (ms,))
c.commit()
check('8 OPEN -> ACKNOWLEDGED -> RESOLVED và OPEN -> RESOLVED (hệ thống, resolved_by NULL) hợp lệ', one(cur, "SELECT count(*) FROM service_call WHERE session_id=%s AND status='RESOLVED'", sid) == 2)
expect_error('8 khách không tạo MANAGER_SUPPORT (ck_service_call_creator)', 'ck_service_call_creator',
             lambda k: k.execute("INSERT INTO service_call (session_id, type, created_by_type, created_by_device_id) VALUES (%s,'MANAGER_SUPPORT','GUEST','device-0001')", (sid,)))
c.close()

# ================================================================== 9. Provider txn trùng
tid, sid, oid, items = open_table('T06')
c = conn(); cur = c.cursor()
pid_bill, _ = create_bill(cur, tid, sid, oid, 159000); c.commit()
insert_payment(cur, pid_bill, 10000, 'SUCCESS', 'BANK_TRANSFER', 'BANK_TRANSFER', 'FT001'); apply_success(cur, tid, sid, oid, pid_bill, 159000); c.commit()
expect_error('9 (provider, provider_txn_id) trùng -> ux_payment_transaction_provider_txn', 'ux_payment_transaction_provider_txn',
             lambda k: insert_payment(k, pid_bill, 10000, 'SUCCESS', 'BANK_TRANSFER', 'BANK_TRANSFER', 'FT001'))
expect_error('9 provider_txn_id không có provider bị CHECK chặn (service mặc định provider = method)', 'ck_payment_transaction_provider',
             lambda k: insert_payment(k, pid_bill, 10000, 'SUCCESS', 'BANK_TRANSFER', None, 'FT002'))
c.close()

# ================================================================== 10. Thu tiền đồng thời: (a) chỉ dựa vào trigger khóa dòng Bill, (b) như service (khóa bàn trước)
tid, sid, oid, items = open_table('T07')
c = conn(); cur = c.cursor()
cbill, _ = create_bill(cur, tid, sid, oid, 159000); c.commit(); c.close()


def racer(lock_table, out, idx, barrier):
    k = conn(); cu = k.cursor()
    try:
        barrier.wait()
        if lock_table:
            cu.execute("SELECT id FROM dining_table WHERE id=%s FOR UPDATE", (tid,))
            tps, tpp = money(cu, cbill)
            if 159000 - tps - tpp < 100000:
                out[idx] = 'APP_REJECT'; k.rollback(); return
        insert_payment(cu, cbill, 100000, 'SUCCESS')
        apply_success(cu, tid, sid, oid, cbill, 159000)
        time.sleep(0.3)
        k.commit(); out[idx] = 'OK'
    except psycopg2.Error as e:
        k.rollback(); out[idx] = (e.pgerror or '').split(':')[1].strip() if e.pgerror and ':' in e.pgerror else str(e).split(':')[0]
    finally:
        k.close()


for lock_table, label in ((False, 'a trigger'), (True, 'b service')):
    out = [None] * 4
    barrier = threading.Barrier(4)
    ths = [threading.Thread(target=racer, args=(lock_table, out, i, barrier)) for i in range(4)]
    [t.start() for t in ths]; [t.join() for t in ths]
    c = conn(); cur = c.cursor()
    tps, _ = money(cur, cbill)
    check(f'10{label}: 4 lần thu 100000 đồng thời trên Bill 159000 -> đúng 1 thành công, TPS không vượt Total', out.count('OK') == 1 and tps <= 159000, out)
    # dọn để chạy vòng sau: Bill mới cho phiên mới
    c.close()
    tid, sid, oid, items = open_table('T08' if lock_table is False else 'T10')
    c = conn(); cur = c.cursor(); cbill, _ = create_bill(cur, tid, sid, oid, 159000); c.commit(); c.close()

failed = [n for n, ok in results if not ok]
print(f"\nTổng: {len(results) - len(failed)}/{len(results)} PASS")
sys.exit(1 if failed else 0)
