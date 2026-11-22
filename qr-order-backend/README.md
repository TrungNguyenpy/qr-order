# qr-order-backend

Backend API của hệ thống QR Order: Kotlin 1.9.25, Spring Boot 3.5.16, Java 21, PostgreSQL 16, Flyway, Spring Security (JWT), WebSocket STOMP, SpringDoc OpenAPI.

## Chạy nhanh

```bash
# Từ thư mục gốc repository
cp .env.example .env                    # điền DB_PASSWORD, JWT_SECRET, QR_TOKEN_SECRET (openssl rand -base64 48), QR_BASE_URL và INITIAL_ADMIN_*
docker compose up -d --build            # postgres:16-alpine + backend (profile dev)
curl http://localhost:8080/actuator/health
# Swagger UI (profile dev): http://localhost:8080/swagger-ui.html
```

Chạy backend trực tiếp (PostgreSQL đã chạy sẵn):

```bash
cd qr-order-backend
export SPRING_PROFILES_ACTIVE=dev DB_URL=jdbc:postgresql://localhost:5432/qr_order DB_USERNAME=qr_order \
       DB_PASSWORD=... JWT_SECRET=...
./gradlew bootRun
```
<!-- Dán đoạn này vào qr-order-backend/README.md, ngay sau mục "Chạy nhanh", rồi xóa file snippet này. -->

## Chạy cùng Customer Web (môi trường dev cục bộ)

Dùng `docker compose` (không dùng `./gradlew bootRun` trần: nó không có biến môi trường nên dễ kết nối nhầm PostgreSQL cài sẵn trên máy ở cổng 5432, lỗi `role "qr_order" does not exist`).

1. Trong `.env` ở thư mục gốc repository, ngoài các biến bắt buộc, thêm **origin của Customer Web** (nếu thiếu, trình duyệt bị chặn CORS và web báo "Không thể kết nối tới máy chủ"):

   ```env
   CORS_ALLOWED_ORIGINS=http://localhost:3000
   WS_ALLOWED_ORIGINS=http://localhost:3000
   ```

   Nhiều origin cách nhau bằng dấu phẩy, không có `/` cuối. Thử trên điện thoại qua mạng LAN thì thêm `http://<IP-máy>:3000` vào cả hai biến.
2. Chạy hoặc tạo lại container để nhận biến mới: `docker compose up -d --build` (đã chạy sẵn thì `docker compose up -d --force-recreate backend`).a
3. Cổng trên máy host do `SERVER_PORT` trong `.env` quyết định (ví dụ `8081`; trong container luôn là `8080`). PostgreSQL của docker compose ở cổng host `5433`, database `qr_order_db`.
4. `customer-web/.env.local` phải trỏ đúng cổng đó, rồi chạy lại `npm run dev`:

   ```env
   NEXT_PUBLIC_API_URL=http://localhost:8081
   NEXT_PUBLIC_WS_URL=ws://localhost:8081/ws/websocket
   ```

   `ws://.../ws/websocket` là WebSocket thuần (khuyến nghị). `http://.../ws` dùng SockJS, trình duyệt có thể cảnh báo `unload is not allowed`.
5. Mở `http://localhost:3000/t/dev-qr-T01` (QR seed dev của bàn T01).

## Profile

| Profile | Flyway locations | Ghi chú |
| --- | --- | --- |
| `dev` | `db/migration` + `db/seed-dev` (V1 → V2 → V3) | Seed VAT 8% thử nghiệm, 11 bàn, QR dev `/t/dev-qr-T01`..., menu mẫu; không có tài khoản (ADMIN đầu tiên tạo từ `INITIAL_ADMIN_*`) |
| `prod` | chỉ `db/migration` (V1 → V3) | `ddl-auto: validate`, OpenAPI tắt (bật bằng `OPENAPI_ENABLED=true`) |
| `test` | như dev | Dùng trong integration test (Testcontainers) |

Hibernate luôn ở chế độ `validate`: schema chỉ do Flyway quản lý. Migration mới đánh số từ **V4**, không sửa V1–V3.

## Kiểm thử

```bash
./gradlew test      # cần Docker đang chạy (Testcontainers kéo image postgres:16-alpine)
```

| Nhóm | Lớp test |
| --- | --- |
| Flyway + Hibernate validate (đường dev và prod) | `DevMigrationAndSchemaIT`, `ProductionMigrationIT` |
| Refresh token rotation, trigger DEFERRABLE lúc commit | `RefreshTokenRotationIT` |
| Payment/Refund: bất biến lúc commit, giới hạn tiền, đồng thời | `PaymentAndRefundTriggerIT` |
| Lịch sử trạng thái món (3 cột người thao tác) | `OrderItemStatusHistoryIT` |
| Security, RBAC, permission động, định dạng lỗi | `SecurityAndErrorHandlingIT` |
| Auth API: login, refresh/reuse, logout, me, đổi mật khẩu (GĐ1) | `AuthApiIT` |
| Quản lý user, khóa/mở khóa, reset mật khẩu, đổi role, SRS §12.4 #1–#3, #5, #6 (GĐ1) | `UserManagementIT` |
| Cấp/thu hồi permission, tự cấp, SRS §12.4 #4, #7 (GĐ1) | `PermissionManagementIT` |
| ADMIN đầu tiên từ biến môi trường (GĐ1) | `InitialAdminRunnerIT` |
| restaurant_settings: BR-P03, BR-P04, optimistic lock, audit (GĐ1) | `RestaurantSettingsIT` |
| AuditService: đủ trường, MANDATORY transaction, IP/correlationId (GĐ1) | `AuditServiceIT` |
| Danh mục: CRUD, tìm kiếm/phân trang, ẩn/hiện, sắp xếp, 409, @Version đồng thời, audit, RBAC, CHECK/unique (GĐ2) | `CategoryApiIT` |
| Món: CRUD, lọc, đổi giá (audit), trạng thái + ProductSoldOutEvent, gán nhóm option, CHECK (GĐ2) | `ProductApiIT` |
| Ảnh món: upload/gỡ, xóa ảnh cũ best-effort, 413/415/502/503, ImageStorageService giả (GĐ2) | `ProductImageIT` |
| Nhóm option / lựa chọn: BR-O03, tên duy nhất, ngừng dùng, CHECK (GĐ2) | `OptionGroupApiIT` |
| Menu công khai cho khách (GĐ2) | `PublicMenuIT` |
| `/topic/menu` sau commit, rollback không gửi, hợp đồng BR-O13 cho GĐ4 (GĐ2) | `MenuEventsIT` |
| Bàn: CRUD, mã duy nhất, lọc, sơ đồ bàn, version đồng thời, ngừng dùng (BR-S08), dọn bàn CLEANING → AVAILABLE, `/topic/tables` (GĐ3) | `DiningTableApiIT` |
| QR: sinh (chỉ lưu SHA-256), xoay, thu hồi, lịch sử, PNG giải mã lại bằng ZXing, PDF đơn lẻ/hàng loạt, BE-07 dùng index (GĐ3) | `QrCodeApiIT` |
| Join phiên: tạo/tham gia, BILLING chỉ xem, QR xoay/thu hồi, bàn ngừng dùng/đang dọn, join đồng thời (1 phiên), rate limit 429, PIN, TTL (GĐ3) | `TableSessionJoinIT` |
| Guest JWT bị chặn ngay khi phiên đóng (BE-05), gia hạn token, nhập PIN (GĐ3) | `GuestSessionIT` |
| Đặt món: 3 chế độ duyệt đợt, snapshot giá/option, idempotency, rate limit, PIN, BILLING/LOCKED, SOLD_OUT, BR-O02/O03, BE-18, giới hạn đợt PENDING, real-time (GĐ4) | `OrderPlacementIT` |
| Claim đợt (đồng thời, TTL, Manager giành quyền), xác nhận/từ chối đợt và món, phân quyền (GĐ4) | `OrderBatchProcessingIT` |
| KDS, CONFIRMED → PREPARING → READY → SERVED, bếp từ chối (wasted), lịch sử actor, trigger DB (GĐ4) | `KitchenFlowIT` |
| Khách hủy món, yêu cầu hủy PREPARING, Manager duyệt/từ chối + audit, quyền `ITEM_CANCEL_APPROVE` (GĐ4) | `OrderCancellationIT` |
| SOLD_OUT tự REJECT món PENDING/CONFIRMED của Order OPEN, bỏ qua LOCKED - BR-O13 (GĐ4) | `OrderSoldOutIT` |
| Force-close: PENDING → REJECTED, WRITTEN_OFF (thất thu + phí phục vụ), VOIDED (GĐ4) | `OrderForceCloseIT` |
| Unit: chế độ duyệt, validator option, thất thu, đồng bộ trạng thái đợt, `changeQuantity` (GĐ4) | `OrderPoliciesTest`, `BatchStatusSynchronizerTest`, `OrderItemTest` |
| Tính tiền: snapshot BR-P02, mã `BILL-...`, Order LOCKED / phiên BILLING / bàn PAYMENT_PENDING, chặn `VAT_NOT_CONFIGURED`, `BATCH_PENDING` (kể cả đợt đang claim), `NO_BILLABLE_ITEMS`, quyền, REQUEST_PAYMENT tự RESOLVED, giảm giá BR-P07, phát hành lại Bill BE-29 (GĐ5a) | `BillingFlowIT` |
| Thanh toán: chia tiền, PENDING → SUCCESS/FAILED, mã phiếu chỉ khi SUCCESS, idempotent `requestId`, thu đồng thời không vượt Total, provider txn trùng, dữ liệu in phiếu (GĐ5a) | `PaymentFlowIT` |
| Tự đóng phiên khi Bill PAID và món đã xong, giữ BILLING khi món còn nấu, `cleaning_auto_release`, Bill Total 0 (GĐ5a) | `SessionSettlementIT` |
| Staff mở lại phiên P1 (BR-P08 a): chặn khi có Payment SUCCESS/PENDING, FAILED không chặn, quyền, version (GĐ5a) | `SessionReopenIT` |
| Force-close có Bill - SRS §12.2: WRITTEN_OFF + thất thu = Total − đã thu, chặn Payment PENDING / Bill VOID_PENDING, Order PAID giữ nguyên (GĐ5a) | `BillingForceCloseIT` |
| ServiceCall: 1 yêu cầu mở mỗi loại (cả khi đồng thời), ACKNOWLEDGED/RESOLVED, MANAGER_SUPPORT chỉ Manager, tự RESOLVED khi force-close (GĐ5a) | `ServiceCallIT` |
| Unit: giảm giá %/số tiền, phí phục vụ, VAT trích ngược HALF_UP, BR-P11 (GĐ5a; đã chạy 10/10 bằng trình biên dịch Kotlin độc lập) | `BillCalculatorTest` |
| Void: thu ròng 0 → VOIDED ngay + Order chờ Bill thay thế, Void sau khi phiên CLOSED, Refund theo SRS §12.1 (hạn mức BR-P13, mã `RF-...`), chỉ khi VOID_PENDING, idempotent, Refund đồng thời, rút Void (P2), phân quyền, phiếu hoàn tiền (GĐ5b, **chưa chạy**) | `VoidRefundIT` |
| Bill thay thế (O4/P-3) có giảm giá → thu → đóng phiên, Order chờ vẫn LOCKED, các trường hợp bị từ chối, Manager mở lại phiên BR-P08 b, force-close Order chờ Bill thay thế (GĐ5b, **chưa chạy**) | `ReplacementBillIT` |
| Unit: `MoneySummary.remainingRefundable`/`fullyRefunded` theo bảng SRS §12.1 (GĐ5b; đã chạy 2/2 bằng trình biên dịch Kotlin độc lập) | `MoneySummaryTest` |
| Nhân viên mở phiên, danh sách phiên, force-close (quyền, lý do, audit, CLEANING), chặn khi còn order, điểm mở rộng (GĐ3) | `SessionLifecycleIT` |
| Scheduler: EXPIRED vs needs_attention, không báo lặp, BILLING không hết hạn, timeout từ settings (GĐ3) | `SessionExpiryIT` |
| Unit | `InMemoryJoinRateLimiterTest`, `QrTokenCodecTest`, `QrRenderersTest` (GĐ3), `OrderItemTest`, `StompTopicAuthorizerTest`, `ErrorCodeCoverageTest`, `DatabaseErrorTranslatorTest`, `JwtServiceTest`, `PasswordPolicyTest`, `RestaurantSettingsRulesTest`, `AuditValueSanitizerTest`, `ClientRequestInfoTest`, `ImageUploadValidatorTest`, `CloudinarySignerTest`, `CloudinaryUrlsTest`, `CloudinaryImageStorageServiceTest` (MockRestServiceServer), `OptionGroupRulesTest`, `MenuSpecificationsTest` |

