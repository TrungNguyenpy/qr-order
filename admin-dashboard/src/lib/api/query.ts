/** Bỏ các tham số rỗng/undefined để không gửi `?q=&role=` lên backend. */
export function cleanParams<T extends object>(p: T): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(p)) {
    if (v === undefined || v === null || v === "") continue;
    out[k] = v;
  }
  return out;
}
