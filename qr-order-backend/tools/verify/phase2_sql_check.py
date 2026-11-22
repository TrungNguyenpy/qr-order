"""Kiểm chứng mọi câu SQL / ràng buộc mà mã + test Giai đoạn 2 dựa vào, trên PostgreSQL 16 thật (V1 -> V2 -> V3)."""
import sys
import psycopg

DSN = sys.argv[1] if len(sys.argv) > 1 else "host=/tmp port=55432 user=postgres dbname=qr"
results = []


def case(name):
    def deco(fn):
        try:
            fn()
            results.append((name, True, ""))
        except AssertionError as e:
            results.append((name, False, str(e)))
        except Exception as e:  # noqa
            results.append((name, False, f"{type(e).__name__}: {e}"))
        return fn
    return deco


def conn():
    return psycopg.connect(DSN, autocommit=False)


def expect_error(sql, params=(), sqlstate=None, constraint=None, prefix=None):
    with conn() as c:
        try:
            c.execute(sql, params)
            c.commit()
        except psycopg.Error as e:
            diag = e.diag
            assert sqlstate is None or e.sqlstate == sqlstate, f"sqlstate {e.sqlstate} != {sqlstate}: {diag.message_primary}"
            assert constraint is None or diag.constraint_name == constraint, f"constraint {diag.constraint_name} != {constraint}"
            assert prefix is None or (diag.message_primary or "").startswith(prefix), diag.message_primary
            c.rollback()
            return
    raise AssertionError(f"Không lỗi: {sql}")


def q(sql, params=()):
    with conn() as c:
        r = c.execute(sql, params).fetchall()
        c.commit()
        return r


def exec_(sql, params=()):
    with conn() as c:
        c.execute(sql, params)
        c.commit()


# ------------------------------------------------------------------- DbCleaner: chụp và trả seed menu
MENU = ["product_option_group", "option_item", "option_group", "product", "category"]


def clean():
    with conn() as c:
        c.execute("SET LOCAL session_replication_role = replica")
        for t in ["order_item_status_history", "order_item", "order_batch", "orders", "table_session", "audit_log"]:
            c.execute(f"DELETE FROM {t}")
        c.execute("CREATE SCHEMA IF NOT EXISTS test_seed")
        for t in MENU:
            c.execute(f"CREATE TABLE IF NOT EXISTS test_seed.{t} AS TABLE public.{t}")
        for t in MENU:
            c.execute(f"DELETE FROM public.{t}")
        for t in reversed(MENU):
            c.execute(f"INSERT INTO public.{t} SELECT * FROM test_seed.{t}")
        c.commit()


def menu_fingerprint():
    return [q(f"SELECT md5(string_agg(t::text, '|' ORDER BY t::text)) FROM {tbl} t")[0][0] for tbl in MENU]


SEED = None


@case("DbCleaner: lần đầu chụp seed, sau khi sửa/thêm menu thì trả đúng seed (id, version, dữ liệu)")
def _():
    global SEED
    clean()
    SEED = menu_fingerprint()
    exec_("INSERT INTO category (name) VALUES ('Tạm')")
    exec_("UPDATE product SET price = 1, version = version + 1 WHERE name = 'Phở bò tái'")
    exec_("UPDATE option_item SET is_active = false")
    exec_("DELETE FROM product_option_group WHERE product_id = (SELECT id FROM product WHERE name = 'Phở bò tái')")
    assert menu_fingerprint() != SEED
    clean()
    assert menu_fingerprint() == SEED, "Không trả về đúng seed"
    clean()
    assert menu_fingerprint() == SEED
    assert q("SELECT count(*) FROM product")[0][0] == 12
    # bảng chụp không nằm trong public, không thêm trigger/unique index ở public
    assert q("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'")[0][0] == 26
    assert q("SELECT count(*) FROM pg_trigger WHERE NOT tgisinternal")[0][0] == 44


@case("Sau khi trả seed, identity sinh id mới không đụng id seed")
def _():
    new_id = q("INSERT INTO category (name) VALUES ('Mới sau clean') RETURNING id")[0][0]
    assert new_id > 4
    clean()


# ------------------------------------------------------------------- Tìm trùng tên (derived query: upper = upper)
@case("Trùng tên danh mục: upper(name)=upper(?) và unique index lower() thống nhất với các ca test (chỉ khác hoa/thường ASCII)")
def _():
    assert q("SELECT count(*) > 0 FROM category WHERE upper(name) = upper(%s) AND id <> -1", ("kHAI vị",))[0][0]
    assert q("SELECT count(*) > 0 FROM category WHERE upper(name) = upper(%s) AND id <> -1", ("tráng miệng",))[0][0]
    expect_error("INSERT INTO category (name) VALUES ('KHAI vị')", sqlstate="23505", constraint="ux_category_name_lower")
    expect_error("INSERT INTO category (name) VALUES ('   ')", sqlstate="23514", constraint="ck_category_name_not_blank")