Integration test không dùng `@Transactional` rollback: trigger DEFERRABLE chỉ chạy lúc COMMIT (DATABASE_DESIGN.md §5.3 (1)).

Công cụ phụ (không cần JVM):

- `tools/schema-test/run_all.sh`: chạy V1/V2/V3 bằng psql và 181 case schema (Python + psycopg).
- `tools/verify/check_entity_mapping.py`: đối chiếu tĩnh 26 entity Kotlin với catalog PostgreSQL.
- `tools/verify/phase1_sql_check.py`: 26 case cho các câu SQL Giai đoạn 1 (UPDATE gốc, audit, settings, DbCleaner) trên database dev V1→V2→V3.
- `tools/verify/concurrent_admin_demote.py`: hai ADMIN hạ quyền nhau đồng thời (20 vòng), kiểm tra BR-A09 và không deadlock.
- `tools/verify/phase2_sql_check.py`: 12 case cho SQL/ràng buộc Giai đoạn 2 (DbCleaner chụp/trả seed menu, CHECK và unique của menu, bumpVersion, truy vấn menu công khai, hợp đồng BR-O13). Đối số: chuỗi kết nối psycopg (mặc định `host=/tmp port=55432 user=postgres dbname=qr`).
- `tools/verify/phase5a_flows.py`: 33 kịch bản SQL Giai đoạn 5a trên PostgreSQL thật (V1→V2→V3, COMMIT thật): tạo Bill, chia tiền PENDING/SUCCESS, đóng phiên, phát hành lại Bill, mở lại phiên (kể cả từ Order PAID Total 0), force-close WRITTEN_OFF, ServiceCall, provider txn, 2 kiểu thu tiền đồng thời. Biến môi trường `PGHOST`, `PGPORT`, `PGUSER`, `PGPASSWORD`, `PGDATABASE`.
- `tools/verify/phase5b_flows.py`: 23 kịch bản SQL Giai đoạn 5b trên PostgreSQL thật (COMMIT thật): Void ngay/VOID_PENDING, Refund theo SRS §12.1, hoàn đủ → VOIDED, rút Void, Bill thay thế, BR-P08 b, 2 Refund đồng thời.
- `tools/verify/phase3_e2e.py`, `tools/verify/phase3_stomp.py`: kiểm chứng đầu-cuối Giai đoạn 3 trên backend **đang chạy** (profile dev, `SESSION_EXPIRY_CHECK_INTERVAL_MS=3000`) + PostgreSQL: 100 kiểm tra REST (bàn, QR, PNG/PDF, join đồng thời, rate limit, PIN, BE-05, force-close, Scheduler) và 9 kiểm tra STOMP (`/topic/tables`, `/topic/session/{id}`, `/topic/staff/orders`, CONNECT bị từ chối khi phiên đã đóng). Cần `pip install requests psycopg2-binary pypdf pillow websocket-client`.

## Cấu trúc package (modular monolith, SRS §11)

```
com.qrorder
  common/        api (ApiResponse, PageResponse), error (ErrorCode, GlobalExceptionHandler, DatabaseErrorTranslator),
                 web (CorrelationId, ClientRequestInfo), config (Clock, OpenAPI, Scheduling), time (DbClock), domain (enum dùng chung),
                 settings (restaurant_settings: service + API), audit (audit_log + AuditService), sequence (daily_sequence)
  auth/          domain (users, user_permission, refresh_token), repository,
                 security (JWT, filter, RBAC, PermissionGuard), token (RefreshTokenService),
                 password (chính sách mật khẩu, xác thực lại), service (Auth, User, Permission),
                 dto, api (AuthController, UserController), bootstrap (InitialAdminRunner)
  menu/          domain (category, product, option_group, option_item, product_option_group), repository, dto,
                 service (Category, Product, ProductImage, OptionGroup, PublicMenu), api (controller),
                 image (ImageStorageService, Cloudinary REST client, kiểm tra ảnh), event (ProductSoldOutEvent, MenuChangedEvent)
  table/         domain (dining_table, qr_code, table_session, session_device), repository, dto,
                 service (DiningTable, QrCode, TableSession, SessionExpiry + Scheduler), api (controller),
                 qr (QrTokenCodec, ZXing PNG, OpenPDF), session (JoinRateLimiter, điểm mở rộng force-close/hết hạn,
                 GuestSessionGate cho BE-05), event (TableChangedEvent, SessionChangedEvent)
  order/         domain (orders, order_batch, order_item, order_item_option, item_cancel_request, order_item_status_history),
                 repository, dto, service (OrderPlacement, OrderBatch, OrderItem, OrderQuery, chính sách/validator/đồng bộ đợt),
                 api (Guest/Staff/Kitchen controller), event (OrderChangedEvent), listener (ProductSoldOutOrderListener - BR-O13),
                 session (kiểm tra đơn chưa thanh toán, VOIDED Order rỗng khi hết hạn, OrderForceCloseHandler khi force-close)
  billing/       domain (bill, payment_transaction, refund_transaction, transaction_status_history, BillCalculator), repository, dto,
                 service (Bill, Payment, Receipt, SessionSettlement - tự đóng phiên, SessionReopen - P1), api (controller),
                 session (BillingForceCloseValidator/Listener - BE-22), event (BillChangedEvent)
  notification/  domain (service_call), servicecall (ServiceCallService + API + /topic sau commit + tự RESOLVED khi phiên đóng),
                 billing (BillingTopicNotifier: BILL_CHANGED tới /topic/session/{id}, /topic/staff/orders),
                 websocket (STOMP /ws), menu (MenuTopicNotifier: /topic/menu sau commit),
                 table (TableTopicNotifier: /topic/tables, /topic/session/{id}, /topic/staff/orders sau commit),
                 order (OrderTopicNotifier: /topic/session/{id}, /topic/kitchen/orders, /topic/staff/orders, /topic/staff/ready sau commit)
  report/        (chỉ đọc, bắt đầu ở Giai đoạn 7)
```

