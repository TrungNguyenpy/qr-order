-- =============================================================================
-- QR Order - V2__seed_dev.sql  (CHỈ MÔI TRƯỜNG DEV)
-- Đặt ở thư mục riêng classpath:db/seed-dev, chỉ thêm vào spring.flyway.locations của
-- profile dev. Production chỉ dùng classpath:db/migration, nên migration production kế tiếp
-- phải đánh số từ V3 trở đi để không trùng version với file này.
--
-- Nội dung: cấu hình VAT thử nghiệm, bàn mẫu, QR dev, menu mẫu.
-- KHÔNG tạo tài khoản người dùng (ADMIN đầu tiên do khởi tạo triển khai, BR-A09).
-- vat_rate = 8% chỉ để thử công thức, KHÔNG phải mức VAT thực tế của nhà hàng (BR-P03, A2).
-- =============================================================================

-- 1. Cấu hình thử nghiệm ---------------------------------------------------------
UPDATE restaurant_settings
   SET vat_rate   = 0.0800,
       updated_at = now(),
       version    = version + 1
 WHERE id = 1;

-- 2. Bàn mẫu ------------------------------------------------------------------------
INSERT INTO dining_table (code, display_name, area, capacity, display_order) VALUES
    ('T01', 'Bàn 1',  'Tầng 1',    4,  1),
    ('T02', 'Bàn 2',  'Tầng 1',    4,  2),
    ('T03', 'Bàn 3',  'Tầng 1',    2,  3),
    ('T04', 'Bàn 4',  'Tầng 1',    6,  4),
    ('T05', 'Bàn 5',  'Tầng 2',    4,  5),
    ('T06', 'Bàn 6',  'Tầng 2',    4,  6),
    ('T07', 'Bàn 7',  'Tầng 2',    8,  7),
    ('T08', 'Bàn 8',  'Sân vườn',  4,  8),
    ('T09', 'Bàn 9',  'Sân vườn',  4,  9),
    ('T10', 'Bàn 10', 'Sân vườn',  2, 10);

-- Bàn tạm ngưng để thử BR-S08 (is_active = false chỉ khi AVAILABLE).
INSERT INTO dining_table (code, display_name, area, capacity, is_active, display_order) VALUES
    ('T99', 'Bàn sửa chữa', 'Tầng 2', 4, false, 99);

-- 3. QR dev -------------------------------------------------------------------------
-- Token dev cố định để test thủ công: URL /t/dev-qr-T01 ... /t/dev-qr-T10.
-- Production phải sinh token ngẫu nhiên >= 128 bit; DB chỉ lưu SHA-256 hex (BR-S05).
INSERT INTO qr_code (table_id, token_hash)
SELECT t.id, encode(sha256(convert_to('dev-qr-' || t.code, 'UTF8')), 'hex')
  FROM dining_table t
 WHERE t.is_active;

-- 4. Danh mục ------------------------------------------------------------------------
INSERT INTO category (name, description, display_order) VALUES
    ('Món chính',  'Cơm, phở, bún',            1),
    ('Khai vị',    'Món ăn nhẹ',               2),
    ('Đồ uống',    'Cà phê, trà, nước ép',     3),
    ('Tráng miệng','Chè, bánh ngọt',           4);

