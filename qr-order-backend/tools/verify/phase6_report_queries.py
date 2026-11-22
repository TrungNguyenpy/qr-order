"""
Cách chạy: pip install psycopg2-binary; PGHOST=... PGPORT=... PGUSER=... PGDATABASE=... python3 phase6_report_queries.py
(database đã chạy V1 -> V2 -> V3; script chạy trong MỘT transaction và ROLLBACK nên không để lại dữ liệu).

GĐ6 - kiểm chứng ở mức SQL các câu truy vấn báo cáo của ReportRepository trên PostgreSQL 16 thật, không cần JVM:
- Lấy NGUYÊN VĂN các câu SQL từ src/main/kotlin/com/qrorder/report/repository/ReportRepository.kt (không chép tay, tránh lệch).
- Dựng dữ liệu bằng SQL (session_replication_role = replica để lùi mốc thời gian, giống DbCleaner/ReportIntegrationTestBase).
- Kiểm tra doanh thu theo ngày (ranh giới 23:59/00:00 giờ VN), Refund ngày sau, thất thu tách riêng, nhiều phương thức,
  nhóm ca qua nửa đêm, món bán chạy, thời gian phục vụ, tổng hợp món hủy/từ chối, và kế hoạch EXPLAIN dùng đúng chỉ mục.
Mỗi kịch bản in PASS/FAIL.
"""
import json
import os
import re
import sys
import uuid
from datetime import datetime, timedelta, timezone

import psycopg2

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')
SRC = open(os.path.join(ROOT, 'src/main/kotlin/com/qrorder/report/repository/ReportRepository.kt'), encoding='utf-8').read()
VN = timezone(timedelta(hours=7))
results = []


def check(name, cond, detail=''):
    results.append((name, bool(cond)))
    print(('PASS ' if cond else 'FAIL ') + name + (f'  [{detail}]' if not cond and detail != '' else ''))


# ------------------------------------------------------------------ lấy SQL từ mã nguồn Kotlin
WINDOWS = re.search(r'private const val WINDOWS = "(.*?)"\n', SRC).group(1)


def const_sql(name):
    m = re.search(r'internal const val ' + name + r' = (?:"""(.*?)"""|"(.*?)")', SRC, re.S)
    text = m.group(1) if m.group(1) is not None else m.group(2)
    return text.replace('$WINDOWS', WINDOWS)


def top_products_sql(sort, category):
    block = re.search(r'val sql = """(.*?)"""', SRC, re.S).group(1)
    order_by = {'QUANTITY': 'quantity DESC, sales DESC', 'REVENUE': 'sales DESC, quantity DESC'}[sort]
    return block.replace('$categoryFilter', 'AND p.category_id = :categoryId' if category else '').replace('$orderBy', order_by)


def named(sql, params):
    """Đổi :name của Spring thành %(name)s của psycopg2 (không đụng tới ::)."""
    return re.sub(r'(?<!:):([A-Za-z_]\w*)', r'%(\1)s', sql), params


def run(cur, sql, **params):
    q, p = named(sql, params)
    cur.execute(q, p)
    return cur.fetchall()


def t(s):
    return datetime.fromisoformat(s)


def windows_json(wins):
    return json.dumps([{'idx': i, 's': s.isoformat(), 'e': e.isoformat()} for i, (s, e) in enumerate(wins)])


def day_windows(first, last):
    d0 = datetime.fromisoformat(first + 'T00:00:00+07:00')
    n = (datetime.fromisoformat(last + 'T00:00:00+07:00') - d0).days + 1
    return [(d0 + timedelta(days=i), d0 + timedelta(days=i + 1)) for i in range(n)]


# ------------------------------------------------------------------ dữ liệu
conn = psycopg2.connect(host=os.environ.get('PGHOST', 'localhost'), port=int(os.environ.get('PGPORT', '5432')),
                        user=os.environ.get('PGUSER', 'postgres'), password=os.environ.get('PGPASSWORD'),
                        dbname=os.environ.get('PGDATABASE', 'qr_order'))