Quy tắc: module khác chỉ tham chiếu nhau bằng ID và gọi qua Service; entity giữ khóa ngoại dạng `Long`.


## Quy ước API

- Mọi phản hồi theo `ApiResponse<T>`: `{ success, data | error{code,message,details}, correlationId, timestamp }`.
- `error.code` lấy từ `ErrorCode`. Mã trigger PostgreSQL (SQLSTATE P0001, thông điệp `MA_LOI: ...`) được ánh xạ trực tiếp: 409 xung đột trạng thái (vd. `LAST_ACTIVE_ADMIN`, `ORDER_NOT_OPEN`, `INVALID_ORDER_ITEM_TRANSITION`), 422 vượt giới hạn tiền (`PAYMENT_EXCEEDS_REMAINING`, `REFUND_EXCEEDS_REFUNDABLE`), 500 vi phạm bất biến do lỗi service (vd. `REFRESH_TOKEN_ROTATION_INCOMPLETE`, `BILL_PAYMENT_STATUS_MISMATCH`). Không trả thông điệp gốc của database.
- Header `X-Correlation-Id` (nhận từ client nếu hợp lệ, nếu không thì sinh mới) có trong mọi response và mọi dòng log.

## Bảo mật

- JWT HMAC, stateless. Staff token: `sub`=userId, `role`; Guest token: `sid`, `did`.
- Guest JWT (GĐ3, BE-05): mỗi request REST và mỗi STOMP CONNECT đối chiếu `table_session` + `session_device`; phiên không còn OPEN/BILLING → 401 `SESSION_CLOSED` ngay, thiết bị không thuộc phiên → 401 `TOKEN_INVALID`.
- Mỗi request đối chiếu `users` để khóa tài khoản/đổi role có hiệu lực ngay (BR-A04).
- Role: MANAGER gồm quyền STAFF và KITCHEN; ADMIN không kế thừa (O3, BR-A06).
- Permission nghiệp vụ: `@PreAuthorize("@perm.has('PAYMENT_PROCESS')")`; MANAGER có mặc định cả 8, các role khác cần bản cấp còn hiệu lực trong `user_permission`.
- STOMP: xác thực ở CONNECT (`Authorization: Bearer ...`), phân quyền SUBSCRIBE theo topic (SRS §11.4).
- ADMIN đầu tiên: `INITIAL_ADMIN_USERNAME`, `INITIAL_ADMIN_PASSWORD`, `INITIAL_ADMIN_FULL_NAME`; chỉ dùng khi bảng `users` trống, thiếu biến thì chỉ cảnh báo. Mật khẩu không bao giờ được ghi log; nên xóa `INITIAL_ADMIN_PASSWORD` khỏi `.env` sau lần khởi tạo.
- Audit (`AuditService`): mọi thao tác quản trị ghi `audit_log` cùng transaction với thay đổi, kèm correlationId và IP; khóa chứa `password`/`secret`/`token` bị loại khỏi `old_value`/`new_value`.

## API Giai đoạn 1 (Auth, User, Permission, Settings)

| Method | Đường dẫn | Ai gọi | Ghi chú |
| --- | --- | --- | --- |
| POST | `/api/v1/auth/login` | public | `{username, password}` → access + refresh token, `user` kèm permission hiệu lực. Sai: 401 `INVALID_CREDENTIALS`; bị khóa/ngừng dùng: 401 `ACCOUNT_DISABLED` |
| POST | `/api/v1/auth/refresh` | public | `{refreshToken}` → cặp token mới (rotation). 401 `REFRESH_TOKEN_INVALID` / `REFRESH_TOKEN_EXPIRED` / `REFRESH_TOKEN_REUSED` (đã thu hồi cả family) / `ACCOUNT_DISABLED` |
| POST | `/api/v1/auth/logout` | public | `{refreshToken}` → thu hồi family (`LOGOUT`); luôn trả thành công |
| GET | `/api/v1/auth/me` | Staff (mọi role) | Thông tin tài khoản + permission hiệu lực (MANAGER đủ 8) |
| POST | `/api/v1/auth/change-password` | Staff | `{currentPassword, newPassword}`; thu hồi mọi refresh token → đăng nhập lại |
| GET | `/api/v1/users?role=&status=&q=&page=&size=` | ADMIN | Danh sách phân trang (`page` từ 0, `size` ≤ 100) |
| GET / POST / PUT | `/api/v1/users`, `/api/v1/users/{id}` | ADMIN | Tạo (role ADMIN cần `currentPassword` + `reason`), sửa họ tên (gửi `version`) |
| PATCH | `/api/v1/users/{id}/status` | ADMIN | `{status, reason?}` với status ACTIVE / LOCKED / DISABLED; không xóa cứng user |
| PATCH | `/api/v1/users/{id}/role` | ADMIN | `{role, reason, currentPassword}` (xác thực lại); audit `USER_ROLE_CHANGED`; luôn còn ≥ 1 ADMIN (409 `LAST_ACTIVE_ADMIN`) |
| POST | `/api/v1/users/{id}/reset-password` | ADMIN | `{newPassword, reason?}`; không dùng cho chính mình |
| GET / POST | `/api/v1/users/{id}/permissions` | ADMIN | Xem (`includeRevoked=true` để xem lịch sử) / cấp `{permission, reason, currentPassword?}` - tự cấp bắt buộc `currentPassword` |
| POST | `/api/v1/users/{id}/permissions/{permission}/revoke` | ADMIN | `{reason, currentPassword?}` - tự thu hồi bắt buộc `currentPassword` |
| GET / PUT | `/api/v1/settings` | ADMIN | restaurant_settings; PUT gửi đủ trường + `version` (lệch: 409 `CONCURRENT_MODIFICATION`) |

Hợp đồng cho client:

- Chính sách mật khẩu: ≥ 8 ký tự, ít nhất 1 chữ hoa, 1 chữ thường, 1 chữ số (tối đa 72 byte do BCrypt). Không bắt buộc đổi mật khẩu ở lần đăng nhập đầu.
- Xác thực lại sai mật khẩu trả **403** `REAUTHENTICATION_FAILED` (không phải 401) để client không tự refresh/đăng xuất.
- Interceptor tự refresh khi gặp 401 không áp dụng cho `/api/v1/auth/login|refresh|logout`; mọi request refresh phải tuần tự (BE-06).
- Tỷ lệ (VAT, phí phục vụ) là số thập phân: `0.08` = 8%, tối đa 4 chữ số thập phân, `0 ≤ x < 1`. `vatRate: null` = chưa cấu hình (chặn tạo Bill). Phí phục vụ > 0 cần `serviceFeeTaxable` khác null (BR-P04).
- Body request được đọc và kiểm tra trước `@PreAuthorize`: body sai định dạng trả 400 kể cả khi người gọi không có quyền.

## API Giai đoạn 2 (Menu)

Phân quyền: **ADMIN, MANAGER** quản lý; **STAFF, KITCHEN** chỉ xem - ngoại lệ theo SRS FR-PRD-02/§10: STAFF/KITCHEN được đổi món `AVAILABLE` ↔ `SOLD_OUT`. Mọi chuyển liên quan `HIDDEN` chỉ ADMIN/MANAGER. Không có API xóa (BR-A03): ẩn/ngừng dùng thay cho xóa.

