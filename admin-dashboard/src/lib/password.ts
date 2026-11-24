/** Chính sách mật khẩu (khớp `@StrongPassword` ở backend): >= 8 ký tự, có chữ hoa, chữ thường, chữ số; tối đa 72 byte (BCrypt). */
export function validatePassword(pw: string): string | null {
  if (pw.length < 8) return "Mật khẩu tối thiểu 8 ký tự.";
  if (!/[A-Z]/.test(pw)) return "Mật khẩu cần ít nhất 1 chữ hoa.";
  if (!/[a-z]/.test(pw)) return "Mật khẩu cần ít nhất 1 chữ thường.";
  if (!/[0-9]/.test(pw)) return "Mật khẩu cần ít nhất 1 chữ số.";
  if (new TextEncoder().encode(pw).length > 72) return "Mật khẩu tối đa 72 byte.";
  return null;
}