@case("Trùng tên lựa chọn đang dùng trong nhóm; lựa chọn ngừng dùng không chiếm tên")
def _():
    g = q("SELECT id FROM option_group WHERE name = 'Kích cỡ'")[0][0]
    expect_error("INSERT INTO option_item (option_group_id, name) VALUES (%s, 'nhỏ')", (g,), "23505", "ux_option_item_group_name_active")
    exec_("INSERT INTO option_item (option_group_id, name, is_active) VALUES (%s, 'Nhỏ', false)", (g,))
    assert q("SELECT count(*) > 0 FROM option_item WHERE option_group_id = %s AND is_active AND upper(name) = upper(%s) AND id <> -1", (g, "siêu lớn"))[0][0] is False
    clean()


# ------------------------------------------------------------------- CHECK mà test mong đợi (đúng một ràng buộc)
@case("product: ck_product_price, ck_product_status, ck_product_image_url, ck_product_name_not_blank, FK, chặn DELETE")
def _():
    main = q("SELECT id FROM category WHERE name = 'Món chính'")[0][0]
    pho = q("SELECT id FROM product WHERE name = 'Phở bò tái'")[0][0]
    expect_error("INSERT INTO product (category_id, name, price) VALUES (%s, 'Âm', -1)", (main,), "23514", "ck_product_price")
    expect_error("UPDATE product SET status = 'DELETED' WHERE id = %s", (pho,), "23514", "ck_product_status")
    expect_error("UPDATE product SET image_url = 'http://insecure/x.jpg' WHERE id = %s", (pho,), "23514", "ck_product_image_url")
    expect_error("INSERT INTO product (category_id, name, price) VALUES (%s, '  ', 1)", (main,), "23514", "ck_product_name_not_blank")
    expect_error("INSERT INTO product (category_id, name, price) VALUES (999999, 'X', 1)", (), "23503")
    expect_error("DELETE FROM product WHERE id = %s", (pho,), "P0001", prefix="HARD_DELETE_FORBIDDEN")
    expect_error("DELETE FROM category WHERE id = %s", (main,), "P0001", prefix="HARD_DELETE_FORBIDDEN")
    # https Cloudinary + public_id lưu được
    exec_("UPDATE product SET image_url = 'https://res.cloudinary.com/demo/image/upload/v1/a.jpg', image_public_id = 'a' WHERE id = %s", (pho,))
    clean()


@case("option_group/option_item: đúng 1 CHECK vi phạm cho từng ca test")
def _():
    expect_error("INSERT INTO option_group (name, selection_type, is_required, min_select, max_select) VALUES ('a', 'SINGLE', false, 0, 2)", (), "23514", "ck_option_group_single")
    expect_error("INSERT INTO option_group (name, selection_type, is_required, min_select) VALUES ('a', 'MULTIPLE', true, 0)", (), "23514", "ck_option_group_required")
    expect_error("INSERT INTO option_group (name, selection_type, is_required, min_select, max_select) VALUES ('a', 'MULTIPLE', true, 3, 2)", (), "23514", "ck_option_group_max_select")
    expect_error("INSERT INTO option_group (name, selection_type) VALUES ('a', 'ANY')", (), "23514", "ck_option_group_selection_type")
    g = q("SELECT id FROM option_group WHERE name = 'Kích cỡ'")[0][0]
    expect_error("INSERT INTO option_item (option_group_id, name, extra_price) VALUES (%s, 'x', -1)", (g,), "23514", "ck_option_item_extra_price")
    expect_error("DELETE FROM option_item WHERE option_group_id = %s", (g,), "P0001", prefix="HARD_DELETE_FORBIDDEN")
    expect_error("DELETE FROM option_group WHERE id = %s", (g,), "P0001", prefix="HARD_DELETE_FORBIDDEN")
    # Bảng nối được xóa (cấu hình, không có trigger chặn).
    exec_("DELETE FROM product_option_group WHERE option_group_id = %s", (g,))
    clean()


@case("Mọi cấu hình nhóm hợp lệ mà OptionGroupRules sinh ra đều qua CHECK")
def _():
    for st, req, mn, mx in [("SINGLE", True, 1, 1), ("SINGLE", False, 0, 1), ("MULTIPLE", False, 0, None), ("MULTIPLE", True, 2, 4), ("MULTIPLE", True, 2, None)]:
        exec_("INSERT INTO option_group (name, selection_type, is_required, min_select, max_select) VALUES ('ok', %s, %s, %s, %s)", (st, req, mn, mx))
    clean()


