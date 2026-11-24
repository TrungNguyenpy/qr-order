import type { OrderItemDto } from "@/lib/api/types";
import { ITEM_STATUS_LABEL } from "./labels";

export interface TimelineEvent {
  key: string;
  label: string;
  at: string;
}

/**
 * Backend chưa có API lịch sử trạng thái món (order_item_history chỉ ghi nội bộ), nên dựng dòng thời gian
 * từ các mốc thời gian trên chính món. Trạng thái cuối (hủy/từ chối) lấy từ `closedAt`.
 */
export function buildItemTimeline(item: OrderItemDto): TimelineEvent[] {
  const events: TimelineEvent[] = [];
  const add = (key: string, label: string, at: string | null) => {
    if (at) events.push({ key, label, at });
  };
  add("created", "Khách/nhân viên gọi món", item.createdAt);
  add("confirmed", ITEM_STATUS_LABEL.CONFIRMED, item.confirmedAt);
  add("preparing", ITEM_STATUS_LABEL.PREPARING, item.preparingAt);
  add("ready", ITEM_STATUS_LABEL.READY, item.readyAt);
  add("served", ITEM_STATUS_LABEL.SERVED, item.servedAt);
  if (item.status === "CANCELLED" || item.status === "REJECTED") {
    add("closed", item.wasted ? `${ITEM_STATUS_LABEL[item.status]} (món đã làm, lãng phí)` : ITEM_STATUS_LABEL[item.status], item.closedAt);
  }
  return events.sort((a, b) => Date.parse(a.at) - Date.parse(b.at));
}
