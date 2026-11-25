#!/usr/bin/env python3
"""Ghi tiến độ Staff App Đợt 2 vào PROJECT_PROGRESS.md của repo (không xóa lịch sử).

Cách dùng (từ thư mục gốc repo):
    python staff_app/docs/apply_staff_app_progress_dot2.py [đường_dẫn/PROJECT_PROGRESS.md]

Idempotent: chạy lại sẽ thay đúng khối đã chèn trước đó, không nhân đôi.
Trạng thái được ghi TRUNG THỰC: IN PROGRESS cho tới khi bạn chạy `flutter analyze`,
`flutter test` và dán kết quả xác nhận; sau đó đổi nhãn thành COMPLETED bằng tay.
"""
import pathlib
import sys

START = "<!-- STAFF_APP_DOT2:START -->"
END = "<!-- STAFF_APP_DOT2:END -->"

BLOCK = f"""{START}
## Staff App (Flutter) — Đợt 2: đơn hàng, bếp (KDS), thu ngân

**Trạng thái: `IN PROGRESS`** — mã nguồn và test đã viết; `flutter analyze` / `flutter test` / chạy thiết bị với backend thật CHƯA được xác nhận (xem mục D). Đổi thành `COMPLETED` bằng tay sau khi đã pass.

### B. Đã triển khai
- Đơn hàng: danh sách đợt chờ xác nhận (`GET /orders/batches/pending`), claim/release/confirm/reject (`POST /orders/batches/{{id}}/...`, TTL claim ~60s, 409 `BATCH_CLAIMED_BY_OTHER`), từ chối từng món khi xác nhận; món sẵn sàng + `POST /orders/items/{{id}}/serve`; yêu cầu hủy món `GET /orders/cancel-requests`, `approve`, `deny` (lý do bắt buộc).
- Bếp: `GET /kitchen/items`, `POST /kitchen/items/{{id}}/status` (PREPARING|READY), `POST /kitchen/items/{{id}}/reject`.
- Thu ngân: `POST/GET /orders/{{orderId}}/bill` (giảm giá tùy chọn), `POST /bills/{{id}}/payments` (idempotent bằng `requestId` UUID), `POST /payments/{{id}}/confirm`, `GET /payments/{{id}}/receipt`.
- Hạ tầng: `LiveListController` + `RealtimeReloader` (STOMP + nối lại + thăm dò 30s); `User.has(Permission)` (MANAGER có đủ quyền); router thay 3 màn giữ chỗ.
- Chi tiết bàn: luôn có dòng Mã PIN bàn ("Chưa có PIN").
- Database: không thay đổi.

### D. Vấn đề / chưa xác nhận
- Môi trường AI không có Flutter SDK: `flutter analyze`, `flutter test`, build, chạy thiết bị CHƯA chạy. Chỉ đã kiểm tra: `dart format`, và 51 test Dart thuần (bộ chạy thay thế) pass. Test widget/provider Đợt 2 CHƯA có.
- Chưa thử với backend thật các luồng Đợt 2.

### E. Quyết định kỹ thuật
- Mở đợt = tự claim; đóng mà chưa quyết = release.
- Cổng quyền: sàn = MANAGER/STAFF; bếp = MANAGER/KITCHEN; duyệt hủy = `ITEM_CANCEL_APPROVE`; thu tiền = `PAYMENT_PROCESS`; giảm giá = `BILL_DISCOUNT`.
- Cố ý chưa làm: void/hoàn tiền/mở lại/hóa đơn thay thế (Manager), khách gọi nhân viên, nhân viên gọi món hộ, sửa số lượng, nhân viên hủy trực tiếp.

### F. Việc tiếp theo
1. `flutter pub get`, `dart fix --apply`, `flutter analyze`, `flutter test`; sửa lỗi; thử thật với backend.
2. Viết test widget Đợt 2 nếu cần; sau đó đổi Đợt 2 thành `COMPLETED`.
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