conn.set_client_encoding('UTF8')
cur = conn.cursor()
cur.execute("SET LOCAL session_replication_role = replica")
seq = [0]


def one(sql, *a):
    cur.execute(sql, a)
    return cur.fetchone()[0]


def nxt():
    seq[0] += 1
    return seq[0]


manager = one("INSERT INTO users (username,password_hash,full_name,role) VALUES ('v6_manager','x','Quản lý','MANAGER') RETURNING id")
table_ids = [r[0] for r in (cur.execute("SELECT id FROM dining_table ORDER BY code LIMIT 6") or cur.fetchall())]
pho = one("SELECT id FROM product WHERE name = 'Phở bò tái'")
coffee = one("SELECT id FROM product WHERE name = 'Cà phê sữa đá'")
coffee_cat = one("SELECT category_id FROM product WHERE id = %s", coffee)
pho_cat = one("SELECT category_id FROM product WHERE id = %s", pho)


def mk_order(opened, status='PAID', paid_at=None, written_off_at=None, shortfall=None):
    n = nxt()
    sid = one("""INSERT INTO table_session (table_id,status,opened_by_type,opened_at,last_activity_at,closed_at)
                 VALUES (%s,'CLOSED','GUEST',%s,%s,%s) RETURNING id""", table_ids[n % len(table_ids)], t(opened), t(opened), t(opened) + timedelta(hours=1))
    if status == 'WRITTEN_OFF':
        return sid, one("""INSERT INTO orders (session_id,status,shortfall_amount,writeoff_reason,written_off_by,written_off_at)
                           VALUES (%s,'WRITTEN_OFF',%s,'Khách bỏ về',%s,%s) RETURNING id""", sid, shortfall, manager, t(written_off_at))
    return sid, one("INSERT INTO orders (session_id,status,paid_at) VALUES (%s,%s,%s) RETURNING id", sid, status, t(paid_at or opened) if status == 'PAID' else None)


def mk_bill(order_id, total=159000):
    n = nxt()
    vat = round(total * 0.08 / 1.08)
    return one("""INSERT INTO bill (order_id,bill_number,status,payment_status,subtotal_amount,net_amount,service_fee_rate,service_fee_amount,
                                    total_amount,vat_rate,vat_amount,created_by,paid_at)
                  VALUES (%s,%s,'ACTIVE','PAID',%s,%s,0,0,%s,0.08,%s,%s,now()) RETURNING id""",
               order_id, 'BILL-20261101-%04d' % n, total, total, total, vat, manager)


def mk_payment(bill_id, amount, at, method='CASH', status='SUCCESS'):
    n = nxt()
    cur.execute("""INSERT INTO payment_transaction (bill_id,request_id,receipt_number,method,amount,status,created_by,created_at,confirmed_by,confirmed_at)
                   VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)""",
                (bill_id, str(uuid.uuid4()), '20261101-%04d' % n if status == 'SUCCESS' else None, method, amount, status, manager,
                 t(at), manager if status != 'PENDING' else None, t(at) if status != 'PENDING' else None))


def mk_refund(bill_id, amount, at, method='CASH'):
    n = nxt()
    cur.execute("""INSERT INTO refund_transaction (bill_id,request_id,receipt_number,method,amount,status,reason,created_by,created_at,confirmed_by,confirmed_at)
                   VALUES (%s,%s,%s,%s,%s,'SUCCESS','Void',%s,%s,%s,%s)""",
                (bill_id, str(uuid.uuid4()), 'RF-20261101-%04d' % n, method, amount, manager, t(at), manager, t(at)))


def mk_batch(order_id, session_id, seqno, submitted, requires=True):
    return one("""INSERT INTO order_batch (order_id,session_id,seq,client_request_id,status,requires_confirmation,submitted_by_type,device_id,submitted_at)
                  VALUES (%s,%s,%s,%s,'ACCEPTED',%s,'GUEST','dev-1',%s) RETURNING id""",
               order_id, session_id, seqno, str(uuid.uuid4()), requires, t(submitted))