# ------------------------------------------------------------------- bumpVersion (gán nhóm option)
@case("bumpVersion: tăng version khi đúng version, 0 dòng khi lệch")
def _():
    pho = q("SELECT id FROM product WHERE name = 'Phở bò tái'")[0][0]
    with conn() as c:
        n = c.execute("UPDATE product SET version = version + 1, updated_at = now() WHERE id = %s AND version = %s", (pho, 0)).rowcount
        assert n == 1
        n = c.execute("UPDATE product SET version = version + 1, updated_at = now() WHERE id = %s AND version = %s", (pho, 0)).rowcount
        assert n == 0
        c.rollback()


@case("Gán nhóm option: xóa liên kết bỏ, sửa thứ tự, thêm liên kết mới trong một transaction")
def _():
    pho = q("SELECT id FROM product WHERE name = 'Phở bò tái'")[0][0]
    size, extra, top = (q("SELECT id FROM option_group WHERE name = %s", (n,))[0][0] for n in ("Kích cỡ", "Thêm món", "Topping"))
    with conn() as c:
        c.execute("INSERT INTO product_option_group (product_id, option_group_id, display_order) VALUES (%s, %s, 1)", (pho, top))
        c.execute("UPDATE product_option_group SET display_order = 2 WHERE product_id = %s AND option_group_id = %s", (pho, extra))
        c.execute("DELETE FROM product_option_group WHERE product_id = %s AND option_group_id = %s", (pho, size))
        c.commit()
    assert q("SELECT option_group_id FROM product_option_group WHERE product_id = %s ORDER BY display_order", (pho,)) == [(top,), (extra,)]
    clean()


# ------------------------------------------------------------------- Public menu: các truy vấn
@case("Public menu: danh mục hiển thị, món không HIDDEN theo display_order, liên kết + nhóm + lựa chọn đang dùng")
def _():
    cats = q("SELECT name FROM category WHERE is_visible ORDER BY display_order, id")
    assert [c[0] for c in cats] == ["Món chính", "Khai vị", "Đồ uống", "Tráng miệng"]
    prods = q("""SELECT p.name FROM product p WHERE p.category_id IN (SELECT id FROM category WHERE is_visible)
                 AND p.status <> 'HIDDEN' ORDER BY p.display_order, p.id""")
    names = [p[0] for p in prods]
    assert "Bánh flan" not in names and "Mì xào hải sản" in names and len(names) == 11
    pho_groups = q("""SELECT g.name FROM product_option_group l JOIN option_group g ON g.id = l.option_group_id
                      JOIN product p ON p.id = l.product_id WHERE p.name = 'Phở bò tái' ORDER BY l.display_order""")
    assert [g[0] for g in pho_groups] == ["Kích cỡ", "Thêm món"]
    plan = "\n".join(r[0] for r in q("EXPLAIN SELECT * FROM product WHERE category_id IN (1,2) AND status <> 'HIDDEN' ORDER BY display_order, id"))
    assert "product" in plan


