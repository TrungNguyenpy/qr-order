# QR Order — Staff App (Flutter)

Ứng dụng nhân viên (Android điện thoại/máy tính bảng) của hệ thống QR Order: phục vụ, thu ngân, bếp.

**Trạng thái: Đợt 1 — `IN PROGRESS`.** Đợt 1 gồm nền tảng, xác thực và sơ đồ bàn. Nhận/xác nhận/từ chối đơn, KDS bếp và thu ngân thuộc Đợt 2 (các tab tương ứng đang là màn hình giữ chỗ).

## Đợt 1 có gì

- Đăng nhập, khôi phục phiên khi mở app, đổi mật khẩu, đăng xuất, hiển thị vai trò và 8 permission.
- Token lưu trong `flutter_secure_storage`; tự làm mới khi 401 theo **hàng đợi tuần tự (BE-06)**: nhiều request 401 cùng lúc chỉ tạo đúng 1 request `/auth/refresh`; refresh bị từ chối → về màn đăng nhập; mất mạng → giữ phiên.
- STOMP realtime (`/ws/websocket`, `Authorization: Bearer`), tự nối lại, chỉ subscribe kênh theo vai trò. Sự kiện chỉ là tín hiệu làm mới, dữ liệu chuẩn luôn lấy lại bằng REST.
- Sơ đồ bàn: nhóm theo khu vực, lọc theo trạng thái, tìm không dấu, cập nhật tức thì; chi tiết bàn (PIN, món đã gọi); **xác nhận dọn xong** (CLEANING → AVAILABLE, có kiểm tra version).
- Giao diện phẳng, gọn, đủ Loading / Empty / Error / Success; điện thoại dọc dùng thanh điều hướng dưới, màn hình rộng/ngang dùng thanh bên.

## Khác biệt so với mô tả ban đầu (theo mã nguồn backend)

| Mô tả ban đầu | Thực tế backend → app làm |
| :-- | :-- |
| endpoint `cleaning-complete` | `PATCH /api/v1/tables/{id}/status` `{status:"AVAILABLE", version}` |
| permission `PIN_VIEW` | không tồn tại; `tablePin` nằm trong `GET /api/v1/sessions/{id}` cho STAFF/MANAGER |
| từ chối yêu cầu hủy món | `/deny` (dùng ở Đợt 2) |
| ADMIN dùng Staff App | ADMIN không có quyền vận hành → bị từ chối, dùng Admin Dashboard |
| KITCHEN xem sơ đồ bàn | `/tables` không cho KITCHEN → KITCHEN chỉ có tab Bếp (Đợt 2) và Tài khoản |

## Chạy lần đầu (Windows PowerShell)

Yêu cầu: Flutter ≥ 3.24 (Dart ≥ 3.5), Android Studio/emulator hoặc điện thoại bật USB debugging, Python 3 (cho bước vá manifest).

```powershell
cd staff_app

# 1) Sinh thư mục nền tảng (chỉ lần đầu; không ghi đè lib/ hay pubspec.yaml)
flutter create --platforms=android,ios --project-name staff_app .

# 2) Vá AndroidManifest: quyền INTERNET + cho phép http:// khi dev
python tools/patch_android.py

# 3) Cài gói, tự sửa các gợi ý lint, kiểm tra, chạy test
flutter pub get
dart fix --apply
flutter analyze
flutter test

# 4) Chạy (emulator: backend trên máy tính là 10.0.2.2)
flutter run --dart-define=API_BASE_URL=http://10.0.2.2:8080

# Thiết bị thật: dùng IP LAN của máy chạy backend, ví dụ
flutter run --dart-define=API_BASE_URL=http://192.168.1.20:8080
```

Địa chỉ máy chủ cũng đổi được ngay ở màn đăng nhập (nút **Đổi máy chủ**, có **Kiểm tra kết nối**); giá trị được lưu trên thiết bị. Backend cần chạy với CORS/tường lửa cho phép cổng 8080 từ thiết bị.

> `flutter analyze` mặc định coi cả mức *info* là lỗi; `dart fix --apply` sẽ tự sửa phần lớn (ví dụ thêm `const`). Nếu còn báo lỗi, dán log để xử lý.

## Cấu trúc