| Method | Đường dẫn | Ai gọi | Ghi chú |
| --- | --- | --- | --- |
| GET | `/api/v1/categories?q=&visible=&page=&size=` | 4 role | Theo `displayOrder`, `id`; `size` ≤ 100 |
| GET | `/api/v1/categories/{id}` | 4 role | |
| POST | `/api/v1/categories` | ADMIN, MANAGER | `{name, description?, displayOrder?, visible?}`; trùng tên (không phân biệt hoa thường): 409 `CATEGORY_NAME_TAKEN` |
| PUT | `/api/v1/categories/{id}` | ADMIN, MANAGER | `{name, description?, displayOrder, version}` |
| PATCH | `/api/v1/categories/{id}/visibility` | ADMIN, MANAGER | `{visible, version}` (ẩn/hiện) |
| PUT | `/api/v1/categories/display-order` | ADMIN, MANAGER | `{items: [{id, displayOrder, version}]}` - sắp xếp nhiều danh mục, nguyên tử |
| GET | `/api/v1/products?q=&categoryId=&status=&page=&size=` | 4 role | Danh sách không kèm nhóm option (`optionGroups: null`) |
| GET | `/api/v1/products/{id}` | 4 role | Kèm `optionGroups` đã gán theo thứ tự |
| POST | `/api/v1/products` | ADMIN, MANAGER | `{categoryId, name, description?, price, displayOrder?, status?}`; giá VND đã gồm VAT, ≥ 0; danh mục không tồn tại: 422 `INVALID_REFERENCE` |
| PUT | `/api/v1/products/{id}` | ADMIN, MANAGER | `{categoryId, name, description?, price, displayOrder, version}`; đổi giá ghi audit `PRODUCT_PRICE_CHANGED` |
| PATCH | `/api/v1/products/{id}/status` | 4 role (HIDDEN: ADMIN, MANAGER) | `{status, version, reason?}`; sang `SOLD_OUT` phát `ProductSoldOutEvent` (BR-O13) |
| PUT | `/api/v1/products/{id}/option-groups` | ADMIN, MANAGER | `{optionGroupIds: [...theo thứ tự], version}` - gán/gỡ/sắp xếp một lần; tăng version của món |
| POST | `/api/v1/products/{id}/image?version=` | ADMIN, MANAGER | multipart `file`: JPEG/PNG/WebP, ≤ 5MB. 415 `UNSUPPORTED_IMAGE_TYPE`, 413 `IMAGE_TOO_LARGE`, 503 `IMAGE_STORAGE_NOT_CONFIGURED`, 502 `IMAGE_UPLOAD_FAILED` |
| DELETE | `/api/v1/products/{id}/image?version=` | ADMIN, MANAGER | Gỡ ảnh; ảnh trên Cloudinary xóa best-effort sau commit |
| GET | `/api/v1/option-groups?q=&active=&page=&size=` | 4 role | Kèm toàn bộ lựa chọn |
| GET | `/api/v1/option-groups/{id}` | 4 role | |
| POST | `/api/v1/option-groups` | ADMIN, MANAGER | `{name, selectionType: SINGLE/MULTIPLE, required, minSelect?, maxSelect?, displayOrder?}` |
| PUT | `/api/v1/option-groups/{id}` | ADMIN, MANAGER | như trên + `displayOrder`, `version` |
| PATCH | `/api/v1/option-groups/{id}/active` | ADMIN, MANAGER | `{active, version}` |
| POST | `/api/v1/option-groups/{groupId}/items` | ADMIN, MANAGER | `{name, extraPrice?, displayOrder?}`; trùng tên lựa chọn đang dùng trong nhóm: 409 `OPTION_ITEM_NAME_TAKEN` |
| PUT | `/api/v1/option-groups/{groupId}/items/{itemId}` | ADMIN, MANAGER | `{name, extraPrice, displayOrder, version}` |
| PATCH | `/api/v1/option-groups/{groupId}/items/{itemId}/active` | ADMIN, MANAGER | `{active, version}` |
| GET | `/api/v1/public/menu` | public | Menu cho khách (xem dưới) |

Hợp đồng cho client:

- Tên trường theo schema: danh mục ẩn/hiện là `visible`; nhóm/lựa chọn ngừng dùng là `active`; phụ thu lựa chọn là `extraPrice`.
- Nhóm option (BR-O03): `required` ⇔ `minSelect ≥ 1`; `SINGLE` ⇒ `maxSelect = 1`; `maxSelect` null = không giới hạn, khác null thì ≥ 1 và ≥ `minSelect`. Bỏ trống `minSelect`/`maxSelect` thì server điền theo `required`/`selectionType`.
- Menu công khai: chỉ danh mục `visible`, món không `HIDDEN` (`SOLD_OUT` vẫn trả về với `available: false` để hiển thị mờ), nhóm option `active` còn ít nhất một lựa chọn `active`, lựa chọn `active`; `displayOrder` của nhóm là thứ tự trong món. Không có trường nội bộ (version, người sửa).
- `/topic/menu` (STOMP): `{eventId, type: "MENU_CHANGED", entityType, entityId, change, version, productStatus, price, occurredAt}`, chỉ gửi sau khi transaction commit. `eventId` tăng dần: bỏ qua sự kiện có `eventId` nhỏ hơn sự kiện đã xử lý. `STATUS_CHANGED` của món có thể cập nhật tại chỗ; các loại khác nên tải lại `GET /api/v1/public/menu`.
- Ảnh: biến môi trường `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET`, `CLOUDINARY_FOLDER`, `MENU_IMAGE_MAX_SIZE`. Upload qua Cloudinary Upload API REST có ký SHA-1 (không cần SDK); `secure_url` lưu ở `product.image_url`, `public_id` ở `product.image_public_id` (cột có sẵn từ V1, không cần V4).

## API Giai đoạn 3 (Bàn, QR Code, Phiên bàn)

Phân quyền (SRS §10): CRUD bàn, QR, ngừng dùng bàn: **ADMIN**. Xem bàn/sơ đồ bàn: ADMIN, MANAGER, STAFF. Xác nhận dọn bàn, mở phiên hộ khách, xem phiên: **STAFF, MANAGER** (ADMIN không kế thừa; ADMIN xem phiên khi được cấp `REPORT_VIEW`). Force-close: permission `SESSION_FORCE_CLOSE` (MANAGER có mặc định). Không có API xóa (BR-A03).

| Method | Đường dẫn | Ai gọi | Ghi chú |
| --- | --- | --- | --- |
| GET | `/api/v1/tables?q=&area=&status=&active=&page=&size=` | ADMIN, MANAGER, STAFF | `q` theo mã/tên; theo khu vực, `displayOrder`, `id` |
| GET | `/api/v1/tables/floor-plan` | ADMIN, MANAGER, STAFF | Bàn đang dùng + `hasActiveQr` + `activeSession {id, status, openedAt, lastActivityAt, needsAttention, deviceCount, version}` (FR-TBL-02) |
| GET | `/api/v1/tables/{id}` | ADMIN, MANAGER, STAFF | |
| POST | `/api/v1/tables` | ADMIN | `{code, displayName, area?, capacity 1-100, displayOrder?}`; mã `^[A-Za-z0-9_-]{1,20}$`, trùng (không phân biệt hoa thường): 409 `TABLE_CODE_TAKEN` |
| PUT | `/api/v1/tables/{id}` | ADMIN | `{code, displayName, area?, capacity, displayOrder, version}` |
| PATCH | `/api/v1/tables/{id}/active` | ADMIN | `{active, version}`; ngừng dùng khi bàn không trống: 409 `TABLE_IN_USE` (BR-S08) |
| PATCH | `/api/v1/tables/{id}/status` | STAFF, MANAGER | `{status: "AVAILABLE", version}` - chỉ xác nhận dọn xong `CLEANING → AVAILABLE`; khác: 409 `INVALID_TABLE_TRANSITION` |
| GET | `/api/v1/tables/{id}/qr-code` | ADMIN | QR ACTIVE: `{id, status, createdAt, url, reproducible, ...}`; chưa có: 409 `QR_CODE_NOT_ACTIVE` |
| GET | `/api/v1/tables/{id}/qr-codes` | ADMIN | Lịch sử QR (mới trước; chỉ QR ACTIVE có `url`) |
| POST | `/api/v1/tables/{id}/qr-codes` | ADMIN | Sinh QR (201); đã có QR ACTIVE: 409 `QR_ALREADY_ACTIVE` |
| POST | `/api/v1/tables/{id}/qr-code/rotate` | ADMIN | `{reason?}`; mã cũ `ROTATED` (vô hiệu ngay), tạo mã mới; audit `QR_ROTATED` |
| POST | `/api/v1/tables/{id}/qr-code/revoke` | ADMIN | `{reason}` bắt buộc; `REVOKED`, không tạo mã mới; audit `QR_REVOKED` |
| GET | `/api/v1/tables/{id}/qr-code/image.png?size=&download=` | ADMIN | PNG `size` 128-2048 px (mặc định 512); `Cache-Control: no-store`; audit `QR_EXPORTED` |
| GET | `/api/v1/tables/{id}/qr-code/print.pdf` | ADMIN | PDF A5 một trang (tên bàn, mã bàn, khu vực, QR, hướng dẫn) |
| GET | `/api/v1/qr-codes/print.pdf?tableIds=1&tableIds=2` | ADMIN | PDF hàng loạt (tối đa 200 bàn), mỗi bàn một trang; không truyền `tableIds` = mọi bàn đang dùng có QR ACTIVE |
| POST | `/api/v1/tables/{id}/sessions` | STAFF, MANAGER | Mở phiên hộ khách (FR-SES-02), 201; trả `tablePin` nếu bật PIN; đã có phiên: 409 `SESSION_ALREADY_ACTIVE` |
| GET | `/api/v1/sessions?status=&tableId=&needsAttention=&page=&size=` | STAFF, MANAGER (ADMIN + `REPORT_VIEW`) | Mặc định OPEN + BILLING, mới mở trước |
| GET | `/api/v1/sessions/needs-attention` | như trên | Phiên idle quá hạn còn đơn chưa thanh toán (FR-SES-06), cũ trước |
| GET | `/api/v1/sessions/{id}` | như trên | Chi tiết, gồm `tablePin`, `deviceCount`, `closedBy`, `closeReason` |
| POST | `/api/v1/sessions/{id}/force-close` | `SESSION_FORCE_CLOSE` | `{reason, version?}`; phiên → `FORCE_CLOSED`, bàn → `CLEANING` (hoặc `AVAILABLE` nếu `cleaning_auto_release`); audit `SESSION_FORCE_CLOSED`. Phiên đã kết thúc: 409 `SESSION_FINAL_STATE`; còn order chưa xử lý: 409 `SESSION_HAS_UNSETTLED_ORDER` (GĐ3) |
| POST | `/api/v1/public/sessions/join` | public | `{qrToken, deviceId, pin?}` → Guest JWT (xem dưới) |
| GET | `/api/v1/guest/session` | Guest | Phiên hiện tại: `{sessionId, status, canOrder, table, deviceId, pinRequired, pinVerified}` |
| POST | `/api/v1/guest/session/token` | Guest | Gia hạn Guest JWT khi phiên còn OPEN/BILLING |
| POST | `/api/v1/guest/session/verify-pin` | Guest | `{pin}` 4 số; sai: 422 `TABLE_PIN_INVALID`; giới hạn số lần thử theo phiên |

