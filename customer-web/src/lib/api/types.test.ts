import { describe, expect, it } from "vitest";
import { bankTransferResponse, billResponse, joinResponse, menuResponse, ordersResponse } from "@/test/fixtures";
import { toBankTransfer, toBill, toJoinResult, toMenu, toOrderView, toServiceCall, toTokenResult } from "./types";

describe("toJoinResult", () => {
  it("đọc phiên lồng trong data.session (CW-06 b)", () => {
    expect(toJoinResult(joinResponse)).toEqual({
      token: "jwt.access.token",
      sessionId: "6f1c1d9e-0000-4000-8000-000000000001",
      tableName: "Bàn 1",
      pinRequired: false,
      canOrder: true,
      sessionStatus: "OPEN",
    });
  });

  it("hiển thị nguyên displayName, không thêm tiền tố (CW-06 e)", () => {
    expect(toJoinResult(joinResponse).tableName).toBe("Bàn 1");
  });

  it("cần PIN khi pinRequired và chưa pinVerified", () => {
    const raw = { ...joinResponse, session: { ...joinResponse.session, pinRequired: true, pinVerified: false } };
    expect(toJoinResult(raw).pinRequired).toBe(true);
  });

  it("không cần PIN khi thiết bị đã pinVerified", () => {
    const raw = { ...joinResponse, session: { ...joinResponse.session, pinRequired: true, pinVerified: true } };
    expect(toJoinResult(raw).pinRequired).toBe(false);
  });

  it("canOrder=false và status BILLING được giữ nguyên", () => {
    const raw = { ...joinResponse, session: { ...joinResponse.session, canOrder: false, status: "BILLING" } };
    const r = toJoinResult(raw);
    expect(r.canOrder).toBe(false);
    expect(r.sessionStatus).toBe("BILLING");
  });

  it("chịu được dữ liệu rỗng/sai kiểu mà không ném lỗi", () => {
    for (const bad of [null, undefined, 42, "x", []]) {
      const r = toJoinResult(bad);
      expect(r.token).toBeNull();
      expect(r.sessionId).toBe("");
      expect(r.canOrder).toBe(true);
    }
  });
});

describe("toTokenResult", () => {
  it("đọc accessToken hoặc guestToken", () => {
    expect(toTokenResult({ accessToken: "a" }).token).toBe("a");
    expect(toTokenResult({ guestToken: "g" }).token).toBe("g");
    expect(toTokenResult({}).token).toBeNull();
  });
});

describe("toMenu", () => {
  const menu = toMenu(menuResponse);

  it("trả danh mục và gán categoryId cho sản phẩm lồng", () => {
    expect(menu.categories.map((c) => c.name)).toEqual(["Món chính", "Đồ uống"]);
    expect(menu.products).toHaveLength(2);
    expect(menu.products.every((p) => p.categoryId === "cat-1")).toBe(true);
  });

  it("món available=false được đánh dấu hết món (SOLD_OUT)", () => {
    expect(menu.products.find((p) => p.id === "p-hetmon")?.available).toBe(false);
    expect(menu.products.find((p) => p.id === "p-pho")?.available).toBe(true);
  });

  it("selectionType SINGLE: bắt buộc, tối đa 1, extraPrice thành priceDelta", () => {
    const size = menu.products[0]?.optionGroups.find((g) => g.id === "g-size");
    expect(size).toMatchObject({ type: "SINGLE", required: true, minSelect: 1, maxSelect: 1 });
    expect(size?.options.map((o) => o.priceDelta)).toEqual([0, 10000]);
  });

  it("selectionType MULTIPLE: không bắt buộc, maxSelect null = không giới hạn", () => {
    const top = menu.products[0]?.optionGroups.find((g) => g.id === "g-topping");
    expect(top).toMatchObject({ type: "MULTIPLE", required: false, minSelect: 0, maxSelect: null });
  });

  it("option `active` mặc định true khi backend không gửi `available`", () => {
    const size = menu.products[0]?.optionGroups[0];
    expect(size?.options.every((o) => o.available)).toBe(true);
  });

  it("suy ra SOLD_OUT từ status khi không có cờ available", () => {
    const m = toMenu({ products: [{ id: "x", name: "X", price: 1, status: "SOLD_OUT" }] });
    expect(m.products[0]?.available).toBe(false);
  });

  it("chấp nhận dạng phẳng categories[] + products[]", () => {
    const m = toMenu({ categories: [{ id: "c", name: "C" }], products: [{ id: "p", categoryId: "c", name: "P", price: 5, available: true }] });
    expect(m.products[0]).toMatchObject({ id: "p", categoryId: "c", price: 5 });
  });

  it("menu rỗng/sai kiểu trả mảng rỗng", () => {
    expect(toMenu(null)).toEqual({ categories: [], products: [] });
  });
});