-- 5. Món (giá VND đã gồm VAT, DEC-10 A) ------------------------------------------
INSERT INTO product (category_id, name, description, price, status, display_order)
SELECT c.id, p.name, p.description, p.price, p.status, p.display_order
  FROM (VALUES
        ('Món chính',  'Phở bò tái',          'Phở bò tái nạm',               65000, 'AVAILABLE', 1),
        ('Món chính',  'Bún chả Hà Nội',      'Bún chả kèm nem',              60000, 'AVAILABLE', 2),
        ('Món chính',  'Cơm tấm sườn bì chả', 'Cơm tấm Sài Gòn',              55000, 'AVAILABLE', 3),
        ('Món chính',  'Mì xào hải sản',      'Món theo mùa, đang hết',       75000, 'SOLD_OUT',  4),
        ('Khai vị',    'Gỏi cuốn tôm thịt',   '2 cuốn',                       35000, 'AVAILABLE', 1),
        ('Khai vị',    'Chả giò',             '4 cuốn',                       40000, 'AVAILABLE', 2),
        ('Đồ uống',    'Cà phê sữa đá',       NULL,                           29000, 'AVAILABLE', 1),
        ('Đồ uống',    'Trà đào cam sả',      NULL,                           39000, 'AVAILABLE', 2),
        ('Đồ uống',    'Nước cam ép',         NULL,                           35000, 'AVAILABLE', 3),
        ('Đồ uống',    'Trà đá',              'Miễn phí (thử món giá 0)',         0, 'AVAILABLE', 4),
        ('Tráng miệng','Chè khúc bạch',       NULL,                           30000, 'AVAILABLE', 1),
        ('Tráng miệng','Bánh flan',           'Món ẩn để thử HIDDEN',         20000, 'HIDDEN',    2)
       ) AS p(category_name, name, description, price, status, display_order)
  JOIN category c ON c.name = p.category_name;

-- 6. Nhóm option ---------------------------------------------------------------------
INSERT INTO option_group (name, selection_type, is_required, min_select, max_select, display_order) VALUES
    ('Kích cỡ',   'SINGLE',   true,  1, 1, 1),   -- chọn 1, bắt buộc
    ('Mức đường', 'SINGLE',   true,  1, 1, 2),   -- chọn 1, bắt buộc
    ('Mức đá',    'SINGLE',   false, 0, 1, 3),   -- chọn 1, không bắt buộc
    ('Topping',   'MULTIPLE', false, 0, 3, 4),   -- chọn nhiều, tối đa 3
    ('Thêm món',  'MULTIPLE', false, 0, NULL, 5);-- chọn nhiều, không giới hạn

INSERT INTO option_item (option_group_id, name, extra_price, display_order)
SELECT g.id, i.name, i.extra_price, i.display_order
  FROM (VALUES
        ('Kích cỡ',   'Nhỏ',            0,     1),
        ('Kích cỡ',   'Vừa',            5000,  2),
        ('Kích cỡ',   'Lớn',            10000, 3),
        ('Mức đường', '0%',             0,     1),
        ('Mức đường', '50%',            0,     2),
        ('Mức đường', '100%',           0,     3),
        ('Mức đá',    'Ít đá',          0,     1),
        ('Mức đá',    'Không đá',       0,     2),
        ('Topping',   'Trân châu',      8000,  1),
        ('Topping',   'Thạch đào',      8000,  2),
        ('Topping',   'Kem cheese',     12000, 3),
        ('Thêm món',  'Thêm thịt',      20000, 1),
        ('Thêm món',  'Thêm trứng',     8000,  2),
        ('Thêm món',  'Thêm rau',       0,     3)
       ) AS i(group_name, name, extra_price, display_order)
  JOIN option_group g ON g.name = i.group_name;

-- 7. Gắn option vào món (N-N) -----------------------------------------------------
INSERT INTO product_option_group (product_id, option_group_id, display_order)
SELECT p.id, g.id, l.display_order
  FROM (VALUES
        ('Phở bò tái',          'Kích cỡ',   1),
        ('Phở bò tái',          'Thêm món',  2),
        ('Bún chả Hà Nội',      'Thêm món',  1),
        ('Cơm tấm sườn bì chả', 'Thêm món',  1),
        ('Cà phê sữa đá',       'Kích cỡ',   1),
        ('Cà phê sữa đá',       'Mức đường', 2),
        ('Cà phê sữa đá',       'Mức đá',    3),
        ('Trà đào cam sả',      'Kích cỡ',   1),
        ('Trà đào cam sả',      'Mức đường', 2),
        ('Trà đào cam sả',      'Mức đá',    3),
        ('Trà đào cam sả',      'Topping',   4),
        ('Nước cam ép',         'Mức đường', 1),
        ('Nước cam ép',         'Mức đá',    2)
       ) AS l(product_name, group_name, display_order)
  JOIN product p      ON p.name = l.product_name
  JOIN option_group g ON g.name = l.group_name;