Hợp đồng cho Customer Web (khách quét QR `https://{domain}/t/{qrToken}`):

- `deviceId`: client tự sinh (khuyến nghị UUID, 8-64 ký tự `[A-Za-z0-9_-]`) và lưu lại trên thiết bị; dùng cùng `deviceId` khi quét lại.
- Kết quả join: `{accessToken, tokenType: "Bearer", expiresAt, newSession, session}`. TTL = `restaurant_settings.guest_jwt_ttl_minutes` (mặc định 15 phút); gọi `POST /api/v1/guest/session/token` trước khi hết hạn, hết hạn thì join lại bằng `qrToken`.
- Lỗi join: 404 `QR_CODE_INVALID` (mã lạ/đã xoay/đã thu hồi - yêu cầu quét lại mã trên bàn), 409 `TABLE_INACTIVE` (bàn tạm ngưng), 409 `TABLE_NOT_READY` (bàn đang dọn), 422 `TABLE_PIN_INVALID`, 429 `RATE_LIMIT_EXCEEDED` kèm header `Retry-After` (giây). Giới hạn = `join_rate_limit_per_minute` (mặc định 10) lần/phút/IP, đếm cả yêu cầu sai.
- `canOrder = false` khi phiên BILLING (chỉ xem đơn, BR-S03). `pinRequired && !pinVerified`: hiển thị ô nhập PIN do nhân viên đọc (lấy ở chi tiết phiên phía nhân viên). Không gửi `pin` ở lần quét đầu khi bàn đang trống (PIN của phiên mới là ngẫu nhiên).
- Mọi 401 `SESSION_CLOSED` (REST) hoặc frame ERROR khi CONNECT STOMP: phiên đã kết thúc → xóa token, về màn hình "Phiên đã kết thúc".
- STOMP: khách subscribe `/topic/session/{sessionId}`. Sự kiện `{eventId, type: "SESSION_CHANGED", sessionId, tableId, tableCode, status, version, change: OPENED|CLOSED, occurredAt}`; `CLOSED` → ngắt kết nối.

Hợp đồng cho Staff App / Admin Dashboard:

- `/topic/tables` (STAFF, MANAGER): `{eventId, type: "TABLE_CHANGED", tableId, code, status, active, version, change: CREATED|UPDATED|ACTIVE_CHANGED|STATUS_CHANGED, sessionId, occurredAt}` sau commit; mất kết nối thì tải lại `GET /api/v1/tables/floor-plan`.
- `/topic/staff/orders` (STAFF, MANAGER): `type: "SESSION_NEEDS_ATTENTION"` khi Scheduler gắn cờ phiên idle còn đơn chưa thanh toán (BR-S07).
- QR: URL đầy đủ có ở `GET /api/v1/tables/{id}/qr-code` (`url`) để client tự render; `reproducible: false` (QR seed dev hoặc đã đổi `QR_TOKEN_SECRET`) → cần xoay mã mới trước khi in. PNG/PDF trả nhị phân; lỗi trả JSON `ApiResponse` như mọi API.

Cấu hình (biến môi trường): `QR_BASE_URL` (địa chỉ Customer Web, không có `/` cuối), `QR_TOKEN_SECRET` (Base64 ≥ 32 byte), `QR_PDF_FONT_PATH` (tùy chọn, mặc định font Liberation Sans có trong OpenPDF), `SESSION_EXPIRY_CHECK_INTERVAL_MS` (mặc định 60000), `SCHEDULING_ENABLED` (mặc định true). Thiếu `QR_BASE_URL`/`QR_TOKEN_SECRET`: API sinh/xoay/in QR trả 503 `QR_NOT_CONFIGURED`, khách vẫn join bằng QR đã có.

Lưu ý triển khai: rate limit theo IP lấy từ `X-Forwarded-For` (do `server.forward-headers-strategy: framework`), nên reverse proxy phía trước **phải ghi đè** header này; không để backend nhận trực tiếp request từ Internet (BE-21).

## API Giai đoạn 4 (Order & Kitchen) - *`COMPLETED` 21/11/2026 (build thật 313/313 PASS)*

Tiền là số nguyên VND; thời điểm ISO-8601; `reasonCode` dạng `UPPER_SNAKE_CASE` (2-50 ký tự). Không đổi schema (không V4).

**Khách** (Guest JWT, `/api/v1/guest/orders`):

| Method | Đường dẫn | Mô tả |
| --- | --- | --- |
| POST | `/api/v1/guest/orders` | Đặt một đợt `{clientRequestId (UUID), items: [{productId, quantity, note?, optionItemIds: []}]}`. 201 khi tạo mới; **200 khi gửi lại cùng `clientRequestId`** (trả đúng kết quả lần đầu, `replayed: true`, không tạo thêm). Nên giữ nguyên `clientRequestId` khi retry do mất mạng |
| GET | `/api/v1/guest/orders` | Order của phiên: các đợt, món, trạng thái, `billableTotal` (tổng món tính tiền: CONFIRMED/PREPARING/READY/SERVED, chưa gồm phí phục vụ) |
| POST | `/api/v1/guest/orders/items/{itemId}/cancel` | Hủy món PENDING/CONFIRMED khi Order còn OPEN, body tùy chọn `{note}` |
| POST | `/api/v1/guest/orders/items/{itemId}/cancel-requests` | Xin hủy món PREPARING/READY `{reason?}` → 201, chờ Manager duyệt; mỗi món một yêu cầu đang chờ |

Quy tắc đặt món: phiên phải OPEN (BILLING chỉ xem); khi `require_table_pin` thiết bị phải đã nhập PIN; tối đa `max_pending_batches_per_session` (3) đợt chờ xác nhận; tối đa 5 lần gọi/phút/phiên (`order_rate_limit_per_minute`, đếm cả lần lỗi, 429 `RATE_LIMIT_EXCEEDED` + `Retry-After`); số lượng 1..`max_item_quantity`; món phải `AVAILABLE`; option theo nhóm (`required`, `minSelect`, `maxSelect`, nhóm không còn lựa chọn active bị ẩn như menu công khai). Giá, tên món, phụ thu option được **chụp lại** lúc đặt.

**Chế độ duyệt đợt** (`restaurant_settings.order_confirmation_mode`): `FIRST_BATCH_ONLY` (mặc định): đợt đầu chờ nhân viên xác nhận, khi phiên đã được xác minh các đợt sau tự `CONFIRMED`; `ALL` (= EVERY_BATCH): mọi đợt chờ xác nhận; `NONE` (= AUTO_CONFIRM): mọi đợt tự `CONFIRMED`. Đợt `CONFIRMED` đi thẳng vào màn hình bếp. (Tên giá trị trong DB: `FIRST_BATCH_ONLY`/`ALL`/`NONE` - BE-26.)

**Staff / Manager** (`/api/v1/orders`):

| Method | Đường dẫn | Quyền | Mô tả |
| --- | --- | --- | --- |
| GET | `/batches/pending` | STAFF, MANAGER | Đợt chờ xác nhận (cũ trước) kèm bàn, người giữ claim (`claimedBy`, `claimActive`, `claimExpiresAt`) |
| GET | `/sessions/{sessionId}` | STAFF, MANAGER | Order của một phiên |
| POST | `/batches/{id}/claim` | STAFF, MANAGER | Nhận xử lý đợt (TTL `batch_claim_ttl_seconds` = 60 giây); người khác đang giữ → 409 `BATCH_CLAIMED_BY_OTHER` kèm tên; Manager giành quyền được |
| POST | `/batches/{id}/release` | người đang giữ | Nhả claim |
| POST | `/batches/{id}/confirm` | STAFF, MANAGER | Xác nhận cả đợt; tùy chọn `{rejectItems: [{itemId, reasonCode, reasonNote?}]}` → đợt `PARTIALLY_ACCEPTED`. Đợt `REJECTED` nếu từ chối hết |
| POST | `/batches/{id}/reject` | STAFF, MANAGER | Từ chối cả đợt `{reasonCode, reasonNote?}` |
| GET | `/items/ready` | STAFF, MANAGER | Món READY chờ mang ra |
| POST | `/items/{itemId}/serve` | STAFF, MANAGER | READY → SERVED |
| GET | `/cancel-requests` | `ITEM_CANCEL_APPROVE` | Yêu cầu hủy chờ duyệt |
| POST | `/cancel-requests/{id}/approve` | `ITEM_CANCEL_APPROVE` | Món → CANCELLED, `wasted = true`, audit `ITEM_CANCEL_APPROVED` |
| POST | `/cancel-requests/{id}/deny` | `ITEM_CANCEL_APPROVE` | `{reason}` bắt buộc; món giữ nguyên, audit `ITEM_CANCEL_DENIED` |

