/**
 * DTO phía client + lớp chuẩn hóa (normalizer).
 *
 * LƯU Ý: README backend không liệt kê tên field JSON chi tiết của public menu / guest orders.
 * Các mapper bên dưới chấp nhận một số tên field thay thế và gán mặc định an toàn.
 * Cần đối chiếu với OpenAPI/Swagger khi chạy cùng backend thật (xem PROJECT_PROGRESS.md).
 */

export type Rec = Record<string, unknown>;

const isRec = (v: unknown): v is Rec => typeof v === "object" && v !== null && !Array.isArray(v);
const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);

function pick(o: Rec, ...keys: string[]): unknown {
  for (const k of keys) if (o[k] !== undefined && o[k] !== null) return o[k];
  return undefined;
}
const str = (v: unknown, d = ""): string => (typeof v === "string" ? v : v == null ? d : String(v));
const num = (v: unknown, d = 0): number => {
  const n = typeof v === "number" ? v : Number(v);
  return Number.isFinite(n) ? n : d;
};
const bool = (v: unknown, d = false): boolean => (typeof v === "boolean" ? v : v == null ? d : v === "true");
const optNum = (v: unknown): number | null => (v == null || v === "" ? null : num(v));

/* ───────────── Session ───────────── */

export interface JoinResult {
  token: string | null;
  sessionId: string;
  tableName: string;
  pinRequired: boolean;
  canOrder: boolean;
  sessionStatus: string;
}

export function toJoinResult(raw: unknown): JoinResult {
  const o = isRec(raw) ? raw : {};
  // Hợp đồng thật: { accessToken, tokenType, expiresAt, newSession, session: { sessionId, status, canOrder, table{code, displayName}, pinRequired, pinVerified } }
  const s = isRec(o.session) ? o.session : o;
  const table = isRec(s.table) ? s.table : {};
  return {
    token: (pick(o, "accessToken", "guestToken", "token") as string | undefined) ?? null,
    sessionId: str(pick(s, "sessionId", "tableSessionId", "id")),
    tableName: str(pick(table, "displayName", "name", "code") ?? pick(s, "tableName", "tableCode")),
    pinRequired: bool(pick(s, "pinRequired", "requiresPin", "requirePin")) && !bool(s.pinVerified),
    canOrder: bool(pick(s, "canOrder"), true),
    sessionStatus: str(pick(s, "status", "sessionStatus"), "OPEN"),
  };
}

export function toTokenResult(raw: unknown): { token: string | null } {
  const o = isRec(raw) ? raw : {};
  return { token: (pick(o, "guestToken", "accessToken", "token") as string | undefined) ?? null };
}

/* ───────────── Menu ───────────── */

export type OptionType = "SINGLE" | "MULTIPLE";

export interface MenuOption {
  id: string;
  name: string;
  priceDelta: number;
  available: boolean;
}
export interface MenuOptionGroup {
  id: string;
  name: string;
  type: OptionType;
  required: boolean;
  minSelect: number;
  /** null = không giới hạn (BR-O03). */
  maxSelect: number | null;
  options: MenuOption[];
}
export interface MenuProduct {
  id: string;
  categoryId: string;
  name: string;
  description: string;
  price: number;
  imageUrl: string | null;
  available: boolean;
  optionGroups: MenuOptionGroup[];
}
export interface MenuCategory {
  id: string;
  name: string;
}
export interface Menu {
  categories: MenuCategory[];
  products: MenuProduct[];
}

function toOption(raw: unknown): MenuOption {
  const o = isRec(raw) ? raw : {};
  return {
    id: str(pick(o, "id", "optionId")),
    name: str(pick(o, "name", "label")),
    priceDelta: num(pick(o, "priceDelta", "extraPrice", "price", "additionalPrice")),
    available: bool(pick(o, "available", "isAvailable"), true),
  };
}

function toGroup(raw: unknown): MenuOptionGroup {
  const o = isRec(raw) ? raw : {};
  const type: OptionType = str(pick(o, "type", "selectionType")).toUpperCase() === "MULTIPLE" ? "MULTIPLE" : "SINGLE";
  const minSelect = num(pick(o, "minSelect", "minSelection"), bool(o.required) ? 1 : 0);
  const rawMax = optNum(pick(o, "maxSelect", "maxSelection"));
  return {
    id: str(pick(o, "id", "groupId")),
    name: str(pick(o, "name", "label")),
    type,
    required: bool(o.required, minSelect >= 1),
    minSelect,
    maxSelect: type === "SINGLE" ? 1 : rawMax,
    options: arr(pick(o, "options", "items", "choices")).map(toOption),
  };
}

function toProduct(raw: unknown, fallbackCategoryId = ""): MenuProduct {
  const o = isRec(raw) ? raw : {};
  const status = str(pick(o, "status")).toUpperCase();
  const explicit = pick(o, "available", "isAvailable");
  return {
    id: str(pick(o, "id", "productId")),
    categoryId: str(pick(o, "categoryId"), fallbackCategoryId),
    name: str(o.name),
    description: str(pick(o, "description", "desc")),
    price: num(pick(o, "price", "basePrice")),
    imageUrl: (pick(o, "imageUrl", "image", "thumbnailUrl") as string | undefined) ?? null,
    available: explicit !== undefined ? bool(explicit) : status !== "SOLD_OUT",
    optionGroups: arr(pick(o, "optionGroups", "options", "optionsGroups")).map(toGroup),
  };
}

