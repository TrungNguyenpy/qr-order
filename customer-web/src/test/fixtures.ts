/**
 * JSON mẫu theo hợp đồng của backend (`qr-order-backend/README.md` + phản hồi thật đã quan sát khi chạy E2E
 * ngày 22/11/2026). Mỗi mẫu lưu đúng hình dạng đã gây lỗi mapping ở CW-06 để chặn tái phát.
 * Giá trị chỉ là dữ liệu minh họa; không phải bản sao nguyên văn từ database.
 */

/** POST /public/sessions/join (đã bóc ApiResponse.data): phiên lồng trong `session`. */
export const joinResponse = {
  accessToken: "jwt.access.token",
  tokenType: "Bearer",
  expiresAt: "2026-11-22T10:15:00Z",
  newSession: true,
  session: {
    sessionId: "6f1c1d9e-0000-4000-8000-000000000001",
    status: "OPEN",
    canOrder: true,
    pinRequired: false,
    pinVerified: false,
    table: { id: "t-1", code: "T01", displayName: "Bàn 1", area: "Tầng 1" },
  },
};

/** GET /public/menu: danh mục lồng sản phẩm; option theo schema (`selectionType`, `extraPrice`, `active`). */
export const menuResponse = {
  categories: [
    {
      id: "cat-1",
      name: "Món chính",
      products: [
        {
          id: "p-pho",
          name: "Phở bò tái",
          description: "Phở nước trong",
          price: 55000,
          imageUrl: "https://res.cloudinary.com/demo/pho.jpg",
          available: true,
          optionGroups: [
            {
              id: "g-size",
              name: "Kích cỡ",
              selectionType: "SINGLE",
              required: true,
              minSelect: 1,
              maxSelect: 1,
              options: [
                { id: "o-nho", name: "Nhỏ", extraPrice: 0, active: true },
                { id: "o-lon", name: "Lớn", extraPrice: 10000, active: true },
              ],
            },
            {
              id: "g-topping",
              name: "Thêm",
              selectionType: "MULTIPLE",
              required: false,
              minSelect: 0,
              maxSelect: null,
              options: [
                { id: "o-trung", name: "Thêm trứng", extraPrice: 5000, active: true },
                { id: "o-thit", name: "Thêm thịt", extraPrice: 15000, active: true },
              ],
            },
          ],
        },
        { id: "p-hetmon", name: "Bún chả", description: "", price: 60000, available: false, optionGroups: [] },
      ],
    },
    { id: "cat-2", name: "Đồ uống", products: [] },
  ],
};

/** GET /guest/orders: các đợt + món + `billableTotal`. */
export const ordersResponse = {
  orderId: "ord-1",
  status: "OPEN",
  billableTotal: 103000,
  batches: [
    {
      batchId: "b-1",
      batchNo: 1,
      status: "PENDING",
      submittedAt: "2026-11-22T09:00:00Z",
      items: [
        {
          itemId: "i-1",
          productName: "Phở bò tái",
          quantity: 1,
          unitPrice: 85000,
          lineTotal: 85000,
          note: "Ít cay",
          status: "PENDING",
          options: [{ name: "Lớn" }, { name: "Thêm thịt" }],
        },
        { itemId: "i-2", productName: "Trà đá", quantity: 2, unitPrice: 9000, lineTotal: 18000, status: "CONFIRMED", options: [] },
      ],
    },
  ],
};

/** GET /guest/bill: tên field kết thúc bằng `Amount` (CW-06 d). */
export const billResponse = {
  billId: "bill-1",
  status: "ACTIVE",
  subtotalAmount: 103000,
  discountAmount: 10300,
  vatAmount: 6867,
  totalAmount: 92700,
  lines: [{ productName: "Phở bò tái", quantity: 1, lineTotal: 85000 }],
};
