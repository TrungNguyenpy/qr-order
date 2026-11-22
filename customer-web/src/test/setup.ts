import { afterEach } from "vitest";

// Mỗi test bắt đầu với localStorage sạch (store persist của Zustand ghi vào đây).
afterEach(() => {
  window.localStorage.clear();
});