```
lib/
  main.dart, app/            # khởi động, theme, router (redirect theo đăng nhập + vai trò)
  core/
    config/                  # AppConfig (API_BASE_URL), chuẩn hóa URL máy chủ
    network/                 # ApiClient, AuthInterceptor, TokenRefreshCoordinator, SingleFlight, ApiException
    realtime/                # StompRealtimeService, kênh theo vai trò
    storage/                 # SecureTokenStore, kho URL máy chủ
    providers/               # Riverpod providers dùng chung
  features/
    auth/                    # domain, repository, SessionController, màn hình đăng nhập/tài khoản/đổi mật khẩu
    tables/                  # sơ đồ bàn, chi tiết bàn, xác nhận dọn xong
    orders/                  # model + đọc order của phiên (Đợt 1: chỉ xem)
    shell/                   # điều hướng theo vai trò, màn hình giữ chỗ Đợt 2
  shared/widgets/            # AppCard, StatusPill, Skeleton/Empty/Error
test/                        # unit + widget test (xem bên dưới)
tools/patch_android.py       # vá AndroidManifest sau flutter create
docs/apply_staff_app_progress_dot1.py   # ghi tiến độ vào PROJECT_PROGRESS.md của repo
```

## Đợt 2 có gì

- **Đơn hàng** (STAFF/MANAGER): tab *Chờ xác nhận* (mở đợt → tự nhận `claim`, giữ 60 giây; xác nhận cả đợt, từ chối từng món hoặc cả đợt kèm lý do; đóng sheet thì tự `release`), tab *Sẵn sàng* (bấm **Đã mang ra** → `serve`), tab *Yêu cầu hủy* (chỉ người có `ITEM_CANCEL_APPROVE`: duyệt / từ chối kèm lý do bắt buộc).
- **Bếp (KDS)** (KITCHEN/MANAGER): cột *Chờ nấu* và *Đang nấu* (màn rộng) hoặc 2 tab (màn hẹp); thời gian chờ đổi màu ≥10 phút vàng, ≥20 phút đỏ; *Bắt đầu nấu*, *Xong, sẵn sàng*, *Từ chối* (có lý do).
- **Thu ngân** (quyền `PAYMENT_PROCESS`): danh sách bàn có phiên; *Tính tiền* (giảm giá tùy chọn nếu có `BILL_DISCOUNT`), *Thu tiền* (tiền mặt/chuyển khoản/thẻ POS/khác, thu một phần được, ghi nhận chờ xác nhận), xác nhận/không thành công giao dịch chờ, xem phiếu thanh toán. `requestId` sinh một lần khi mở hộp thoại nên bấm lại sau lỗi mạng không thu trùng.
- Mọi danh sách tự làm mới theo STOMP, khi nối lại, và thăm dò 30 giây khi mất kết nối.
- Chi tiết bàn luôn có dòng **Mã PIN bàn** ("Chưa có PIN" nếu chưa có).

## Đợt 3 có gì

- **Tab Hỗ trợ** (trong Đơn hàng): hàng đợi yêu cầu *Khách gọi nhân viên*, *Khách xin thanh toán*, *Nhân viên cần Manager*; "Nhận xử lý" → "Hoàn tất" (ghi chú tùy chọn). Yêu cầu dành cho Manager chỉ Manager thao tác. STAFF có nút **Gọi Manager** trong chi tiết bàn. Realtime qua `/topic/staff/orders`.
- **Gọi món hộ** (chi tiết bàn, khi order chưa có hoặc đang mở): chọn danh mục/tìm món, chọn option (kiểm tra nhóm bắt buộc/tối đa), ghi chú, số lượng, gửi một đợt vào hàng chờ xác nhận. `clientRequestId` giữ nguyên khi gửi lại cùng giỏ sau lỗi mạng, đổi khi giỏ thay đổi.
- **Sửa số lượng / Hủy món** (menu ⋮ trên món PENDING/CONFIRMED): đổi số lượng gửi kèm `version` (xung đột → tải lại), hủy bắt buộc lý do. Món đã nấu đi qua yêu cầu hủy.
- **Thao tác hóa đơn của Manager** (trong sheet Thu ngân, theo quyền): Void (`BILL_VOID`), Rút Void, Ghi nhận hoàn tiền (`REFUND_RECORD`, `requestId` idempotent, thu một phần được), Mở lại phiên (`PAYMENT_PROCESS`/`SESSION_REOPEN`, chỉ khi chưa có tiền), Tạo hóa đơn thay thế. Hóa đơn bị Void sau khi mở lại phiên → tính tiền lại bình thường.
- **Cần xử lý** (nút trên màn Thu ngân): hóa đơn chờ hoàn tiền, order chờ hóa đơn thay thế; chạm để mở sheet của bàn.
- **Chưa làm (cố ý)**: xem lịch sử/phiếu hoàn tiền (backend đã có `GET /refunds/{id}/receipt`), đóng cưỡng bức phiên (`SESSION_FORCE_CLOSE`), ghi nhận thất thu, báo cáo.

## Kiểm thử