describe("toOrderView", () => {
  const order = toOrderView(ordersResponse);

  it("đọc billableTotal do backend tính", () => {
    expect(order.billableTotal).toBe(103000);
  });

  it("billableTotal null khi backend không gửi", () => {
    expect(toOrderView({ batches: [] }).billableTotal).toBeNull();
  });

  it("đọc đợt, số đợt, thời điểm gửi", () => {
    expect(order.status).toBe("OPEN");
    expect(order.batches).toHaveLength(1);
    expect(order.batches[0]).toMatchObject({ id: "b-1", batchNo: 1, createdAt: "2026-11-22T09:00:00Z" });
  });

  it("đọc món: id, số lượng, thành tiền, ghi chú, trạng thái, option", () => {
    const [phở, trà] = order.batches[0]?.items ?? [];
    expect(phở).toMatchObject({
      id: "i-1",
      productName: "Phở bò tái",
      quantity: 1,
      lineTotal: 85000,
      note: "Ít cay",
      status: "PENDING",
      optionSummary: "Lớn, Thêm thịt",
    });
    expect(trà).toMatchObject({ id: "i-2", quantity: 2, lineTotal: 18000, status: "CONFIRMED", note: "", optionSummary: "" });
  });

  it("thiếu lineTotal thì tính = đơn giá × số lượng", () => {
    const v = toOrderView({ batches: [{ items: [{ itemId: "z", productName: "Z", quantity: 3, unitPrice: 1000 }] }] });
    expect(v.batches[0]?.items[0]?.lineTotal).toBe(3000);
  });

  it("đánh số đợt theo thứ tự khi thiếu batchNo", () => {
    const v = toOrderView({ batches: [{ items: [] }, { items: [] }] });
    expect(v.batches.map((b) => b.batchNo)).toEqual([1, 2]);
  });

  it("dữ liệu rỗng: Order OPEN, không đợt", () => {
    expect(toOrderView(undefined)).toEqual({ status: "OPEN", batches: [], billableTotal: null });
  });
});

describe("toBill", () => {
  it("đọc subtotalAmount, discountAmount, vatAmount, totalAmount (CW-06 d)", () => {
    expect(toBill(billResponse)).toMatchObject({
      subtotal: 103000,
      discount: 10300,
      vatAmount: 6867,
      total: 92700,
      status: "ACTIVE",
    });
  });

  it("vẫn chấp nhận tên field ngắn", () => {
    expect(toBill({ subtotal: 10, discount: 1, vatAmount: 0, total: 9 })).toMatchObject({ subtotal: 10, discount: 1, total: 9 });
  });

  it("đọc dòng hóa đơn", () => {
    expect(toBill(billResponse).lines).toEqual([{ name: "Phở bò tái", quantity: 1, amount: 85000 }]);
  });

  it("thiếu dữ liệu thì về 0, không NaN", () => {
    const b = toBill({});
    expect([b.subtotal, b.discount, b.vatAmount, b.total]).toEqual([0, 0, 0, 0]);
  });
});

describe("bankTransfer (VietQR)", () => {
  it("toBill đọc bankTransfer khi backend gửi", () => {
    expect(toBill({ ...billResponse, bankTransfer: bankTransferResponse }).bankTransfer).toEqual(bankTransferResponse);
  });

  it("không có bankTransfer thì null: nhà hàng chưa cấu hình ngân hàng hoặc đã thu đủ", () => {
    expect(toBill(billResponse).bankTransfer).toBeNull();
    expect(toBill({ ...billResponse, bankTransfer: null }).bankTransfer).toBeNull();
  });

  it("thiếu URL ảnh hoặc số tài khoản thì không hiển thị QR", () => {
    expect(toBankTransfer({ ...bankTransferResponse, qrImageUrl: "" })).toBeNull();
    expect(toBankTransfer({ ...bankTransferResponse, accountNo: "" })).toBeNull();
    expect(toBankTransfer("x")).toBeNull();
  });

  it("chỉ nhận URL ảnh http(s), từ chối scheme khác", () => {
    expect(toBankTransfer({ ...bankTransferResponse, qrImageUrl: "javascript:alert(1)" })).toBeNull();
    expect(toBankTransfer({ ...bankTransferResponse, qrImageUrl: "data:image/png;base64,AAAA" })).toBeNull();
    expect(toBankTransfer({ ...bankTransferResponse, qrImageUrl: "http://localhost:9000/qr.png" })?.qrImageUrl).toBe("http://localhost:9000/qr.png");
  });

  it("số tiền sai kiểu về 0, không NaN", () => {
    expect(toBankTransfer({ ...bankTransferResponse, amount: "abc" })?.amount).toBe(0);
    expect(toBankTransfer({ ...bankTransferResponse, amount: "92700" })?.amount).toBe(92700);
  });
});

describe("toServiceCall", () => {
  it("đọc id, loại, trạng thái", () => {
    expect(toServiceCall({ id: "sc", type: "CALL_STAFF", status: "OPEN" })).toEqual({ id: "sc", type: "CALL_STAFF", status: "OPEN" });
  });
});
