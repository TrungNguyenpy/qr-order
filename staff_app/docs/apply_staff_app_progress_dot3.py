#!/usr/bin/env python3
"""Ghi tiến độ Staff App Đợt 3 vào PROJECT_PROGRESS.md của repo (không xóa lịch sử).

Cách dùng (từ thư mục gốc repo):
    python staff_app/docs/apply_staff_app_progress_dot3.py [đường_dẫn/PROJECT_PROGRESS.md]

Idempotent: chạy lại sẽ thay đúng khối đã chèn trước đó, không nhân đôi.
Trạng thái được ghi TRUNG THỰC: IN PROGRESS cho tới khi bạn chạy `flutter analyze`,
`flutter test` và dán kết quả xác nhận; sau đó đổi nhãn thành COMPLETED bằng tay.
"""
import pathlib
import sys

START = "<!-- STAFF_APP_DOT3:START -->"
END = "<!-- STAFF_APP_DOT3:END -->"

BLOCK = f"""{START}
## Staff App (Flutter) — Đợt 3: hỗ trợ, gọi món hộ, sửa món, hóa đơn Manager

**Trạng thái: `IN PROGRESS`** — mã nguồn và test đã viết; `flutter analyze` / `flutter test` / chạy thiết bị với backend thật CHƯA được xác nhận (xem mục D). Đổi thành `COMPLETED` bằng tay sau khi đã pass.

### B. Đã triển khai
- Yêu cầu hỗ trợ: `GET /service-calls`, `POST /service-calls/{{id}}/acknowledge|resolve`, `POST /service-calls` (STAFF gửi `MANAGER_SUPPORT`); tab Hỗ trợ, MANAGER_SUPPORT chỉ Manager thao tác.
- Gọi món hộ: `GET /categories`, `GET /products` (AVAILABLE, phân trang), `GET /products/{{id}}`, `GET /option-groups/{{id}}`, `POST /orders/sessions/{{id}}/batches` (`clientRequestId` idempotent).
- Sửa/hủy món: `PATCH /orders/items/{{id}}/quantity` (kèm `version`), `POST /orders/items/{{id}}/cancel` (lý do bắt buộc).
- Hóa đơn Manager: `POST /bills/{{id}}/void`, `.../void/withdraw`, `POST /orders/{{id}}/replacement-bill`, `GET /billing/attention`, `POST /bills/{{id}}/refunds`, `POST /refunds/{{id}}/confirm`, `GET /bills/{{id}}/refunds`, `POST /sessions/{{id}}/reopen`.
- Test: `dot3_domain_test.dart` (Dart thuần), `dot23_screens_test.dart` (widget Đợt 2/3) và các fake mới trong `test/support/fakes.dart`.
- Database: không thay đổi.

### D. Vấn đề / chưa xác nhận
- Môi trường AI không có Flutter SDK: `flutter analyze`, `flutter test`, build, chạy thiết bị CHƯA chạy. Chỉ đã kiểm tra: `dart format`, `dart analyze` phần Dart thuần, và 75 test Dart thuần (bộ chạy thay thế) pass. Test widget (`dot23_screens_test.dart`) viết nhưng chưa chạy.
- Chưa thử với backend thật các luồng Đợt 3.
- Yêu cầu từ người dùng còn mở: hiển thị dòng "Phí dịch vụ"/"VAT" trong Hóa đơn tạm tính của Customer Web (mã Customer Web không có trong phiên làm việc này).

### E. Quyết định kỹ thuật
- Cổng quyền theo backend: Void = `BILL_VOID`; hoàn tiền = `REFUND_RECORD`; mở lại phiên = `PAYMENT_PROCESS` hoặc `SESSION_REOPEN`; hóa đơn thay thế = `BILL_VOID`; MANAGER có đủ quyền mặc định.
- Hóa đơn VOIDED + order OPEN (sau mở lại phiên) → cho tính tiền mới; VOIDED + order LOCKED → chờ hóa đơn thay thế.
- Cố ý chưa làm: phiếu hoàn tiền, đóng cưỡng bức phiên, ghi nhận thất thu, báo cáo.

### F. Việc tiếp theo
1. `flutter pub get`, `dart fix --apply`, `flutter analyze`, `flutter test`; sửa lỗi; thử thật với backend.
2. Khi đã pass và thử thật: đổi Đợt 3 thành `COMPLETED`.
3. Việc còn lại của Staff App: phiếu hoàn tiền, đóng cưỡng bức phiên, ghi nhận thất thu; sửa Customer Web (phí dịch vụ).
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
