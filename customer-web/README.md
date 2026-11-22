# QR Order — Customer Web

Next.js (App Router) · TypeScript · Tailwind CSS v4 · Axios · TanStack Query · Zustand · STOMP (`@stomp/stompjs`).

## Chạy

```bash
cp .env.example .env.local   # chỉnh NEXT_PUBLIC_API_URL / NEXT_PUBLIC_WS_URL
npm install
npm run dev                  # http://localhost:3000
npm run typecheck && npm run lint && npm test && npm run build
```

## Kiểm thử

`npm test` (Vitest + jsdom, không cần backend). Gồm 3 nhóm: mapper `lib/api/types.test.ts` (JSON mẫu trong `src/test/fixtures.ts`), giỏ hàng `stores/cart.test.ts`, gia hạn JWT `lib/api/client.test.ts` (giả lập backend bằng adapter Axios).
Khi backend đổi hình dạng JSON, cập nhật `src/test/fixtures.ts` theo phản hồi thật rồi chạy lại test. CI: `.github/workflows/customer-web-ci.yml` (ở gốc repository).

Mở `http://localhost:3000/t/<qrToken>` (token lấy từ mã QR của bàn trong Admin/Swagger).

## Cấu trúc

```
src/
  app/            # routes: / , /t/[token] (join + PIN), (app)/{menu,cart,orders,service}
  components/ui/  # Button, Skeleton, EmptyState, ErrorState, BottomSheet, Stepper
  hooks/          # useSessionRuntime: gia hạn JWT + STOMP -> invalidate query
  lib/api/        # client (Axios, unwrap ApiResponse, refresh đơn luồng), endpoints, types + normalizer
  lib/realtime/   # STOMP (ws:// = WebSocket thuần, http(s):// = SockJS)
  stores/         # session (persist), cart (persist)
  test/           # setup + fixtures JSON mẫu cho Vitest
```

## Quyết định chính

- **Hợp đồng API theo `qr-order-backend/README.md`**: đặt món `POST /guest/orders`, yêu cầu hủy `.../cancel-requests`.
- `clientRequestId` giữ nguyên khi retry lỗi mạng/5xx, đổi khi giỏ thay đổi hoặc gặp lỗi 4xx.
- JWT gia hạn trước 60s khi hết hạn; 401 -> gia hạn 1 lần rồi retry; thất bại -> re-join bằng `qrToken` + `deviceId`. `401 SESSION_CLOSED` -> màn kết thúc phiên.
- Sự kiện WebSocket chỉ là tín hiệu làm mới: mọi dữ liệu lấy lại từ REST (cả khi kết nối lại).
- Tên field JSON của menu/đơn/bill được chuẩn hóa ở `lib/api/types.ts` (chấp nhận vài tên thay thế). **Cần đối chiếu với OpenAPI khi chạy cùng backend.**
