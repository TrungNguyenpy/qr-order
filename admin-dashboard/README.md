# QR Order - Admin Dashboard (Đợt 1 + Đợt 2)

Giao diện quản trị cho ADMIN và MANAGER. Next.js 16 (App Router), React 19, TypeScript strict, Tailwind v4, Axios, TanStack Query v5, Zustand v5, `@stomp/stompjs`, Recharts, Vitest.

## Phạm vi Đợt 1

| Nhóm | Màn hình | Vai trò |
| :--- | :--- | :--- |
| Đăng nhập | `/login`, đổi mật khẩu (menu người dùng), đăng xuất | ADMIN, MANAGER |
| Nhân sự & phân quyền | `/employees`: danh sách có lọc/phân trang, tạo, sửa họ tên, khóa/mở khóa/ngừng, đổi role (mật khẩu + lý do), cấp/thu hồi permission, đặt lại mật khẩu | ADMIN |
| Cấu hình nhà hàng | `/settings` (thuế, phí phục vụ, phiên, giới hạn đặt món) kèm `version` | ADMIN |
| Menu | `/menu/categories`, `/menu/products`, `/menu/options` | ADMIN, MANAGER |
| Bàn & QR | `/tables`: sơ đồ bàn (realtime), danh sách, CRUD bàn (ADMIN), xác nhận dọn bàn (MANAGER), QR (xem, sinh, xoay, thu hồi, lịch sử, PNG, PDF, PDF hàng loạt; ADMIN) | ADMIN, MANAGER |

## Phạm vi Đợt 2

| Nhóm | Màn hình | Quyền cần có |
| :--- | :--- | :--- |
| Tổng quan & Báo cáo | `/reports` (`/` chuyển hướng về đây): KPI, biểu đồ doanh thu (ngày/tuần/tháng/ca) + theo phương thức thanh toán, top món, thời gian phục vụ trung bình/trung vị, xuất CSV (UTF-8 BOM) | `REPORT_VIEW` |
| Phiên & Đơn hàng | `/sessions`: danh sách phân trang + lọc; chi tiết phiên (bàn, số thiết bị, đơn theo đợt, dòng thời gian món, Bill); force-close; mở lại phiên | Xem danh sách: MANAGER hoặc `REPORT_VIEW`. Xem đơn/Bill: MANAGER. Force-close: `SESSION_FORCE_CLOSE`. Mở lại: `PAYMENT_PROCESS` hoặc `SESSION_REOPEN` |
| Vận hành | `/operations`: duyệt/từ chối yêu cầu hủy món; hóa đơn cần xử lý (Void, rút Void, hoàn tiền, Bill thay thế) | Hủy món: `ITEM_CANCEL_APPROVE`. Danh sách/Bill: MANAGER. Void/Bill thay thế: `BILL_VOID`. Hoàn tiền: `REFUND_RECORD` |

### Lưu ý về quyền và hợp đồng (Đợt 2)

- **ADMIN không kế thừa quyền MANAGER** (BR-A06). ADMIN muốn xem báo cáo phải tự cấp `REPORT_VIEW` ở Nhân sự → Phân quyền (cần mật khẩu), rồi đăng nhập lại. Backend không cho ADMIN đọc chi tiết đơn, Bill và danh sách Cần xử lý dù đã tự cấp quyền; giao diện hiện thông báo thiếu quyền ở từng khối thay vì 403 chung.
- Từ chối yêu cầu hủy món gọi `POST /orders/cancel-requests/{id}/deny` (backend dùng `deny`, không phải `reject`).
- Backend chưa có API lịch sử trạng thái món: dòng thời gian được dựng từ các mốc thời gian của món. `StaffSessionDto` chỉ có số thiết bị, không có danh sách thiết bị.
- Hoàn tiền: `requestId` (UUID) sinh một lần mỗi lần mở hộp thoại, thử lại dùng cùng giá trị nên không ghi trùng. Bill thay thế tạo không kèm giảm giá.
- Realtime: MANAGER nhận STOMP và các truy vấn phiên/đơn/Bill được làm mới; ADMIN làm mới mỗi 30 giây.

## Chạy

```bash
cd admin-dashboard
cp .env.example .env.local      # chỉnh địa chỉ backend nếu cần
npm ci
npm run dev                     # http://localhost:3001
```

Kiểm tra trước khi giao: `npm run typecheck && npm run lint && npm test && npm run build` (CI chạy đúng chuỗi này, xem `.github/workflows/admin-dashboard-ci.yml`).

