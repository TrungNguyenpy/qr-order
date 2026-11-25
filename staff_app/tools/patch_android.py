#!/usr/bin/env python3
"""Vá AndroidManifest.xml sau `flutter create --platforms=android,ios .`.

- Thêm quyền INTERNET (bản debug mặc định đã có, bản release thì chưa).
- Bật `usesCleartextTraffic` để gọi backend qua http:// trong mạng LAN/emulator.
  (Chỉ dùng khi phát triển; khi triển khai thật hãy dùng https và bỏ cờ này.)
- Đặt tên hiển thị của app.

Chạy từ thư mục staff_app:  python tools/patch_android.py
Chạy lại nhiều lần vẫn an toàn (idempotent).
"""
import pathlib
import re
import sys

MANIFEST = pathlib.Path("android/app/src/main/AndroidManifest.xml")
INTERNET = '<uses-permission android:name="android.permission.INTERNET"/>'


def main() -> int:
    if not MANIFEST.exists():
        print(f"Không thấy {MANIFEST}. Hãy chạy `flutter create --platforms=android,ios .` trước.")
        return 1

    text = MANIFEST.read_text(encoding="utf-8")
    original = text

    if "android.permission.INTERNET" not in text:
        text = re.sub(r"(<manifest[^>]*>)", r"\1\n    " + INTERNET, text, count=1)

    if "usesCleartextTraffic" not in text:
        text = text.replace("<application", '<application\n        android:usesCleartextTraffic="true"', 1)

    text = re.sub(r'android:label="[^"]*"', 'android:label="QR Order Nhân viên"', text, count=1)

    if text == original:
        print("AndroidManifest.xml đã được vá từ trước, không đổi gì.")
    else:
        MANIFEST.write_text(text, encoding="utf-8")
        print("Đã vá AndroidManifest.xml (INTERNET, cleartext, tên app).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