**Bếp** (KITCHEN, MANAGER, `/api/v1/kitchen`): `GET /items?status=CONFIRMED|PREPARING` (KDS, cũ trước); `POST /items/{itemId}/status {status: PREPARING|READY}`; `POST /items/{itemId}/reject {reasonCode, reasonNote?}` (món CONFIRMED: không thất thoát; PREPARING: `wasted = true`; READY không từ chối được). Món đổi trạng thái → yêu cầu hủy đang chờ của món đó tự bị từ chối.

**Tự động:** đổi món sang `SOLD_OUT` → món PENDING/CONFIRMED của Order còn OPEN tự `REJECTED` (`OUT_OF_STOCK`, người thực hiện `SYSTEM`), Order `LOCKED` giữ nguyên (BR-O13). Force-close phiên → đợt/món chờ `REJECTED` (`SESSION_FORCE_CLOSED`); còn món tính tiền (giá > 0) → Order `WRITTEN_OFF` với thất thu = tổng món tính tiền + phí phục vụ (làm tròn HALF_UP, không cộng VAT), ngược lại `VOIDED`; audit `ORDER_WRITTEN_OFF`/`ORDER_VOIDED`. Khi đã có Bill: xem Giai đoạn 5a (Bill/Payment được xử lý trước, thất thu lấy theo Bill).

**Mã lỗi mới:** `SESSION_NOT_OPEN` 409, `TABLE_PIN_REQUIRED` 403, `PRODUCT_NOT_AVAILABLE` 409, `INVALID_OPTION_SELECTION` 422, `MAX_PENDING_BATCHES_REACHED` 409, `BATCH_NOT_PENDING` 409, `BATCH_CLAIMED_BY_OTHER` 409, `ORDER_ITEM_STATE_INVALID` 409, `CANCEL_REQUEST_NOT_ALLOWED` 409, `CANCEL_REQUEST_NOT_PENDING` 409; dùng lại `ORDER_NOT_OPEN`, `CANCEL_REQUEST_ALREADY_PENDING`, `RATE_LIMIT_EXCEEDED`.

**WebSocket (gửi sau khi transaction commit):** payload chung `{eventId, type: "ORDER_CHANGED", change, sessionId, tableId, tableCode, orderId, batchId, items: [{itemId, productName, quantity, status, reasonCode}], occurredAt}` với `change` ∈ `BATCH_SUBMITTED`, `BATCH_CLAIM_CHANGED`, `BATCH_DECIDED`, `ITEM_STATUS_CHANGED`, `ITEM_CANCELLED`, `CANCEL_REQUESTED`, `CANCEL_DECIDED`, `SYSTEM_REJECTED`, `ORDER_CLOSED`. Không có thông tin nhân viên.
- `/topic/session/{sessionId}` (khách của phiên, STAFF, MANAGER): mọi thay đổi order của phiên.
- `/topic/kitchen/orders` (KITCHEN, MANAGER): món vào/ra danh sách bếp.
- `/topic/staff/orders` (STAFF, MANAGER): đợt mới/đợt đã xử lý/yêu cầu hủy/đóng Order (cùng kênh với `SESSION_NEEDS_ATTENTION` của GĐ3, phân biệt bằng `type`).
- `/topic/staff/ready` (STAFF, MANAGER): món READY/SERVED.
- Mất kết nối hoặc phát hiện `eventId` nhảy: gọi lại REST (`GET /guest/orders`, `/kitchen/items`, `/orders/batches/pending`); sự kiện chỉ là tín hiệu làm mới.

## API Giai đoạn 5a (Billing, Payment, đóng phiên, ServiceCall) - *`COMPLETED` 21/11/2026 (build thật 362/362 PASS)*

Tiền là số nguyên VND, làm tròn HALF_UP; giá menu **đã gồm VAT** nên VAT chỉ trích ngược để hiển thị, không cộng vào tổng (BR-P02). Không đổi schema (không V4). Void có hoàn tiền, rút Void, Bill thay thế của Manager thuộc **GĐ5b**.

**Công thức (khớp các CHECK của bảng `bill`):** `Subtotal` = tổng `line_total` món CONFIRMED/PREPARING/READY/SERVED; `Discount` theo % (tối đa 2 chữ số thập phân, (0, 100]) hoặc số tiền (0 < x ≤ Subtotal), HALF_UP; `Net = Subtotal − Discount`; `ServiceFee = round(Net × service_fee_rate)`; `Total = Net + ServiceFee`; `VAT = round(TaxBase × r / (1 + r))`, `TaxBase = Net` (+ ServiceFee nếu `service_fee_taxable = true`). Tên trường theo schema: yêu cầu GĐ5a gọi giá trị trước giảm giá là "Net", API gọi là `subtotalAmount`.

**Tính tiền / Bill** (Staff có `PAYMENT_PROCESS`, MANAGER mặc định có):

| Method | Đường dẫn | Quyền | Mô tả |
| --- | --- | --- | --- |
| POST | `/api/v1/orders/{orderId}/bill` | `PAYMENT_PROCESS` | Body tùy chọn `{discount?: {type: PERCENT\|FIXED_AMOUNT, value, reason}}` (giảm giá cần thêm `BILL_DISCOUNT`). 201: Bill ACTIVE + `BILL-YYYYMMDD-NNNN`, Order LOCKED, phiên BILLING, bàn PAYMENT_PENDING, REQUEST_PAYMENT đang mở tự RESOLVED. Lỗi: 422 `VAT_NOT_CONFIGURED`, 409 `BATCH_PENDING` (còn đợt chờ, kể cả đang claim), 409 `NO_BILLABLE_ITEMS`, 409 `BILL_ALREADY_ACTIVE`, 409 `ORDER_NOT_OPEN`, 409 `SESSION_NOT_OPEN`, 422 `INVALID_DISCOUNT`. Total = 0 → Bill PAID ngay |
| PUT | `/api/v1/bills/{billId}/discount` | `BILL_DISCOUNT` | `{type: PERCENT\|FIXED_AMOUNT\|NONE, value?, reason, version?}`. **Phát hành lại Bill (BE-29)**: Bill cũ VOIDED (`MANAGER_VOID`), Bill mới `replacesBillId` = Bill cũ, mã mới. Chỉ khi Bill ACTIVE chưa có Payment PENDING/SUCCESS (409 `BILL_HAS_PAYMENTS`); không đổi gì → 422 `INVALID_DISCOUNT` |
| GET | `/api/v1/orders/{orderId}/bill` | STAFF, MANAGER | Bill hiện hành (hoặc gần nhất) |
| GET | `/api/v1/orders/{orderId}/bills` | STAFF, MANAGER | Mọi Bill của Order (kể cả VOIDED/WRITTEN_OFF) |
| GET | `/api/v1/bills/{billId}` | STAFF, MANAGER | Chi tiết: số liệu snapshot, `paidAmount` (thu ròng), `pendingAmount`, `remainingAmount` (= Total − (TPS + TPP − TRS)), món, giao dịch |
| GET | `/api/v1/guest/bill` | Guest | Phiếu tạm tính của phiên (không có thông tin nhân viên); 404 khi chưa tính tiền |

**Thanh toán** (DEC-11 C: `CASH`, `CARD_POS`, `BANK_TRANSFER`, `OTHER`):

| Method | Đường dẫn | Quyền | Mô tả |
| --- | --- | --- | --- |
| POST | `/api/v1/bills/{billId}/payments` | `PAYMENT_PROCESS` | `{requestId (UUID), method, amount, status: SUCCESS\|PENDING, provider?, providerTxnId?, note?}`. 201 mới / **200 khi gửi lại cùng `requestId`** (`replayed: true`); cùng `requestId` khác số tiền/phương thức/Bill → 409 `DUPLICATE_REQUEST_ID`. Vượt số còn lại (PENDING cũng giữ chỗ) → 422 `PAYMENT_EXCEEDS_REMAINING`; Bill không ACTIVE → 409 `PAYMENT_BILL_NOT_ACTIVE`. SUCCESS có `receiptNumber` `YYYYMMDD-NNNN` |
| POST | `/api/v1/payments/{paymentId}/confirm` | `PAYMENT_PROCESS` | `{result: SUCCESS\|FAILED, failureReason?, provider?, providerTxnId?}`; FAILED bắt buộc `failureReason`; xác nhận lại cùng kết quả → `replayed: true`; khác kết quả → 409 `TRANSACTION_IMMUTABLE` |
| GET | `/api/v1/bills/{billId}/payments` | STAFF, MANAGER | Giao dịch của Bill |
| GET | `/api/v1/payments/{paymentId}` | STAFF, MANAGER | Giao dịch + lịch sử trạng thái (do trigger ghi) |
| GET | `/api/v1/payments/{paymentId}/receipt` | STAFF, MANAGER | Dữ liệu in phiếu thanh toán nội bộ (FR-PAY-12): thông tin nhà hàng (`RECEIPT_*`), mã phiếu, Bill, bàn, thu ngân, món, Subtotal/Discount/Net/ServiceFee/Total/VAT, `paidBefore`/`paidAfter`/`remainingAfter`. Chỉ giao dịch SUCCESS (409 `RECEIPT_NOT_AVAILABLE`) |