def mk_item(order_id, batch_id, product, qty, status='SERVED', created='2026-11-18T12:00:00+07:00', confirmed=None, preparing=None,
            ready=None, served=None, reason=None, wasted=False, price=None):
    price = price if price is not None else (65000 if product == pho else 29000)
    closed = t(created) + timedelta(minutes=1) if status in ('CANCELLED', 'REJECTED') else None
    return one("""INSERT INTO order_item (order_id,batch_id,product_id,product_name,unit_price,quantity,line_total,status,reason_code,wasted,device_id,
                                          created_at,confirmed_at,preparing_at,ready_at,served_at,closed_at)
                  VALUES (%s,%s,%s,'sp',%s,%s,%s,%s,%s,%s,'dev-1',%s,%s,%s,%s,%s,%s) RETURNING id""",
               order_id, batch_id, product, price, qty, price * qty, status, reason, wasted, t(created),
               t(confirmed) if confirmed else None, t(preparing) if preparing else None, t(ready) if ready else None,
               t(served) if served else None, closed)


# ------------------------------------------------------------------ S1: ranh giới ngày giờ Việt Nam
pay_times = ['2026-11-19T23:59:59+07:00', '2026-11-20T23:59:00+07:00', '2026-11-21T00:00:00+07:00',
             '2026-11-21T23:59:59+07:00', '2026-11-22T00:00:00+07:00']
for at in pay_times:
    _, o = mk_order(at)
    mk_payment(mk_bill(o), 159000, at)
wins = day_windows('2026-11-20', '2026-11-21')
rows = run(cur, const_sql('SQL_PAYMENTS_BY_METHOD'), windows=windows_json(wins))
by_idx = {r[0]: (r[2], r[3]) for r in rows}
check('S1 ngày 20/11 có 1 giao dịch 159.000', by_idx.get(0) == (1, 159000), by_idx)
check('S1 ngày 21/11 có 2 giao dịch 318.000 (00:00 VN thuộc ngày 21, 23:59:59 vẫn ngày 21)', by_idx.get(1) == (2, 318000), by_idx)
check('S1 giao dịch ngoài khoảng (19/11 23:59:59 và 22/11 00:00) bị loại', sum(v[1] for v in by_idx.values()) == 477000)
bills = dict(run(cur, const_sql('SQL_PAYMENT_BILLS_BY_WINDOW'), windows=windows_json(wins)))
check('S1 đếm Bill khác nhau theo kỳ', bills == {0: 1, 1: 2}, bills)
check('S1 tổng Bill khác nhau', run(cur, const_sql('SQL_PAYMENT_BILLS_TOTAL'), windows=windows_json(wins))[0][0] == 3)

# ------------------------------------------------------------------ S2: Refund ngày sau, ngày đã chốt không đổi
_, o = mk_order('2026-12-05T09:00:00+07:00')
b = mk_bill(o)
mk_payment(b, 159000, '2026-12-05T10:00:00+07:00')
mk_refund(b, 159000, '2026-12-06T10:00:00+07:00')
wins = day_windows('2026-12-05', '2026-12-06')
pay = {r[0]: r[3] for r in run(cur, const_sql('SQL_PAYMENTS_BY_METHOD'), windows=windows_json(wins))}
ref = {r[0]: r[3] for r in run(cur, const_sql('SQL_REFUNDS_BY_METHOD'), windows=windows_json(wins))}
check('S2 thu 159.000 ngày 05/12, hoàn 159.000 ngày 06/12', pay == {0: 159000} and ref == {1: 159000}, (pay, ref))
only_first = day_windows('2026-12-05', '2026-12-05')
check('S2 ngày đã chốt không đổi khi chỉ xem 05/12',
      {r[0]: r[3] for r in run(cur, const_sql('SQL_REFUNDS_BY_METHOD'), windows=windows_json(only_first))} == {})

