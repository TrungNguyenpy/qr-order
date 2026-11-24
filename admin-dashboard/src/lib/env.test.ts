import { describe, expect, it } from "vitest";
import { toWebSocketUrl } from "./env";

describe("toWebSocketUrl", () => {
  it("giữ nguyên ws(s):// đầy đủ", () => {
    expect(toWebSocketUrl("ws://localhost:8081/ws/websocket")).toBe("ws://localhost:8081/ws/websocket");
  });
  it("đổi http(s)://host/ws sang ws(s)://host/ws/websocket", () => {
    expect(toWebSocketUrl("http://localhost:8081/ws")).toBe("ws://localhost:8081/ws/websocket");
    expect(toWebSocketUrl("https://api.example.com/ws/")).toBe("wss://api.example.com/ws/websocket");
  });
  it("rỗng thì rỗng (chỉ polling)", () => {
    expect(toWebSocketUrl("  ")).toBe("");
  });
});