| Nhóm | File | Ghi chú |
| :-- | :-- | :-- |
| Dart thuần | `test/core/*`, `test/features/auth_models_test.dart`, `test/features/tables_domain_test.dart` | đã chạy được ngoài Flutter: 75/75 pass gồm `dot2_domain_test` và `dot3_domain_test` |
| Interceptor Dio | `test/core/auth_interceptor_test.dart` | 10 request 401 → 1 refresh; refresh bị từ chối; mất mạng; ACCOUNT_DISABLED |
| Điều phối phiên | `test/features/session_controller_test.dart` | khôi phục, đăng nhập, hết hạn, đổi mật khẩu |
| Widget | `test/features/login_screen_test.dart`, `test/features/floor_plan_screen_test.dart` | 4 trạng thái, lọc, xác nhận dọn xong, 409 |
| Đợt 2 (Dart thuần) | `test/features/dot2_domain_test.dart` | giảm giá, UUID v4, mã lý do, đọc `KitchenItem`/`PaymentResult` |
| Đợt 3 (Dart thuần) | `test/features/dot3_domain_test.dart` | yêu cầu hỗ trợ, giỏ gọi món hộ, validate option, hoàn tiền, quyền thao tác hóa đơn |
| Widget Đợt 2/3 | `test/features/dot23_screens_test.dart` | màn Đơn hàng, tab Hỗ trợ, bếp, danh sách thu ngân, gọi món hộ (idempotent), sửa/hủy món (CHƯA chạy) |
| Điều hướng | `test/app/router_redirect_test.dart` | chuyển hướng theo trạng thái + vai trò |

## Kịch bản thử tay với backend thật

1. Đăng nhập STAFF → thấy tab Sơ đồ bàn, Đơn hàng, Tài khoản. Đăng nhập KITCHEN → chỉ Bếp, Tài khoản. Đăng nhập ADMIN → bị từ chối.
2. Khách quét QR ở Customer Web → ô bàn chuyển "Đang phục vụ" trong ~1 giây không cần kéo làm mới; mở chi tiết thấy PIN.
3. Thanh toán xong ở Admin/Thu ngân → bàn "Đang dọn" → bấm **Xác nhận dọn xong** → "Trống". Thử bấm đồng thời trên hai máy → máy chậm nhận thông báo xung đột và tự tải lại.
4. Tắt backend 30 giây → thanh vàng "Mất kết nối thời gian thực" → bật lại → tự nối lại và tải lại sơ đồ.
5. Chờ access token (15 phút) hết hạn khi đang dùng → app tự làm mới, không bị đăng xuất.

### Thử tay Đợt 2

6. Khách gửi đợt gọi món → tab *Chờ xác nhận* hiện đợt mới; mở trên 2 máy cùng lúc → máy sau báo "đang được <tên> xử lý". MANAGER có thể tiếp quản.
7. Xác nhận đợt → bếp thấy món ở *Chờ nấu* ngay; bấm *Bắt đầu nấu* → *Xong* → tab *Sẵn sàng* của phục vụ có món → *Đã mang ra*.
8. Từ chối một món trong đợt (lý do *Hết món*) rồi xác nhận → đợt thành "chấp nhận một phần".
9. Khách xin hủy món đã nấu → tab *Yêu cầu hủy* → duyệt / từ chối (phải nhập lý do).
10. Thu ngân: *Tính tiền* (thử giảm 10%) → *Thu tiền* một phần tiền mặt → *Thu tiền* phần còn lại → bàn chuyển "Đang dọn", xem phiếu. Thử bật tắt Wi-Fi giữa lúc ghi nhận rồi bấm lại: chỉ có một giao dịch.

### Thử tay Đợt 3

11. Khách bấm *Gọi nhân viên* / *Xin thanh toán* trên Customer Web → tab Hỗ trợ hiện ngay; *Nhận xử lý* → *Hoàn tất*. STAFF bấm *Gọi Manager* → yêu cầu hiện ở máy Manager, STAFF không có nút thao tác.
12. Chi tiết bàn → *Gọi món hộ*: chọn món có option bắt buộc (phải chọn đủ mới thêm được) → *Gửi đợt* → đợt xuất hiện ở *Chờ xác nhận*. Tắt mạng giữa lúc gửi rồi gửi lại: chỉ có một đợt.
13. Món vừa gọi (Chờ xác nhận / Đã xác nhận): ⋮ → *Sửa số lượng*; mở món đó trên 2 máy rồi đổi trên máy kia trước → máy này báo xung đột và tải lại. ⋮ → *Hủy món* phải nhập lý do.
14. Tính tiền, thu một phần tiền mặt, rồi *Void hóa đơn* (Manager) → hóa đơn VOID_PENDING → *Ghi nhận hoàn tiền* đủ số → hóa đơn chuyển *Đã Void*, order chờ hóa đơn thay thế → *Tạo hóa đơn thay thế*.
15. Tính tiền nhưng chưa thu: *Mở lại phiên* → bàn về Đang phục vụ, gọi thêm món, tính tiền lại.
16. Màn Thu ngân → *Cần xử lý* liệt kê các hóa đơn ở bước 14.
