"""
Cách chạy: pip install psycopg2-binary; PGHOST=... PGDATABASE=... python3 phase5b_flows.py (database dùng riêng để thử).

GĐ5b - kiểm chứng ở mức SQL (Void, Refund, rút Void, Bill thay thế, mở lại phiên sau Void) trên PostgreSQL 16 thật (schema V1 + seed V2 + V3, không sửa schema):
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



REFUND_INSERT = """INSERT INTO refund_transaction (bill_id, request_id, method, amount, status, reason, created_by, failure_reason,
    confirmed_by, confirmed_at) VALUES (%s,%s,'CASH',%s,%s,'Hoàn tiền Void',%s,NULL,%s,%s) RETURNING id, receipt_number"""


def insert_refund(cur, bill_id, amount, status):
    ok = status != 'PENDING'
    cur.execute(REFUND_INSERT, (bill_id, str(uuid.uuid4()), amount, status, manager, manager if ok else None, 'now' if ok else None))
    return cur.fetchone()


def refund_money(cur, bill_id):
    cur.execute("""SELECT coalesce(sum(amount) FILTER (WHERE status='SUCCESS'),0), coalesce(sum(amount) FILTER (WHERE status='PENDING'),0)
                   FROM refund_transaction WHERE bill_id=%s""", (bill_id,))
    return cur.fetchone()


ORDER_FULL = """UPDATE orders SET status=%(status)s, status_before_void=%(sbv)s, needs_attention=%(na)s, attention_reason=%(ar)s,
    attention_set_at=%(at)s, shortfall_amount=NULL, writeoff_reason=NULL, written_off_by=NULL, written_off_at=NULL,
    paid_at=%(paid)s, voided_at=NULL, updated_at=now(), version=version+1 WHERE id=%(id)s"""


def order_set(cur, oid, status, sbv=None, attention=False, paid=None):
    cur.execute(ORDER_FULL, dict(status=status, sbv=sbv, na=attention, ar='AWAITING_REPLACEMENT_BILL' if attention else None,
                                 at='now' if attention else None, paid=paid, id=oid))


def apply_refund_success(cur, bill_id, oid):
    """RefundService.applySuccess: payment_status theo TPS - TRS; hoàn đủ -> VOIDED + Order chờ Bill thay thế."""
    tps, _ = money(cur, bill_id)
    trs, trp = refund_money(cur, bill_id)
    total = one(cur, "SELECT total_amount FROM bill WHERE id=%s", bill_id)
    net = tps - trs
    ps = 'PAID' if net >= total else ('PARTIALLY_PAID' if net > 0 else 'UNPAID')
    if trs == tps and trp == 0:
        bill_update(cur, bill_id, status='VOIDED', ps=ps, vt='MANAGER_VOID', vb=manager, va='now')
        order_set(cur, oid, 'LOCKED', attention=True)
        return True
    bill_update(cur, bill_id, ps=ps)
    return False


def paid_bill(code, items=((65000, 2, 'SERVED'), (29000, 1, 'SERVED'))):
    tid, sid, oid, ids = open_table(code, items)
    c = conn(); cur = c.cursor()
    bid, _ = create_bill(cur, tid, sid, oid, 159000); c.commit()
    insert_payment(cur, bid, 159000, 'SUCCESS'); apply_success(cur, tid, sid, oid, bid, 159000); c.commit(); c.close()
    return tid, sid, oid, bid


# ================================================================== 1. Void khi chưa thu (VOIDED ngay)
tid, sid, oid, ids = open_table('T01')
c = conn(); cur = c.cursor()
bid, _ = create_bill(cur, tid, sid, oid, 159000); c.commit()
cur.execute("SELECT id FROM dining_table WHERE id=%s FOR UPDATE", (tid,))
bill_update(cur, bid, status='VOIDED', vt='MANAGER_VOID', vrb=manager, vra='now', vr='Sai món', vb=manager, va='now')
order_set(cur, oid, 'LOCKED', attention=True)
audit(cur, 'BILL_VOID_REQUESTED', 'bill', bid, 'Sai món', manager)
c.commit()
check('1 Void khi thu ròng 0: Bill VOIDED, Order LOCKED + AWAITING_REPLACEMENT_BILL, phiên giữ BILLING',
      one(cur, "SELECT status FROM bill WHERE id=%s", bid) == 'VOIDED' and one(cur, "SELECT needs_attention FROM orders WHERE id=%s", oid)
      and one(cur, "SELECT status FROM table_session WHERE id=%s", sid) == 'BILLING')
expect_error('1 audit BILL_VOID_REQUESTED không lý do bị CHECK chặn', 'ck_audit_log_reason_required', lambda k: audit(k, 'BILL_VOID_REQUESTED', 'bill', bid, None, manager))
expect_error('1 needs_attention khi Order không LOCKED bị ck_orders_attention chặn', 'ck_orders_attention', lambda k: order_set(k, oid, 'OPEN', attention=True))

# Bill thay thế: replaces_bill_id, xóa needs_attention
cur.execute(BILL_INSERT, dict(order=oid, sub=159000, disc=15900, net=143100, sfr=0, sf=0, tot=143100, vat=vat_of(cur, 143100), by=manager,
                              ps='UNPAID', reason='Xin lỗi', dby=manager, repl=bid, paid=None))
rid, rno = cur.fetchone()
order_set(cur, oid, 'LOCKED', attention=False)
audit(cur, 'BILL_REPLACEMENT_CREATED', 'bill', rid, None, manager)
c.commit()
check('1 Bill thay thế ACTIVE replaces_bill_id = Bill cũ, Order LOCKED không còn needs_attention',
      one(cur, "SELECT replaces_bill_id FROM bill WHERE id=%s", rid) == bid and not one(cur, "SELECT needs_attention FROM orders WHERE id=%s", oid))
insert_payment(cur, rid, 143100, 'SUCCESS'); apply_success(cur, tid, sid, oid, rid, 143100); c.commit()
check('1 thu Bill thay thế -> Bill PAID, Order PAID',
      one(cur, "SELECT payment_status FROM bill WHERE id=%s", rid) == 'PAID' and one(cur, "SELECT status FROM orders WHERE id=%s", oid) == 'PAID')
c.close()

# ================================================================== 2. Void sau CLOSED, VOID_PENDING, Refund theo SRS 12.1
tid, sid, oid, bid = paid_bill('T02')
c = conn(); cur = c.cursor()
check('2 chuẩn bị: phiên CLOSED, Order PAID', one(cur, "SELECT status FROM table_session WHERE id=%s", sid) == 'CLOSED'
      and one(cur, "SELECT status FROM orders WHERE id=%s", oid) == 'PAID')
cur.execute("SELECT id FROM dining_table WHERE id=%s FOR UPDATE", (tid,))
bill_update(cur, bid, status='VOID_PENDING', vrb=manager, vra='now', vr='Sai món')
order_set(cur, oid, 'LOCKED', sbv='PAID', paid='now')
c.commit()
check('2 VOID_PENDING sau CLOSED: Order LOCKED status_before_void PAID, phiên vẫn CLOSED',
      one(cur, "SELECT status_before_void FROM orders WHERE id=%s", oid) == 'PAID' and one(cur, "SELECT status FROM table_session WHERE id=%s", sid) == 'CLOSED')
expect_error('2 thu thêm trên Bill VOID_PENDING -> PAYMENT_BILL_NOT_ACTIVE', 'PAYMENT_BILL_NOT_ACTIVE', lambda k: insert_payment(k, bid, 1000, 'SUCCESS'))
r1, rn1 = insert_refund(cur, bid, 59000, 'SUCCESS'); apply_refund_success(cur, bid, oid); c.commit()
check('2 #2 Refund SUCCESS 59.000: mã RF-, Bill PARTIALLY_PAID, vẫn VOID_PENDING', rn1 and rn1.startswith('RF-')
      and one(cur, "SELECT payment_status FROM bill WHERE id=%s", bid) == 'PARTIALLY_PAID' and one(cur, "SELECT status FROM bill WHERE id=%s", bid) == 'VOID_PENDING', rn1)
expect_error('2 #2 Refund 100.001 > remaining_refundable -> REFUND_EXCEEDS_REFUNDABLE', 'REFUND_EXCEEDS_REFUNDABLE', lambda k: insert_refund(k, bid, 100001, 'PENDING'))
pa, _ = insert_refund(cur, bid, 60000, 'PENDING'); c.commit()
pb, _ = insert_refund(cur, bid, 40000, 'PENDING'); c.commit()
expect_error('2 #3 TRS + TRP = TPS: thêm 1 đồng bị chặn', 'REFUND_EXCEEDS_REFUNDABLE', lambda k: insert_refund(k, bid, 1, 'PENDING'))
expect_error('2 #3 còn Refund PENDING: VOIDED bị VOID_NOT_COMPLETE chặn lúc COMMIT', 'VOID_NOT_COMPLETE',
             lambda k: bill_update(k, bid, status='VOIDED', vt='MANAGER_VOID', vb=manager, va='now'))
cur.execute("UPDATE refund_transaction SET status='FAILED', failure_reason='Khách chưa nhận', confirmed_by=%s, confirmed_at=now() WHERE id=%s", (manager, pa)); c.commit()
check('2 #6 FAILED không tính: remaining_refundable = 60.000', 159000 - sum(refund_money(cur, bid)) == 60000)
expect_error('2 Refund FAILED bất biến (TRANSACTION_IMMUTABLE)', 'TRANSACTION_IMMUTABLE',
             lambda k: k.execute("UPDATE refund_transaction SET status='SUCCESS', confirmed_by=%s, confirmed_at=now() WHERE id=%s", (manager, pa)))
cur.execute("UPDATE refund_transaction SET status='SUCCESS', failure_reason=NULL, confirmed_by=%s, confirmed_at=now() WHERE id=%s RETURNING receipt_number", (manager, pb))
rnb = cur.fetchone()[0]; done = apply_refund_success(cur, bid, oid); c.commit()
check('2 xác nhận PENDING -> SUCCESS cấp RF- kế tiếp, chưa hoàn đủ', rnb and rnb != rn1 and not done)
insert_refund(cur, bid, 60000, 'SUCCESS'); done = apply_refund_success(cur, bid, oid); c.commit()
cur.execute("SELECT b.status, b.payment_status, o.status, o.needs_attention, o.status_before_void FROM bill b JOIN orders o ON o.id=b.order_id WHERE b.id=%s", (bid,))
check('2 #5 hoàn đủ: Bill VOIDED + UNPAID (DB-04), Order LOCKED needs_attention, status_before_void NULL', done and cur.fetchone() == ('VOIDED', 'UNPAID', 'LOCKED', True, None))
expect_error('2 Bill VOIDED: không hoàn thêm (TRS + TRP > TPS)', 'REFUND_EXCEEDS_REFUNDABLE', lambda k: insert_refund(k, bid, 1, 'SUCCESS'))
c.close()

# ================================================================== 3. Rút Void
tid, sid, oid, bid = paid_bill('T03')
c = conn(); cur = c.cursor()
bill_update(cur, bid, status='VOID_PENDING', vrb=manager, vra='now', vr='Sai món')
order_set(cur, oid, 'LOCKED', sbv='PAID', paid='now'); c.commit()
pf, _ = insert_refund(cur, bid, 10000, 'PENDING'); c.commit()
cur.execute("UPDATE refund_transaction SET status='FAILED', failure_reason='Nhập nhầm', confirmed_by=%s, confirmed_at=now() WHERE id=%s", (manager, pf)); c.commit()
cur.execute("""UPDATE bill SET status='ACTIVE', void_withdrawn_by=%s, void_withdrawn_at=now(), void_withdraw_reason='Khách giữ Bill',
               version=version+1 WHERE id=%s""", (manager, bid))
order_set(cur, oid, 'PAID', paid='now')
audit(cur, 'BILL_VOID_WITHDRAWN', 'bill', bid, 'Khách giữ Bill', manager)
c.commit()
check('3 #5 rút Void khi chỉ có Refund FAILED: Bill ACTIVE PAID, Order về PAID',
      one(cur, "SELECT status FROM bill WHERE id=%s", bid) == 'ACTIVE' and one(cur, "SELECT status FROM orders WHERE id=%s", oid) == 'PAID')
bill_update(cur, bid, status='VOID_PENDING', vrb=manager, vra='now', vr='Void lần 2')
order_set(cur, oid, 'LOCKED', sbv='PAID', paid='now'); c.commit()
insert_refund(cur, bid, 1000, 'SUCCESS'); apply_refund_success(cur, bid, oid); c.commit()
expect_error('3 #4 đã có Refund SUCCESS: Bill về ACTIVE bị REFUND_ONLY_FOR_VOID chặn lúc COMMIT (service chặn trước)', 'REFUND_ONLY_FOR_VOID',
             lambda k: k.execute("UPDATE bill SET status='ACTIVE', void_withdrawn_by=%s, void_withdrawn_at=now(), void_withdraw_reason='x' WHERE id=%s", (manager, bid)))
c.close()

# ================================================================== 4. Manager mở lại phiên sau Void (BR-P08 b)
tid, sid, oid, ids = open_table('T04')
c = conn(); cur = c.cursor()
bid, _ = create_bill(cur, tid, sid, oid, 159000); c.commit()
insert_payment(cur, bid, 59000, 'SUCCESS'); apply_success(cur, tid, sid, oid, bid, 159000); c.commit()
bill_update(cur, bid, status='VOID_PENDING', vrb=manager, vra='now', vr='Đổi món')
order_set(cur, oid, 'LOCKED', sbv='LOCKED'); c.commit()
insert_refund(cur, bid, 59000, 'SUCCESS'); apply_refund_success(cur, bid, oid); c.commit()
cur.execute("SELECT id FROM dining_table WHERE id=%s FOR UPDATE", (tid,))
order_set(cur, oid, 'OPEN')
cur.execute("UPDATE table_session SET status='OPEN', billing_started_at=NULL, last_activity_at=now(), version=version+1 WHERE id=%s", (sid,))
cur.execute("UPDATE dining_table SET status='OCCUPIED', status_changed_at=now(), version=version+1 WHERE id=%s", (tid,))
audit(cur, 'SESSION_REOPENED', 'table_session', sid, 'Khách đổi món', manager)
c.commit()
check('4 BR-P08 b: Order LOCKED (chờ Bill thay thế) -> OPEN, phiên OPEN, bàn OCCUPIED',
      one(cur, "SELECT status FROM orders WHERE id=%s", oid) == 'OPEN' and one(cur, "SELECT status FROM table_session WHERE id=%s", sid) == 'OPEN')
b2, _ = create_bill(cur, tid, sid, oid, 159000); c.commit()
check('4 Staff tính tiền lại: Bill mới không replaces', one(cur, "SELECT replaces_bill_id FROM bill WHERE id=%s", b2) is None)
c.close()

# ================================================================== 5. Hai Refund đồng thời (khóa bàn như service + trigger khóa dòng Bill)
tid, sid, oid, bid = paid_bill('T05')
c = conn(); cur = c.cursor()
bill_update(cur, bid, status='VOID_PENDING', vrb=manager, vra='now', vr='Sai món')
order_set(cur, oid, 'LOCKED', sbv='PAID', paid='now'); c.commit(); c.close()


def refund_racer(lock_table, out, idx, barrier):
    k = conn(); cu = k.cursor()
    try:
        barrier.wait()
        if lock_table:
            cu.execute("SELECT id FROM dining_table WHERE id=%s FOR UPDATE", (tid,))
            trs, trp = refund_money(cu, bid)
            if 159000 - trs - trp < 100000:
                out[idx] = 'APP_REJECT'; k.rollback(); return
        insert_refund(cu, bid, 100000, 'PENDING')
        time.sleep(0.3)
        k.commit(); out[idx] = 'OK'
    except psycopg2.Error as e:
        k.rollback(); out[idx] = (e.pgerror or '').split(':')[1].strip() if e.pgerror and ':' in e.pgerror else str(e)
    finally:
        k.close()


for lock_table, label in ((False, 'a trigger'), (True, 'b service')):
    out = [None] * 4
    barrier = threading.Barrier(4)
    ths = [threading.Thread(target=refund_racer, args=(lock_table, out, i, barrier)) for i in range(4)]
    [t.start() for t in ths]; [t.join() for t in ths]
    c = conn(); cur = c.cursor()
    trs, trp = refund_money(cur, bid)
    check(f'5{label}: 4 Refund 100.000 đồng thời (TPS 159.000) -> đúng 1 thành công, TRS + TRP <= TPS', out.count('OK') == 1 and trs + trp <= 159000, out)
    cur.execute("UPDATE refund_transaction SET status='FAILED', failure_reason='dọn', confirmed_by=%s, confirmed_at=now() WHERE bill_id=%s AND status='PENDING'", (manager, bid))
    c.commit(); c.close()

failed = [n for n, ok in results if not ok]
print(f"\nTổng: {len(results) - len(failed)}/{len(results)} PASS")
sys.exit(1 if failed else 0)