### Biến môi trường

| Biến | Mặc định (`.env.example`) | Ý nghĩa |
| :--- | :--- | :--- |
| `NEXT_PUBLIC_API_URL` | `http://localhost:8081` | Gốc backend (khi chạy `docker compose` backend ở cổng 8081) |
| `NEXT_PUBLIC_WS_URL` | `ws://localhost:8081/ws/websocket` | STOMP qua WebSocket thuần. Bỏ trống thì sơ đồ bàn chỉ làm mới định kỳ |
| `NEXT_PUBLIC_MAX_IMAGE_SIZE_MB` | `5` | Giới hạn kiểm tra ảnh món phía client (backend vẫn kiểm tra lại) |

### Cấu hình backend cần có

Admin chạy ở cổng **3001** (song song Customer Web ở 3000). Thêm origin vào `.env` của backend rồi khởi động lại:

```
CORS_ALLOWED_ORIGINS=http://localhost:3000,http://localhost:3001
WS_ALLOWED_ORIGINS=http://localhost:3000,http://localhost:3001
```

Ảnh món cần backend đã cấu hình Cloudinary (`CLOUDINARY_*`), QR cần `QR_BASE_URL` và `QR_TOKEN_SECRET`; nếu thiếu, backend trả lỗi và giao diện hiển thị thông báo tương ứng.

## Hợp đồng API và quy tắc đã áp dụng

- Tên field lấy từ mã nguồn backend (`qr-order-backend`), không suy từ README. `src/lib/api/types.ts` là nơi duy nhất khai báo DTO.
- **Không xóa cứng** (BR-A03): ẩn/hiện danh mục, trạng thái món `AVAILABLE/SOLD_OUT/HIDDEN`, bật/tắt nhóm option và lựa chọn, `active` của bàn, `LOCKED/DISABLED` của nhân viên.
- **Khóa lạc quan:** mọi cập nhật gửi `version`. `409 CONCURRENT_MODIFICATION` làm toàn bộ truy vấn được tải lại và biểu mẫu hiện nút "Tải lại dữ liệu".
- **Làm mới token tuần tự (BE-06):** một promise dùng chung cho mọi request 401 và STOMP, thêm khóa liên tab (Web Locks) và đọc lại `localStorage` trước khi refresh. Lỗi mạng/5xx khi refresh không đăng xuất.
- **`403 REAUTHENTICATION_FAILED`** hiện ngay trên ô mật khẩu của hộp thoại xác nhận và **không** đăng xuất.
- **Phân quyền:** ADMIN không kế thừa quyền MANAGER (BR-A06). Chỉ MANAGER xác nhận dọn bàn; chỉ ADMIN quản lý bàn/QR, nhân sự, cấu hình.
- Backend thu hồi refresh token khi đổi role, permission, trạng thái hoặc mật khẩu của chính người đang đăng nhập; khi đó giao diện đăng xuất cục bộ kèm thông báo lý do.
- **Realtime:** backend chỉ cho STAFF/MANAGER subscribe `/topic/tables` và `/topic/staff/orders`, nên MANAGER dùng STOMP, còn ADMIN làm mới sơ đồ bàn mỗi 30 giây. Mỗi sự kiện và mỗi lần kết nối lại đều tải lại bằng REST.
- Mọi màn dữ liệu có đủ Loading (skeleton), Empty, Error (nút "Thử lại"), Success.

## Cấu trúc

```
src/app/            route (nhóm (admin) có AppShell + AccessGuard)
src/features/       users, settings, menu, tables, auth, reports, sessions, operations, billing, orders
src/components/ui/  thành phần dùng chung (Modal, DataTable, ReasonDialog, ...)
src/lib/api/        Axios client, DTO, lỗi, tải file nhị phân
src/lib/realtime/   STOMP
src/stores/         Zustand (auth persist, toast)
```

## Kiểm thử tự động

`npm test` chạy 46 test Vitest không cần backend (thêm: khoảng ngày/định dạng/dữ liệu biểu đồ báo cáo, dòng thời gian món, kiểm tra số tiền hoàn): làm mới token đồng thời/thất bại/mạng, 403 không đăng xuất, quy tắc truy cập, BR-O03, kiểm tra form cấu hình (BR-P04, quy đổi %), kiểm tra form bàn, chuẩn hóa địa chỉ WebSocket. Chưa có test component và chưa chạy cùng backend thật.