`providerTxnId` cần `provider` (CHECK DB); thanh toán thủ công không gửi `provider` thì backend dùng tên phương thức (ví dụ `BANK_TRANSFER` + mã tham chiếu ngân hàng). Trùng cặp → 409 `DUPLICATE_PROVIDER_TXN`. Phản hồi ghi nhận/xác nhận: `{payment, bill: {paymentStatus, totalAmount, paidAmount, pendingAmount, remainingAmount, version...}, orderStatus, sessionStatus, sessionClosed, replayed}`.

**Đóng phiên tự động:** Bill PAID (Order PAID) **và** không còn món CONFIRMED/PREPARING/READY → phiên `CLOSED`, bàn `CLEANING` (hoặc `AVAILABLE` nếu `cleaning_auto_release`), ServiceCall còn mở tự RESOLVED, audit `SESSION_CLOSED`, Guest JWT bị từ chối ngay (401 `SESSION_CLOSED`). Bill đã PAID mà món còn nấu/chờ mang ra: phiên giữ `BILLING`; món cuối cùng chuyển `SERVED` thì phiên tự đóng (người phục vụ món đó là `closed_by`). Order PAID vẫn cho bếp/phục vụ đi tiếp; không hủy/từ chối món (BR-O12).

**Mở lại phiên (P1, BR-P08 a):** `POST /api/v1/sessions/{sessionId}/reopen {reason, version?}` (`PAYMENT_PROCESS`): phiên phải BILLING (409 `SESSION_NOT_BILLING`), Bill ACTIVE chưa có Payment SUCCESS (409 `BILL_HAS_PAYMENTS` - cần Manager Void, GĐ5b) và không có PENDING (409 `PENDING_PAYMENT_EXISTS`); Payment FAILED không chặn. Kết quả: Bill VOIDED (`SESSION_REOPEN`), Order OPEN, phiên OPEN, bàn OCCUPIED, audit `SESSION_REOPENED`; khách gọi thêm, Staff tính tiền lại tạo Bill mới.

**Force-close có Bill (BE-22, BR-P15):** từ chối khi Bill hiện hành `VOID_PENDING` (409 `BILL_VOID_PENDING`) hoặc còn Payment PENDING (409 `PENDING_PAYMENT_EXISTS`). Bill ACTIVE chưa thu đủ → Bill và Order `WRITTEN_OFF`, `shortfall_amount = Total − (TPS − TRS)`, giữ Payment SUCCESS, không Refund, audit `ORDER_WRITTEN_OFF` + `BILL_WRITTEN_OFF`. Order đã PAID (phiên BILLING do món chưa xong) giữ nguyên. Chưa có Bill: như GĐ4.

**ServiceCall (FR-NOT-03, §9.8):**

| Method | Đường dẫn | Quyền | Mô tả |
| --- | --- | --- | --- |
| POST | `/api/v1/guest/service-calls` | Guest | `{type: CALL_STAFF\|REQUEST_PAYMENT, note?}`; mỗi loại tối đa 1 yêu cầu đang mở - gửi lại trả yêu cầu đang mở (200, `replayed: true`). REQUEST_PAYMENT chỉ khi phiên OPEN (BILLING → 409 `SERVICE_CALL_STATE_INVALID`). Khách không khóa Order (DEC-08 B) |
| GET | `/api/v1/guest/service-calls` | Guest | Yêu cầu của phiên (không có thông tin nhân viên) |
| POST | `/api/v1/service-calls` | STAFF, MANAGER | `{sessionId, note?}` → MANAGER_SUPPORT |
| GET | `/api/v1/service-calls?status=OPEN&status=ACKNOWLEDGED` | STAFF, MANAGER | Hàng đợi (mặc định OPEN + ACKNOWLEDGED, cũ trước) |
| POST | `/api/v1/service-calls/{id}/acknowledge` | STAFF, MANAGER | OPEN → ACKNOWLEDGED (gọi lại: idempotent). MANAGER_SUPPORT chỉ MANAGER |
| POST | `/api/v1/service-calls/{id}/resolve` | STAFF, MANAGER | → RESOLVED `{note?}`; đã RESOLVED → 409 `SERVICE_CALL_STATE_INVALID`. MANAGER_SUPPORT chỉ MANAGER |

Tự RESOLVED (`resolved_by` NULL, `autoResolved: true`): REQUEST_PAYMENT khi Tính tiền; mọi yêu cầu còn mở khi phiên CLOSED/FORCE_CLOSED/EXPIRED.

**WebSocket (sau commit):**
- `BILL_CHANGED` `{eventId, type, change, sessionId, tableId, tableCode, orderId, billId, billNumber, billStatus, paymentStatus, totalAmount, paidAmount, remainingAmount, occurredAt}`, `change` ∈ `BILL_CREATED`, `BILL_REISSUED`, `PAYMENT_PENDING`, `PAYMENT_SUCCEEDED`, `PAYMENT_FAILED`, `BILL_PAID`, `BILL_VOIDED`, `BILL_WRITTEN_OFF` → `/topic/session/{sessionId}` và `/topic/staff/orders`.
- `SERVICE_CALL_CHANGED` `{eventId, type, change: CREATED|ACKNOWLEDGED|RESOLVED, callId, callType, status, autoResolved, sessionId, tableId, tableCode, occurredAt}` → `/topic/staff/orders`; CALL_STAFF/REQUEST_PAYMENT cũng gửi `/topic/session/{sessionId}` (MANAGER_SUPPORT là trao đổi nội bộ, không gửi kênh khách).
- `SESSION_CHANGED` thêm `change` = `BILLING_STARTED`, `REOPENED`; `CLOSED` khi đóng phiên sau thanh toán.

**Mã lỗi mới:** `NO_BILLABLE_ITEMS` 409, `BILL_NOT_ACTIVE` 409, `BILL_HAS_PAYMENTS` 409, `BILL_VOID_PENDING` 409, `INVALID_DISCOUNT` 422, `RECEIPT_NOT_AVAILABLE` 409, `SESSION_NOT_BILLING` 409, `SERVICE_CALL_STATE_INVALID` 409, `DUPLICATE_PROVIDER_TXN` 409 (unique `ux_payment_transaction_provider_txn`).

**Audit mới:** `BILL_CREATED`, `BILL_DISCOUNT_APPLIED` (lý do bắt buộc), `BILL_VOIDED`, `BILL_PAID`, `BILL_WRITTEN_OFF`, `PAYMENT_RECORDED`, `PAYMENT_CONFIRMED`, `PAYMENT_FAILED`, `SESSION_REOPENED`, `SESSION_CLOSED`.

**Cấu hình mới:** `RECEIPT_RESTAURANT_NAME`, `RECEIPT_ADDRESS`, `RECEIPT_PHONE`, `RECEIPT_TAX_CODE`, `RECEIPT_FOOTER` (`app.receipt.*`).

## API Giai đoạn 5b (Void có hoàn tiền, Refund, rút Void, Bill thay thế) - *`COMPLETED` 21/11/2026 (build thật 377/377 PASS)*

`remaining_refundable = TPS − TRS − TRP` (BR-P13; TPS/TRS/TRP = tổng Payment SUCCESS / Refund SUCCESS / Refund PENDING). `BillResponse` thêm `refundedAmount`, `refundPendingAmount`, `remainingRefundable`, `voidRequestedAt`, `orderNeedsAttention`, `refunds`.