/** Chấp nhận cả dạng lồng (category.products[]) lẫn dạng phẳng (categories[] + products[]). */
export function toMenu(raw: unknown): Menu {
  const o = isRec(raw) ? raw : {};
  const catsRaw = arr(pick(o, "categories", "items"));
  const categories: MenuCategory[] = [];
  const products: MenuProduct[] = [];
  for (const c of catsRaw) {
    const co = isRec(c) ? c : {};
    const id = str(pick(co, "id", "categoryId"));
    categories.push({ id, name: str(co.name) });
    for (const p of arr(pick(co, "products", "items"))) products.push(toProduct(p, id));
  }
  for (const p of arr(o.products)) products.push(toProduct(p));
  return { categories, products };
}

/* ───────────── Orders ───────────── */

export type ItemStatus = "PENDING" | "CONFIRMED" | "PREPARING" | "READY" | "SERVED" | "REJECTED" | "CANCELLED";

export interface OrderItem {
  id: string;
  productName: string;
  quantity: number;
  unitPrice: number;
  lineTotal: number;
  note: string;
  status: ItemStatus;
  optionSummary: string;
  cancelRequested: boolean;
  rejectReason: string;
}
export interface OrderBatch {
  id: string;
  batchNo: number;
  createdAt: string;
  items: OrderItem[];
}
export interface OrderView {
  status: string;
  batches: OrderBatch[];
  /** Tổng món tính tiền do backend tính (CONFIRMED..SERVED, chưa gồm phí phục vụ); null nếu không có. */
  billableTotal: number | null;
}

function toItem(raw: unknown): OrderItem {
  const o = isRec(raw) ? raw : {};
  const quantity = num(pick(o, "quantity", "qty"), 1);
  const unitPrice = num(pick(o, "unitPrice", "price"));
  const opts = arr(pick(o, "options", "selectedOptions")).map((x) =>
    isRec(x) ? str(pick(x, "name", "optionName")) : str(x),
  );
  return {
    id: str(pick(o, "id", "itemId", "orderItemId")),
    productName: str(pick(o, "productName", "name", "productNameSnapshot")),
    quantity,
    unitPrice,
    lineTotal: num(pick(o, "lineTotal", "totalPrice", "total"), unitPrice * quantity),
    note: str(pick(o, "note", "notes")),
    status: (str(o.status, "PENDING").toUpperCase() as ItemStatus),
    optionSummary: opts.filter(Boolean).join(", "),
    cancelRequested: bool(pick(o, "cancelRequested", "hasCancelRequest", "cancelRequestPending")),
    rejectReason: str(pick(o, "rejectReason", "rejectedReason", "reason")),
  };
}

export function toOrderView(raw: unknown): OrderView {
  const o = isRec(raw) ? raw : {};
  const batchesRaw = arr(pick(o, "batches", "orderBatches"));
  const batches: OrderBatch[] = batchesRaw.map((b, i) => {
    const bo = isRec(b) ? b : {};
    return {
      id: str(pick(bo, "id", "batchId")),
      batchNo: num(pick(bo, "batchNo", "batchNumber", "sequence"), i + 1),
      createdAt: str(pick(bo, "createdAt", "submittedAt")),
      items: arr(bo.items).map(toItem),
    };
  });
  return {
    status: str(pick(o, "status", "orderStatus"), "OPEN"),
    batches,
    billableTotal: optNum(o.billableTotal),
  };
}

/** Phản hồi khi tạo đợt: chỉ cần biết có phải replay hay không. */
export interface PlaceBatchResult {
  replayed: boolean;
}

/* ───────────── Service call & Bill ───────────── */

export type ServiceCallType = "CALL_STAFF" | "REQUEST_PAYMENT";
export interface ServiceCallView {
  id: string;
  type: ServiceCallType;
  status: string;
}

export function toServiceCall(raw: unknown): ServiceCallView {
  const o = isRec(raw) ? raw : {};
  return {
    id: str(pick(o, "id", "serviceCallId")),
    type: str(o.type) as ServiceCallType,
    status: str(o.status, "OPEN"),
  };
}

export interface BillLine {
  name: string;
  quantity: number;
  amount: number;
}
export interface BillView {
  lines: BillLine[];
  subtotal: number;
  discount: number;
  /** Phí dịch vụ (backend: serviceFeeAmount); 0 nếu không thu. */
  serviceFeeAmount: number;
  vatAmount: number;
  total: number;
  status: string;
}

export function toBill(raw: unknown): BillView {
  const o = isRec(raw) ? raw : {};
  const lines = arr(pick(o, "lines", "items")).map((l) => {
    const lo = isRec(l) ? l : {};
    return {
      name: str(pick(lo, "name", "productName", "description")),
      quantity: num(pick(lo, "quantity", "qty"), 1),
      amount: num(pick(lo, "lineTotal", "amount", "total")),
    };
  });
  return {
    lines,
    subtotal: num(pick(o, "subtotalAmount", "subtotal", "subTotal")),
    discount: num(pick(o, "discountAmount", "discount")),
    serviceFeeAmount: num(pick(o, "serviceFeeAmount", "serviceFee", "serviceCharge")),
    vatAmount: num(pick(o, "vatAmount", "tax", "taxAmount")),
    total: num(pick(o, "totalAmount", "total", "grandTotal")),
    status: str(pick(o, "status", "billStatus"), "DRAFT"),
  };
}