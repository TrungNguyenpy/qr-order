import { beforeEach, describe, expect, it } from "vitest";
import { selectCartCount, selectCartTotal, useCartStore, type CartLine } from "./cart";

type NewLine = Omit<CartLine, "key">;

const pho = (over: Partial<NewLine> = {}): NewLine => ({
  productId: "p-pho",
  name: "Phở bò tái",
  unitPrice: 85000,
  quantity: 1,
  note: "",
  optionIds: ["o-lon", "o-thit"],
  optionSummary: "Lớn, Thêm thịt",
  ...over,
});

const state = () => useCartStore.getState();

beforeEach(() => {
  useCartStore.setState({ lines: [], pendingRequestId: null });
});

describe("thêm và gộp món", () => {
  it("thêm món mới tạo một dòng", () => {
    state().add(pho());
    expect(state().lines).toHaveLength(1);
    expect(state().lines[0]).toMatchObject({ productId: "p-pho", quantity: 1 });
  });

  it("cùng productId + optionIds + note thì gộp, cộng dồn số lượng", () => {
    state().add(pho({ quantity: 1 }));
    state().add(pho({ quantity: 2 }));
    expect(state().lines).toHaveLength(1);
    expect(state().lines[0]?.quantity).toBe(3);
  });

  it("thứ tự optionIds khác nhau vẫn là cùng một dòng", () => {
    state().add(pho({ optionIds: ["o-lon", "o-thit"] }));
    state().add(pho({ optionIds: ["o-thit", "o-lon"] }));
    expect(state().lines).toHaveLength(1);
    expect(state().lines[0]?.quantity).toBe(2);
  });

  it("khác option thì tách dòng", () => {
    state().add(pho());
    state().add(pho({ optionIds: ["o-nho"] }));
    expect(state().lines).toHaveLength(2);
  });

  it("khác ghi chú thì tách dòng", () => {
    state().add(pho({ note: "Ít cay" }));
    state().add(pho({ note: "Nhiều hành" }));
    expect(state().lines).toHaveLength(2);
  });

  it("khoảng trắng đầu/cuối của ghi chú không làm tách dòng", () => {
    state().add(pho({ note: "Ít cay" }));
    state().add(pho({ note: "  Ít cay  " }));
    expect(state().lines).toHaveLength(1);
  });

  it("số lượng bị chặn ở mức tối đa (mặc định 99)", () => {
    state().add(pho({ quantity: 98 }));
    state().add(pho({ quantity: 5 }));
    expect(state().lines[0]?.quantity).toBe(99);
  });
});

describe("đổi số lượng và xóa", () => {
  it("setQuantity cập nhật số lượng", () => {
    state().add(pho());
    const key = state().lines[0]!.key;
    state().setQuantity(key, 4);
    expect(state().lines[0]?.quantity).toBe(4);
  });

  it("setQuantity <= 0 xóa dòng", () => {
    state().add(pho());
    state().setQuantity(state().lines[0]!.key, 0);
    expect(state().lines).toHaveLength(0);
  });

  it("setQuantity bị chặn ở mức tối đa", () => {
    state().add(pho());
    state().setQuantity(state().lines[0]!.key, 500);
    expect(state().lines[0]?.quantity).toBe(99);
  });

  it("remove chỉ xóa đúng dòng", () => {
    state().add(pho());
    state().add(pho({ optionIds: ["o-nho"] }));
    state().remove(state().lines[0]!.key);
    expect(state().lines).toHaveLength(1);
    expect(state().lines[0]?.optionIds).toEqual(["o-nho"]);
  });

  it("clear xóa hết giỏ và requestId", () => {
    state().add(pho());
    state().ensureRequestId();
    state().clear();
    expect(state().lines).toEqual([]);
    expect(state().pendingRequestId).toBeNull();
  });
});

describe("tổng tiền và số lượng", () => {
  it("tính tổng = Σ số lượng × đơn giá và tổng số món", () => {
    state().add(pho({ quantity: 2, unitPrice: 85000 }));
    state().add({ productId: "p-tra", name: "Trà đá", unitPrice: 9000, quantity: 3, note: "", optionIds: [], optionSummary: "" });
    expect(selectCartTotal(state())).toBe(2 * 85000 + 3 * 9000);
    expect(selectCartCount(state())).toBe(5);
  });

  it("giỏ rỗng có tổng 0", () => {
    expect(selectCartTotal(state())).toBe(0);
    expect(selectCartCount(state())).toBe(0);
  });
});

describe("clientRequestId (chống gửi trùng)", () => {
  it("ensureRequestId trả UUID và giữ nguyên khi gọi lại (retry lỗi mạng)", () => {
    state().add(pho());
    const first = state().ensureRequestId();
    expect(first).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
    expect(state().ensureRequestId()).toBe(first);
    expect(state().ensureRequestId()).toBe(first);
  });

  it("thêm món làm requestId đổi", () => {
    state().add(pho());
    const first = state().ensureRequestId();
    state().add(pho({ optionIds: ["o-nho"] }));
    expect(state().pendingRequestId).toBeNull();
    expect(state().ensureRequestId()).not.toBe(first);
  });

  it("đổi số lượng hoặc xóa món làm requestId đổi", () => {
    state().add(pho());
    const key = state().lines[0]!.key;

    const a = state().ensureRequestId();
    state().setQuantity(key, 3);
    const b = state().ensureRequestId();
    expect(b).not.toBe(a);

    state().remove(key);
    expect(state().pendingRequestId).toBeNull();
  });

  it("resetRequestId (sau gửi thành công hoặc lỗi 4xx) tạo id mới ở lần sau", () => {
    state().add(pho());
    const a = state().ensureRequestId();
    state().resetRequestId();
    expect(state().ensureRequestId()).not.toBe(a);
  });
});