# ------------------------------------------------------------------ S3: thất thu tách riêng
_, o = mk_order('2026-12-10T09:00:00+07:00', status='WRITTEN_OFF', written_off_at='2026-12-11T22:00:00+07:00', shortfall=100000)
wins = day_windows('2026-12-10', '2026-12-11')
wo = {r[0]: (r[1], r[2]) for r in run(cur, const_sql('SQL_WRITE_OFFS_BY_WINDOW'), windows=windows_json(wins))}
check('S3 thất thu 100.000 tính vào ngày written_off_at (11/12)', wo == {1: (1, 100000)}, wo)

# ------------------------------------------------------------------ S4: một Bill, hai phương thức
_, o = mk_order('2026-12-15T09:00:00+07:00')
b = mk_bill(o)
mk_payment(b, 100000, '2026-12-15T12:00:00+07:00', 'CASH')
mk_payment(b, 59000, '2026-12-15T12:05:00+07:00', 'BANK_TRANSFER')
mk_payment(b, 10000, '2026-12-15T12:06:00+07:00', 'CARD_POS', status='PENDING')
wins = day_windows('2026-12-15', '2026-12-15')
rows = {r[1]: (r[2], r[3]) for r in run(cur, const_sql('SQL_PAYMENTS_BY_METHOD'), windows=windows_json(wins))}
check('S4 tách theo phương thức, PENDING không tính', rows == {'CASH': (1, 100000), 'BANK_TRANSFER': (1, 59000)}, rows)
check('S4 một Bill thanh toán hai lần chỉ đếm một Bill', run(cur, const_sql('SQL_PAYMENT_BILLS_TOTAL'), windows=windows_json(wins))[0][0] == 1)

# ------------------------------------------------------------------ S5: ca làm việc qua nửa đêm
for at in ['2026-12-20T07:00:00+07:00', '2026-12-20T15:00:00+07:00', '2026-12-20T23:30:00+07:00', '2026-12-21T02:00:00+07:00']:
    _, o = mk_order(at)
    mk_payment(mk_bill(o), 159000, at)
d = t('2026-12-20T00:00:00+07:00')
shift_wins = [(d + timedelta(hours=6), d + timedelta(hours=14)), (d + timedelta(hours=14), d + timedelta(hours=23)), (d + timedelta(hours=23), d + timedelta(hours=30))]
sums = {r[0]: r[3] for r in run(cur, const_sql('SQL_PAYMENTS_BY_METHOD'), windows=windows_json(shift_wins))}
check('S5 ca sáng, chiều, đêm (23:00 đến 06:00 hôm sau): 159.000 / 159.000 / 318.000', sums == {0: 159000, 1: 159000, 2: 318000}, sums)

# ------------------------------------------------------------------ S6: món bán chạy (Order PAID theo paid_at)
sid1, o1 = mk_order('2026-11-12T11:00:00+07:00', paid_at='2026-11-12T12:00:00+07:00')
bt = mk_batch(o1, sid1, 1, '2026-11-12T11:05:00+07:00')
mk_item(o1, bt, pho, 2, 'SERVED', '2026-11-12T11:05:00+07:00', '2026-11-12T11:06:00+07:00', '2026-11-12T11:07:00+07:00', '2026-11-12T11:20:00+07:00', '2026-11-12T11:25:00+07:00')
mk_item(o1, bt, coffee, 1, 'SERVED', '2026-11-12T11:05:00+07:00', '2026-11-12T11:06:00+07:00', '2026-11-12T11:07:00+07:00', '2026-11-12T11:10:00+07:00', '2026-11-12T11:12:00+07:00')
mk_item(o1, bt, coffee, 9, 'CANCELLED', '2026-11-12T11:05:00+07:00', reason='STAFF_CANCELLED')
sid2, o2 = mk_order('2026-11-10T11:00:00+07:00', paid_at='2026-11-10T12:00:00+07:00')
bt2 = mk_batch(o2, sid2, 1, '2026-11-10T11:05:00+07:00')
mk_item(o2, bt2, pho, 1, 'SERVED', '2026-11-10T11:05:00+07:00', '2026-11-10T11:06:00+07:00', '2026-11-10T11:07:00+07:00', '2026-11-10T11:20:00+07:00', '2026-11-10T11:25:00+07:00')
mk_item(o2, bt2, coffee, 4, 'SERVED', '2026-11-10T11:05:00+07:00', '2026-11-10T11:06:00+07:00', '2026-11-10T11:07:00+07:00', '2026-11-10T11:10:00+07:00', '2026-11-10T11:12:00+07:00')
sid3, o3 = mk_order('2026-11-11T11:00:00+07:00', status='LOCKED')
bt3 = mk_batch(o3, sid3, 1, '2026-11-11T11:05:00+07:00')
mk_item(o3, bt3, coffee, 5, 'CONFIRMED', '2026-11-11T11:05:00+07:00', '2026-11-11T11:06:00+07:00')
rng = dict(**{'from': t('2026-11-10T00:00:00+07:00'), 'to': t('2026-11-13T00:00:00+07:00')})
q = run(cur, top_products_sql('QUANTITY', False), limit=10, **rng)
check('S6 xếp theo số lượng: Cà phê 5 (145.000), Phở 3 (195.000); Order chưa PAID và món hủy bị loại',
      [(r[1], r[4], r[5], r[6]) for r in q] == [('Cà phê sữa đá', 5, 145000, 2), ('Phở bò tái', 3, 195000, 2)], q)