# ------------------------------------------------------------------- Hợp đồng BR-O13 (listener GĐ4 mô phỏng)
@case("BR-O13: REJECTED OUT_OF_STOCK cho món PENDING/CONFIRMED của Order OPEN; Order LOCKED giữ nguyên; lịch sử SYSTEM")
def _():
    pho = q("SELECT id FROM product WHERE name = 'Phở bò tái'")[0][0]
    t1, t2 = (q("SELECT id FROM dining_table WHERE code = %s", (c,))[0][0] for c in ("T01", "T02"))
    with conn() as c:
        s1 = c.execute("INSERT INTO table_session (table_id, opened_by_type) VALUES (%s, 'GUEST') RETURNING id", (t1,)).fetchone()[0]
        o1 = c.execute("INSERT INTO orders (session_id) VALUES (%s) RETURNING id", (s1,)).fetchone()[0]
        b1 = c.execute("""INSERT INTO order_batch (order_id, session_id, seq, client_request_id, status, requires_confirmation, submitted_by_type, device_id)
                          VALUES (%s, %s, 1, gen_random_uuid(), 'PENDING', false, 'GUEST', 'device-0001') RETURNING id""", (o1, s1)).fetchone()[0]
        b2 = c.execute("""INSERT INTO order_batch (order_id, session_id, seq, client_request_id, status, requires_confirmation, submitted_by_type, device_id)
                          VALUES (%s, %s, 2, gen_random_uuid(), 'ACCEPTED', false, 'GUEST', 'device-0001') RETURNING id""", (o1, s1)).fetchone()[0]
        i_pending = c.execute("""INSERT INTO order_item (order_id, batch_id, product_id, product_name, unit_price, quantity, line_total, device_id)
                                 VALUES (%s, %s, %s, 'Phở bò tái', 65000, 1, 65000, 'device-0001') RETURNING id""", (o1, b1, pho)).fetchone()[0]
        i_conf = c.execute("""INSERT INTO order_item (order_id, batch_id, product_id, product_name, unit_price, quantity, line_total, status, device_id, confirmed_at)
                              VALUES (%s, %s, %s, 'Phở bò tái', 65000, 1, 65000, 'CONFIRMED', 'device-0001', now()) RETURNING id""", (o1, b2, pho)).fetchone()[0]
        s2 = c.execute("INSERT INTO table_session (table_id, opened_by_type) VALUES (%s, 'GUEST') RETURNING id", (t2,)).fetchone()[0]
        o2 = c.execute("INSERT INTO orders (session_id) VALUES (%s) RETURNING id", (s2,)).fetchone()[0]
        b3 = c.execute("""INSERT INTO order_batch (order_id, session_id, seq, client_request_id, status, requires_confirmation, submitted_by_type, device_id)
                          VALUES (%s, %s, 1, gen_random_uuid(), 'ACCEPTED', false, 'GUEST', 'device-0001') RETURNING id""", (o2, s2)).fetchone()[0]
        i_locked = c.execute("""INSERT INTO order_item (order_id, batch_id, product_id, product_name, unit_price, quantity, line_total, status, device_id, confirmed_at)
                                VALUES (%s, %s, %s, 'Phở bò tái', 65000, 1, 65000, 'CONFIRMED', 'device-0001', now()) RETURNING id""", (o2, b3, pho)).fetchone()[0]
        c.commit()
    exec_("UPDATE orders SET status = 'LOCKED' WHERE id = %s", (o2,))
    with conn() as c:
        c.execute("UPDATE product SET status = 'SOLD_OUT', status_changed_at = now(), version = version + 1 WHERE id = %s", (pho,))
        n = c.execute("""UPDATE order_item
                            SET status = 'REJECTED', reason_code = 'OUT_OF_STOCK', closed_at = now(),
                                status_changed_by_type = 'SYSTEM', status_changed_by = NULL, status_changed_by_device_id = NULL,
                                updated_at = now(), version = version + 1
                          WHERE product_id = %s AND status IN ('PENDING', 'CONFIRMED')
                            AND order_id IN (SELECT id FROM orders WHERE status = 'OPEN')""", (pho,)).rowcount
        assert n == 2, n
        c.commit()
    st = dict(q("SELECT id, status FROM order_item WHERE id IN (%s, %s, %s)", (i_pending, i_conf, i_locked)))
    assert st == {i_pending: "REJECTED", i_conf: "REJECTED", i_locked: "CONFIRMED"}, st
    h = q("""SELECT count(*) FROM order_item_status_history WHERE to_status = 'REJECTED' AND changed_by_type = 'SYSTEM'
             AND reason_code = 'OUT_OF_STOCK'""")[0][0]
    assert h == 2, h
    clean()
    exec_("SET session_replication_role = origin")  # no-op ngoài transaction, chỉ để chắc chắn
    with conn() as c:
        c.execute("SET LOCAL session_replication_role = replica")
        for t in ["order_item_status_history", "order_item", "order_batch", "orders", "table_session"]:
            c.execute(f"DELETE FROM {t}")
        c.execute("UPDATE dining_table SET status = 'AVAILABLE'")
        c.commit()


# ------------------------------------------------------------------- audit_log action/entity_type mới
@case("audit_log nhận các action/entity_type mới của menu (CHECK định dạng), entity_id NULL cho sắp xếp lại")
def _():
    actions = ["CATEGORY_CREATED", "CATEGORY_UPDATED", "CATEGORY_VISIBILITY_CHANGED", "CATEGORIES_REORDERED", "PRODUCT_CREATED",
               "PRODUCT_UPDATED", "PRODUCT_PRICE_CHANGED", "PRODUCT_STATUS_CHANGED", "PRODUCT_IMAGE_CHANGED",
               "PRODUCT_OPTION_GROUPS_CHANGED", "OPTION_GROUP_CREATED", "OPTION_GROUP_UPDATED", "OPTION_GROUP_ACTIVE_CHANGED",
               "OPTION_ITEM_CREATED", "OPTION_ITEM_UPDATED", "OPTION_ITEM_ACTIVE_CHANGED"]
    with conn() as c:
        for a in actions:
            for et in ("category", "product", "option_group", "option_item"):
                c.execute("""INSERT INTO audit_log (actor_type, action, entity_type, entity_id, old_value, new_value)
                             VALUES ('SYSTEM', %s, %s, NULL, '{"displayOrder":{"1":2}}', '{"optionGroupIds":[3,1]}')""", (a, et))
        c.rollback()


for name, ok, msg in results:
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else f"\n     -> {msg}"))
passed = sum(1 for r in results if r[1])
print(f"\n{passed}/{len(results)} PASS")
sys.exit(0 if passed == len(results) else 1)
