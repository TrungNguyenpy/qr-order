import { uuid } from "./format";

const DEVICE_KEY = "qo.deviceId";

/** deviceId bền vững: 8–64 ký tự [A-Za-z0-9_-] (UUID hợp lệ). */
export function getDeviceId(): string {
  if (typeof window === "undefined") return "";
  try {
    let id = window.localStorage.getItem(DEVICE_KEY);
    if (!id || !/^[A-Za-z0-9_-]{8,64}$/.test(id)) {
      id = uuid();
      window.localStorage.setItem(DEVICE_KEY, id);
    }
    return id;
  } catch {
    // localStorage bị chặn (private mode): dùng id tạm trong bộ nhớ.
    return memoryDeviceId ?? (memoryDeviceId = uuid());
  }
}
let memoryDeviceId: string | null = null;

/** Giải mã `exp` (giây) của JWT mà không xác thực chữ ký (chỉ để lên lịch gia hạn). */
export function jwtExpiryMs(token: string): number | null {
  try {
    const payload = token.split(".")[1];
    if (!payload) return null;
    const json = JSON.parse(atob(payload.replace(/-/g, "+").replace(/_/g, "/")));
    return typeof json.exp === "number" ? json.exp * 1000 : null;
  } catch {
    return null;
  }
}
