#!/usr/bin/env python3
"""Ghi tiến độ Staff App Đợt 1 vào PROJECT_PROGRESS.md của repo (không xóa lịch sử).

Cách dùng (từ thư mục gốc repo):
    python staff_app/docs/apply_staff_app_progress_dot1.py [đường_dẫn/PROJECT_PROGRESS.md]

Idempotent: chạy lại sẽ thay đúng khối đã chèn trước đó, không nhân đôi.
Trạng thái được ghi TRUNG THỰC: IN PROGRESS cho tới khi bạn chạy `flutter analyze`,
`flutter test` và dán kết quả xác nhận; sau đó đổi nhãn thành COMPLETED bằng tay.
"""
import pathlib
import sys

START = "<!-- STAFF_APP_DOT1:START -->"
END = "<!-- STAFF_APP_DOT1:END -->"

BLOCK = f"""{START}
## Staff App (Flutter) — Đợt 1: nền tảng, xác thực, sơ đồ bàn

**Trạng thái: `IN PROGRESS`** — mã nguồn và test đã viết; `flutter analyze` / `flutter test` / chạy thử trên thiết bị CHƯA được xác nhận (xem mục D).

### A. Tổng quan
- Thư mục: `staff_app/` (Flutter ≥ 3.24, Dart ≥ 3.5, Riverpod + GoRouter + Dio + stomp_dart_client + flutter_secure_storage).
- Đợt 1: scaffold, lớp mạng + hàng đợi refresh BE-06, STOMP, đăng nhập/khôi phục phiên/đổi mật khẩu/đăng xuất, hiển thị quyền, sơ đồ bàn realtime, chi tiết bàn, xác nhận dọn xong.
- Đợt 2 (`NOT STARTED`): nhận/xác nhận/từ chối đơn, duyệt hủy món, KDS bếp, thu ngân/thanh toán. Các tab tương ứng đang là màn hình giữ chỗ.

### B. Đã triển khai (`IN PROGRESS` cho tới khi có kết quả analyze/test)
- Mạng: `ApiClient` (2 Dio: có/không interceptor), `AuthInterceptor`, `TokenRefreshCoordinator` + `SingleFlight` (N request 401 đồng thời → đúng 1 `POST /api/v1/auth/refresh`; request đến muộn dùng lại token mới; refresh 401 → đăng xuất; lỗi mạng/5xx → giữ phiên; `ACCOUNT_DISABLED` → đăng xuất ngay).
- Phân tích phong bì `ApiResponse`/`ApiError` → `ApiException` (code, details theo trường, correlationId, Retry-After).
- Lưu trữ: token trong `flutter_secure_storage`; địa chỉ máy chủ trong `shared_preferences`; `API_BASE_URL` mặc định `http://10.0.2.2:8080` qua `--dart-define`, đổi được ở màn đăng nhập (kèm "Kiểm tra kết nối").
- STOMP: kết nối `/ws/websocket`, header `Authorization: Bearer`, làm mới token trước khi nối, tự nối lại có backoff, chỉ subscribe kênh theo vai trò; sự kiện chỉ là tín hiệu làm mới, nối lại thì tải lại bằng REST; STOMP mất kết nối → thăm dò 30 giây.
- UI: đăng nhập, khôi phục phiên, sơ đồ bàn (nhóm khu vực, lọc, tìm không dấu, realtime), chi tiết bàn (PIN, món đã gọi), xác nhận dọn xong (xử lý 409 `CONCURRENT_MODIFICATION`), tài khoản + quyền, đổi mật khẩu; điều hướng theo vai trò; phẳng, tối giản, đủ Loading/Empty/Error/Success.
- API đã tích hợp: `POST /auth/login|refresh|logout`, `GET /auth/me`, `POST /auth/change-password`, `GET /tables/floor-plan`, `PATCH /tables/{{id}}/status`, `GET /sessions/{{id}}`, `GET /orders/sessions/{{id}}`, STOMP `/topic/tables`.
- Database: không thay đổi.

### D. Vấn đề / chưa xác nhận
- Môi trường AI không có Flutter SDK: `flutter analyze`, `flutter test`, build và chạy thiết bị CHƯA chạy. Chỉ đã kiểm tra: `dart format` toàn bộ, `dart analyze` phần Dart thuần, và 43 test Dart thuần (qua bộ chạy thay thế) đều pass. Test Flutter (interceptor Dio, session controller, widget) viết nhưng chưa chạy.
- Chưa thử với backend thật (đăng nhập, STOMP, 401 refresh).

### E. Quyết định kỹ thuật / khác biệt so với mô tả ban đầu (theo mã nguồn backend)
- Xác nhận dọn bàn: `PATCH /api/v1/tables/{{id}}/status` `{{status:"AVAILABLE", version}}` (không có endpoint `cleaning-complete`).
- Không có permission `PIN_VIEW`; `tablePin` nằm trong `GET /sessions/{{id}}` cho STAFF/MANAGER.
- Từ chối yêu cầu hủy món là `/deny` (dùng ở Đợt 2).
- ADMIN không có quyền vận hành → bị từ chối ở Staff App (dùng Admin Dashboard). KITCHEN không truy cập `/tables`.
- Android: cần `INTERNET` và cleartext khi dev (`tools/patch_android.py`).

### F. Việc tiếp theo
1. Chạy `flutter pub get`, `dart fix --apply`, `flutter analyze`, `flutter test`; sửa lỗi nếu có; thử trên emulator với backend thật.
2. Khi đã pass và thử thật: đổi trạng thái Đợt 1 thành `COMPLETED`.
3. Đợt 2: nhận/xác nhận/từ chối đơn, duyệt hủy món, KDS, thu ngân.
{END}
"""


def main() -> int:
    path = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "PROJECT_PROGRESS.md")
    if not path.exists():
        print(f"Không thấy {path}.")
        return 1
    text = path.read_text(encoding="utf-8")
    if START in text and END in text:
        head, rest = text.split(START, 1)
        _, tail = rest.split(END, 1)
        text = head.rstrip() + "\n\n" + BLOCK + tail.lstrip("\n")
    else:
        text = text.rstrip() + "\n\n" + BLOCK
    path.write_text(text, encoding="utf-8")
    print(f"Đã cập nhật {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
