<!-- Dán đoạn này vào qr-order-backend/README.md, ngay sau mục "Chạy nhanh", rồi xóa file snippet này. -->

## Chạy cùng Customer Web (môi trường dev cục bộ)

Dùng `docker compose` (không dùng `./gradlew bootRun` trần: nó không có biến môi trường nên dễ kết nối nhầm PostgreSQL cài sẵn trên máy ở cổng 5432, lỗi `role "qr_order" does not exist`).

1. Trong `.env` ở thư mục gốc repository, ngoài các biến bắt buộc, thêm **origin của Customer Web** (nếu thiếu, trình duyệt bị chặn CORS và web báo "Không thể kết nối tới máy chủ"):

   ```env
   CORS_ALLOWED_ORIGINS=http://localhost:3000
   WS_ALLOWED_ORIGINS=http://localhost:3000
   ```

   Nhiều origin cách nhau bằng dấu phẩy, không có `/` cuối. Thử trên điện thoại qua mạng LAN thì thêm `http://<IP-máy>:3000` vào cả hai biến.
2. Chạy hoặc tạo lại container để nhận biến mới: `docker compose up -d --build` (đã chạy sẵn thì `docker compose up -d --force-recreate backend`).
3. Cổng trên máy host do `SERVER_PORT` trong `.env` quyết định (ví dụ `8081`; trong container luôn là `8080`). PostgreSQL của docker compose ở cổng host `5433`, database `qr_order_db`.
4. `customer-web/.env.local` phải trỏ đúng cổng đó, rồi chạy lại `npm run dev`:

   ```env
   NEXT_PUBLIC_API_URL=http://localhost:8081
   NEXT_PUBLIC_WS_URL=ws://localhost:8081/ws/websocket
   ```

   `ws://.../ws/websocket` là WebSocket thuần (khuyến nghị). `http://.../ws` dùng SockJS, trình duyệt có thể cảnh báo `unload is not allowed`.
5. Mở `http://localhost:3000/t/dev-qr-T01` (QR seed dev của bàn T01).