q = run(cur, top_products_sql('REVENUE', False), limit=10, **rng)
check('S6 xếp theo doanh số: Phở trước Cà phê', [r[1] for r in q] == ['Phở bò tái', 'Cà phê sữa đá'], q)
q = run(cur, top_products_sql('QUANTITY', True), limit=10, categoryId=coffee_cat, **rng)
check('S6 lọc theo danh mục', [r[1] for r in q] == ['Cà phê sữa đá'] and coffee_cat != pho_cat, q)
q = run(cur, top_products_sql('QUANTITY', False), limit=1, **rng)
check('S6 giới hạn limit', len(q) == 1)
q = run(cur, top_products_sql('QUANTITY', False), limit=10, **{'from': t('2026-11-10T00:00:00+07:00'), 'to': t('2026-11-11T00:00:00+07:00')})
check('S6 ngày 10/11 chỉ có Order thứ hai', sorted((r[1], r[4]) for r in q) == [('Cà phê sữa đá', 4), ('Phở bò tái', 1)], q)

# ------------------------------------------------------------------ S7: thời gian phục vụ
sid, o = mk_order('2026-11-18T11:00:00+07:00', paid_at='2026-11-18T13:00:00+07:00')
base = t('2026-11-18T12:00:00+07:00')
b1 = mk_batch(o, sid, 1, base.isoformat(), True)
b2 = mk_batch(o, sid, 2, (base + timedelta(seconds=1000)).isoformat(), False)


def sec(n):
    return (base + timedelta(seconds=n)).isoformat()


mk_item(o, b1, pho, 1, 'SERVED', sec(0), sec(20), sec(30), sec(330), sec(360))
mk_item(o, b1, coffee, 1, 'SERVED', sec(0), sec(40), sec(70), sec(670), sec(700))
mk_item(o, b2, pho, 1, 'SERVED', sec(1000), sec(1000), sec(1110), sec(1410), sec(1500))
r = run(cur, const_sql('SQL_SERVICE_TIME'), **{'from': t('2026-11-18T00:00:00+07:00'), 'to': t('2026-11-19T00:00:00+07:00')})[0]
cols = [d.name for d in cur.description]
v = dict(zip(cols, r))
f = lambda k: float(v[k])
check('S7 3 món SERVED, chờ xác nhận chỉ tính đợt cần xác nhận (2 mẫu, TB 30, trung vị 30)',
      v['served'] == 3 and v['confirmation_n'] == 2 and f('confirmation_avg') == 30 and f('confirmation_median') == 30, v)
