-- =============================================================================
-- QR Order - V4__add_bank_transfer_settings.sql
-- PostgreSQL 16 | Flyway | Tính năng VietQR (thu ngân xác nhận tay)
--   Thêm tài khoản ngân hàng nhận tiền vào restaurant_settings để dựng mã VietQR
--   hiển thị cho khách (Customer Web) và thu ngân (Staff App). Không sửa V1-V3.
--   Hệ thống KHÔNG tự động đối soát: thu ngân vẫn xác nhận nhận tiền bằng tay.
-- Chạy được ở mọi môi trường (production: V1 -> V3 -> V4; dev: V1 -> V2 -> V3 -> V4).
-- =============================================================================

ALTER TABLE restaurant_settings
    ADD COLUMN bank_bin          VARCHAR(6),
    ADD COLUMN bank_account_no   VARCHAR(19),
    ADD COLUMN bank_account_name VARCHAR(100);

-- Hoặc chưa cấu hình cả ba (NULL), hoặc đủ cả ba: không có trạng thái cấu hình dở dang.
ALTER TABLE restaurant_settings
    ADD CONSTRAINT ck_restaurant_settings_bank_all_or_none CHECK (
        (bank_bin IS NULL AND bank_account_no IS NULL AND bank_account_name IS NULL)
        OR (bank_bin IS NOT NULL AND bank_account_no IS NOT NULL AND bank_account_name IS NOT NULL)),
    -- Mã BIN ngân hàng theo chuẩn NAPAS: đúng 6 chữ số.
    ADD CONSTRAINT ck_restaurant_settings_bank_bin CHECK (bank_bin IS NULL OR bank_bin ~ '^[0-9]{6}$'),
    -- Số tài khoản/thẻ nhận: 6-19 chữ số.
    ADD CONSTRAINT ck_restaurant_settings_bank_account_no CHECK (bank_account_no IS NULL OR bank_account_no ~ '^[0-9]{6,19}$'),
    ADD CONSTRAINT ck_restaurant_settings_bank_account_name CHECK (
        bank_account_name IS NULL OR (char_length(btrim(bank_account_name)) BETWEEN 1 AND 100));

COMMENT ON COLUMN restaurant_settings.bank_bin IS 'Mã BIN ngân hàng nhận tiền (6 chữ số). NULL = chưa cấu hình, hệ thống không hiển thị VietQR.';
COMMENT ON COLUMN restaurant_settings.bank_account_no IS 'Số tài khoản nhận tiền (6-19 chữ số). Cùng bank_bin/bank_account_name: đủ cả ba hoặc cùng NULL.';
COMMENT ON COLUMN restaurant_settings.bank_account_name IS 'Tên chủ tài khoản nhận tiền (hiển thị cho khách và thu ngân).';
