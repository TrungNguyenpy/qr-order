import { call } from "./client";
import {
  toBill,
  toJoinResult,
  toMenu,
  toOrderView,
  toServiceCall,
  toTokenResult,
  type BillView,
  type JoinResult,
  type Menu,
  type OrderView,
  type PlaceBatchResult,
  type ServiceCallType,
  type ServiceCallView,
} from "./types";

export interface PlaceItemInput {
  productId: string;
  quantity: number;
  note?: string;
  /** Khớp hợp đồng backend: POST /guest/orders -> items[].optionItemIds */
  optionItemIds: string[];
}

export const api = {
  /** POST /public/sessions/join */
  async join(qrToken: string, deviceId: string): Promise<JoinResult> {
    const { data } = await call({ method: "POST", url: "/public/sessions/join", data: { qrToken, deviceId }, skipAuth: true });
    return toJoinResult(data);
  },

  /** POST /guest/session/verify-pin */
  async verifyPin(pin: string): Promise<{ token: string | null }> {
    const { data } = await call({ method: "POST", url: "/guest/session/verify-pin", data: { pin } });
    return toTokenResult(data);
  },

  /** GET /public/menu */
  async menu(): Promise<Menu> {
    const { data } = await call({ method: "GET", url: "/public/menu", skipAuth: true });
    return toMenu(data);
  },

  /**
   * Đặt một đợt món. README backend: POST /guest/orders (khác với /orders/batches trong yêu cầu).
   * Theo README là hợp đồng chuẩn; hằng số dưới đây dễ đổi nếu Swagger khác.
   */
  async placeBatch(clientRequestId: string, items: PlaceItemInput[]): Promise<PlaceBatchResult> {
    const { data, status } = await call<{ replayed?: boolean }>({
      method: "POST",
      url: "/guest/orders",
      data: { clientRequestId, items },
    });
    return { replayed: status === 200 || Boolean(data?.replayed) };
  },

  /** GET /guest/orders: đơn hiện tại của phiên (theo đợt). */
  async orders(): Promise<OrderView> {
    const { data } = await call({ method: "GET", url: "/guest/orders" });
    return toOrderView(data);
  },

  /** Hủy trực tiếp món PENDING/CONFIRMED. */
  async cancelItem(itemId: string): Promise<void> {
    await call({ method: "POST", url: `/guest/orders/items/${encodeURIComponent(itemId)}/cancel` });
  },

  /** Yêu cầu hủy món đang nấu (PREPARING/READY) — Manager duyệt. README: `cancel-requests` (số nhiều). */
  async requestCancel(itemId: string, reason?: string): Promise<void> {
    await call({
      method: "POST",
      url: `/guest/orders/items/${encodeURIComponent(itemId)}/cancel-requests`,
      data: reason ? { reason } : {},
    });
  },

  /** POST /guest/service-calls */
  async serviceCall(type: ServiceCallType): Promise<ServiceCallView> {
    const { data } = await call({ method: "POST", url: "/guest/service-calls", data: { type } });
    return toServiceCall(data);
  },

  /** GET /guest/bill — tạm tính; 404 nếu chưa có bill. */
  async bill(): Promise<BillView> {
    const { data } = await call({ method: "GET", url: "/guest/bill" });
    return toBill(data);
  },
};