check('S7 chờ bếp TB 50 trung vị 30', f('queue_avg') == 50 and f('queue_median') == 30, v)
check('S7 nấu TB 400 trung vị 300', f('cooking_avg') == 400 and f('cooking_median') == 300, v)
check('S7 mang ra TB 50 trung vị 30', f('delivery_avg') == 50 and f('delivery_median') == 30, v)
check('S7 tổng TB 520 trung vị 500', f('total_avg') == 520 and f('total_median') == 500, v)
r = run(cur, const_sql('SQL_SERVICE_TIME'), **{'from': t('2026-01-01T00:00:00+07:00'), 'to': t('2026-01-02T00:00:00+07:00')})[0]
check('S7 không có mẫu thì số mẫu 0 và thống kê là NULL', r[0] == 0 and r[1] == 0 and all(x is None for x in r[2:]), r)

# ------------------------------------------------------------------ S8: tổng hợp món hủy/từ chối/wasted
sid, o = mk_order('2026-11-25T11:00:00+07:00', status='LOCKED')
b = mk_batch(o, sid, 1, '2026-11-25T11:05:00+07:00')
for st, rc, w in [('SERVED', None, False), ('SERVED', None, False), ('CANCELLED', 'STAFF_CANCELLED', False), ('REJECTED', 'OUT_OF_STOCK', True), ('CANCELLED', 'STAFF_CANCELLED', False)]:
    mk_item(o, b, pho, 2, st, '2026-11-25T11:05:00+07:00', '2026-11-25T11:06:00+07:00' if st == 'SERVED' else None,
            '2026-11-25T11:07:00+07:00' if st == 'SERVED' else None, '2026-11-25T11:10:00+07:00' if st == 'SERVED' else None,
            '2026-11-25T11:12:00+07:00' if st == 'SERVED' else None, reason=rc, wasted=w)
rng = {'from': t('2026-11-25T00:00:00+07:00'), 'to': t('2026-11-26T00:00:00+07:00')}
r = run(cur, const_sql('SQL_ITEM_COHORT'), **rng)[0]
check('S8 cohort món: 5 món, 2 hủy, 1 từ chối, 1 wasted trị giá 130.000', tuple(r) == (5, 2, 1, 1, 130000), r)
r = run(cur, const_sql('SQL_ITEM_REASONS'), **rng)
check('S8 lý do hủy/từ chối xếp theo số lượng giảm dần', [(x[0], x[1]) for x in r] == [('STAFF_CANCELLED', 2), ('OUT_OF_STOCK', 1)], r)
check('S8 đếm phiên mở trong khoảng', run(cur, const_sql('SQL_SESSIONS_OPENED'), **rng)[0][0] == 1)
check('S8 đếm đợt trong khoảng', tuple(run(cur, const_sql('SQL_BATCHES'), **rng)[0]) == (1, 0))
check('S8 ảnh chụp hiện tại chạy được', run(cur, const_sql('SQL_ORDERS_NEEDING_ATTENTION'))[0][0] == 0 and run(cur, const_sql('SQL_BILLS_VOID_PENDING'))[0][0] == 0)

# ------------------------------------------------------------------ EXPLAIN: dùng đúng chỉ mục báo cáo
cur.execute("SET LOCAL enable_seqscan = off")
wj = windows_json(day_windows('2026-11-01', '2026-11-30')).replace("'", "''")


def plan(sql):
    cur.execute('EXPLAIN ' + sql.replace(':windows', "'" + wj + "'"))
    return '\n'.join(x[0] for x in cur.fetchall())


for name, idx in [('SQL_PAYMENTS_BY_METHOD', 'ix_payment_transaction_revenue'), ('SQL_PAYMENT_BILLS_BY_WINDOW', 'ix_payment_transaction_revenue'),
                  ('SQL_PAYMENT_BILLS_TOTAL', 'ix_payment_transaction_revenue'), ('SQL_REFUNDS_BY_METHOD', 'ix_refund_transaction_report'),
                  ('SQL_WRITE_OFFS_BY_WINDOW', 'ix_orders_written_off_report')]:
    p = plan(const_sql(name))
    check('EXPLAIN ' + name + ' dùng ' + idx, idx in p, p)

conn.rollback()
conn.close()
failed = [n for n, ok in results if not ok]
print('\n%d/%d PASS' % (len(results) - len(failed), len(results)))
sys.exit(1 if failed else 0)
