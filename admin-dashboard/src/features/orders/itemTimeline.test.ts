import { describe, expect, it } from "vitest";
import type { OrderItemDto } from "@/lib/api/types";
import { buildItemTimeline } from "./itemTimeline";

const base: OrderItemDto = {
  id: 1,
  batchId: 1,
  productId: 1,
  productName: "Phở bò",
  unitPrice: 50_000,
  optionExtraPerUnit: 0,
  quantity: 1,
  lineTotal: 50_000,
  note: null,
  status: "SERVED",
  reasonCode: null,
  wasted: false,
  options: [],
  cancelRequest: null,
  createdAt: "2026-11-23T03:00:00Z",
  confirmedAt: "2026-11-23T03:01:00Z",
  preparingAt: "2026-11-23T03:02:00Z",
  readyAt: "2026-11-23T03:10:00Z",
  servedAt: "2026-11-23T03:12:00Z",
  closedAt: "2026-11-23T03:12:00Z",
  version: 3,
};

describe("buildItemTimeline", () => {
  it("món đã phục vụ: các mốc theo thứ tự, không có mốc đóng", () => {
    expect(buildItemTimeline(base).map((e) => e.key)).toEqual(["created", "confirmed", "preparing", "ready", "served"]);
  });
  it("bỏ qua mốc rỗng và thêm mốc hủy khi món bị hủy", () => {
    const e = buildItemTimeline({ ...base, status: "CANCELLED", wasted: true, confirmedAt: null, preparingAt: null, readyAt: null, servedAt: null, closedAt: "2026-11-23T03:05:00Z" });
    expect(e.map((x) => x.key)).toEqual(["created", "closed"]);
    expect(e[1]?.label).toContain("lãng phí");
  });
  it("sắp xếp theo thời gian dù dữ liệu lệch thứ tự", () => {
    const e = buildItemTimeline({ ...base, status: "REJECTED", servedAt: null, readyAt: null, preparingAt: null, confirmedAt: null, closedAt: "2026-11-23T02:59:00Z" });
    expect(e.map((x) => x.key)).toEqual(["closed", "created"]);
  });
});