| Method | Đường dẫn | Quyền | Mô tả |
| --- | --- | --- | --- |
| POST | `/api/v1/bills/{billId}/void` | `BILL_VOID` | `{reason, version?}`. Bill phải ACTIVE, không có Payment PENDING. Thu ròng 0 → VOIDED ngay (`voidCompleted: true`), Order LOCKED chờ Bill thay thế; > 0 → VOID_PENDING, Order PAID → LOCKED (lưu `status_before_void`). Cho phép cả khi phiên đã CLOSED; phiên/bàn không đổi |
| POST | `/api/v1/bills/{billId}/void/withdraw` | `BILL_VOID` | `{reason}` (P2). Chỉ khi VOID_PENDING và chưa có Refund PENDING/SUCCESS (FAILED không chặn); Bill → ACTIVE, Order về trạng thái trước Void |
| POST | `/api/v1/bills/{billId}/refunds` | `REFUND_RECORD` | `{requestId (UUID), method, amount, status: SUCCESS\|PENDING, reason}`. 201; gửi lại `requestId` → 200 `replayed: true`. Chỉ khi VOID_PENDING; `amount` ≤ `remainingRefundable`. Hoàn đủ → Bill VOIDED, Order chờ Bill thay thế |
| POST | `/api/v1/refunds/{refundId}/confirm` | `REFUND_RECORD` | `{result: SUCCESS\|FAILED, failureReason?}`; PENDING → SUCCESS/FAILED, cùng kết quả → `replayed`, khác → 409 `TRANSACTION_IMMUTABLE` |
| GET | `/api/v1/bills/{billId}/refunds` | STAFF, MANAGER | Refund của Bill + tóm tắt |
| GET | `/api/v1/refunds/{refundId}` | STAFF, MANAGER | Refund + lịch sử trạng thái |
| GET | `/api/v1/refunds/{refundId}/receipt` | STAFF, MANAGER | Dữ liệu phiếu hoàn tiền `RF-YYYYMMDD-NNNN` (chỉ SUCCESS) |
| POST | `/api/v1/orders/{orderId}/replacement-bill` | `BILL_VOID` (+ `BILL_DISCOUNT` nếu giảm giá) | Body như tạo Bill. Order LOCKED + Bill gần nhất VOIDED; Bill mới `replacesBillId`, 201 |
| GET | `/api/v1/billing/attention` | STAFF, MANAGER | Cần xử lý (FR-PAY-13): Bill VOID_PENDING + Order `needs_attention` |
| POST | `/api/v1/sessions/{sessionId}/reopen` | `PAYMENT_PROCESS` hoặc `SESSION_REOPEN` | Thêm nhánh BR-P08 b: Manager mở lại phiên có Order chờ Bill thay thế (cần `SESSION_REOPEN`) → Order OPEN, phiên OPEN, bàn OCCUPIED |

**Order chờ Bill thay thế** (`needs_attention`, AWAITING_REPLACEMENT_BILL) có 3 lối ra: Bill thay thế, Manager mở lại phiên, force-close (WRITTEN_OFF, ghi thất thu).

**WebSocket:** `BILL_CHANGED` thêm `change` = `VOID_REQUESTED`, `VOID_WITHDRAWN`, `REFUND_PENDING`, `REFUND_SUCCEEDED`, `REFUND_FAILED`, `REPLACEMENT_CREATED` (`VOIDED` khi Void hoàn tất).

**Mã lỗi mới:** `BILL_NOT_VOID_PENDING` 409, `VOID_HAS_REFUNDS` 409, `REPLACEMENT_BILL_NOT_ALLOWED` 409. Dùng lại: `REFUND_REQUIRES_VOID_PENDING`, `REFUND_EXCEEDS_REFUNDABLE`, `PENDING_PAYMENT_EXISTS`, `TRANSACTION_IMMUTABLE`.

**Audit mới:** `BILL_VOID_REQUESTED`, `BILL_VOID_WITHDRAWN`, `REFUND_CREATED`, `REFUND_CONFIRMED`, `REFUND_FAILED`, `BILL_REPLACEMENT_CREATED` (lý do bắt buộc); entity `REFUND_TRANSACTION`.

## API Giai đoạn 6 (Report & Analytics + FR-ORD-05) - *`COMPLETED` 22/11/2026 (build thật 476/476 PASS)*

Không đổi schema (V1-V3, không V4). Module `report` chỉ đọc (`NamedParameterJdbcTemplate`, `@Transactional(readOnly = true)`). Múi giờ kinh doanh `Asia/Ho_Chi_Minh`.

### Báo cáo (FR-RPT-01..03) - quyền `REPORT_VIEW` (MANAGER mặc định; ADMIN/STAFF/KITCHEN chỉ khi được cấp)

Tham số khoảng thời gian `from`, `to` (tùy chọn): dạng ngày `yyyy-MM-dd` (`to` tính **bao gồm** ngày đó) hoặc ngày giờ ISO-8601 có offset (`to` **loại trừ**). Bỏ trống: mặc định theo `ReportRange`. Tối đa `app.report.max-range-days` (mặc định 366, biến `REPORT_MAX_RANGE_DAYS`).

| Method | Đường dẫn | Tham số | Mô tả |
| --- | --- | --- | --- |
| GET | `/api/v1/reports/revenue` | `from, to, groupBy=DAY\|WEEK\|MONTH\|SHIFT, shift?` | Mỗi kỳ: Payment SUCCESS (theo `confirmed_at`), Refund SUCCESS (trừ theo `confirmed_at` của Refund), doanh thu ròng, số Bill, 4 phương thức (zero-fill), thất thu (WRITTEN_OFF) hiển thị riêng, không cộng/trừ vào doanh thu |
| GET | `/api/v1/reports/summary` | `from, to` | Thất thu, Refund, tỷ lệ hủy/từ chối món theo `reason_code`, wasted, Order chờ Bill thay thế và Bill VOID_PENDING (ảnh chụp hiện tại), số phiên, số đợt |
| GET | `/api/v1/reports/top-products` | `from, to, limit (1..100, mặc định 10), categoryId?, sortBy` | Món tính tiền (CONFIRMED/PREPARING/READY/SERVED) của Order `PAID` theo `orders.paid_at`; doanh số = `line_total` |
| GET | `/api/v1/reports/service-time` | `from, to` | Trung bình/trung vị các đoạn xác nhận, chờ bếp, nấu, mang món, tổng (chỉ món SERVED) |
| GET | `/api/v1/reports/revenue.csv` | như `revenue` | CSV UTF-8 có BOM, CRLF, chống formula injection; `Content-Disposition: attachment`; audit `REPORT_EXPORTED` |
| GET | `/api/v1/reports/top-products.csv` | như `top-products` | như trên |

**Ca làm việc (BE-34):** khai báo trong `application.yml` `app.report.shifts` (mã, giờ bắt đầu, giờ kết thúc; hỗ trợ ca qua nửa đêm, gắn vào ngày bắt đầu ca; kiểm tra chồng lấn khi khởi động). Mặc định: MORNING 06:00-14:00, EVENING 14:00-23:00, NIGHT 23:00-06:00. `groupBy=SHIFT` cần cấu hình hợp lệ, nếu không → 422 `REPORT_SHIFT_NOT_CONFIGURED`.

**Mã lỗi mới:** `INVALID_REPORT_RANGE` 400 (from sau to, vượt max-range-days, định dạng sai), `REPORT_SHIFT_NOT_CONFIGURED` 422. Tham số sai kiểu → 400 `MALFORMED_REQUEST`.

**Nhãn kỳ:** `2026-11-21` (DAY), `2026-W47` (WEEK, tuần ISO), `2026-11` (MONTH), `2026-11-21 NIGHT` (SHIFT). DTO mang `date`/`fromDate`/`toDate` theo giờ kinh doanh vì Jackson trả OffsetDateTime theo UTC.

### Nhân viên đặt/sửa/hủy món hộ khách (FR-ORD-05, BE-37) - STAFF, MANAGER

| Method | Đường dẫn | Mô tả |
| --- | --- | --- |
| POST | `/api/v1/orders/sessions/{sessionId}/batches` | `{clientRequestId, items[]}` như đặt món của khách. Đợt `submitted_by_type = STAFF`, tự `CONFIRMED`, không rate limit, không PIN, không đặt `verified_at`. 201; gửi lại cùng `clientRequestId` → 200 (replay); trùng với đợt không phải STAFF → 409 `DUPLICATE_CLIENT_REQUEST`. Phiên phải OPEN, Order OPEN |
| PATCH | `/api/v1/orders/items/{itemId}/quantity` | `{quantity, version}`; món PENDING/CONFIRMED, Order OPEN; 1..`max_item_quantity`; sai version → 409 `CONCURRENT_MODIFICATION`; món khác trạng thái → 409 `ORDER_ITEM_NOT_EDITABLE` |
| POST | `/api/v1/orders/items/{itemId}/cancel` | `{reason}`; chỉ PENDING/CONFIRMED (reason code `STAFF_CANCELLED`); PREPARING/READY vẫn qua yêu cầu hủy + Manager duyệt (BR-O06) → 409 `ORDER_ITEM_STATE_INVALID` |

**Audit mới:** `ORDER_ITEM_ADDED_BY_STAFF` (entity `order_batch`), `ORDER_ITEM_QUANTITY_CHANGED`, `ORDER_ITEM_CANCELLED_BY_STAFF`, `REPORT_EXPORTED` (entity `report`). **WebSocket:** `ORDER_CHANGED` thêm `change = ITEM_QUANTITY_CHANGED`. CORS expose thêm `Content-Disposition`.

**Hạn chế đã biết:** chưa có index trên `orders.paid_at` và `order_item.served_at` (top-products, service-time quét theo khoảng; không V4); nhân viên chưa tạo yêu cầu hủy hộ khách cho món PREPARING/READY (BE-39).
