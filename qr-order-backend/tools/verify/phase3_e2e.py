#!/usr/bin/env python3
"""
Kiểm chứng đầu-cuối Giai đoạn 3 (Bàn, QR, Phiên bàn) trên backend ĐANG CHẠY + PostgreSQL thật.

Không thay thế integration test (Testcontainers) - dùng để kiểm chứng hành vi Spring thực tế khi môi trường không
build được Gradle. Yêu cầu: backend chạy profile dev (seed V2), ADMIN đầu tiên đã tạo, Scheduler chu kỳ ngắn
(SESSION_EXPIRY_CHECK_INTERVAL_MS=3000), QR_BASE_URL + QR_TOKEN_SECRET đã đặt.

    python3 phase3_e2e.py --base http://localhost:18080 --dsn "dbname=qr_app user=qr_order password=... host=localhost" \
        --admin admin --admin-password Admin12345
"""
import argparse, concurrent.futures, io, json, sys, time, uuid
import psycopg2, requests

ap = argparse.ArgumentParser()
ap.add_argument("--base", default="http://localhost:18080")
ap.add_argument("--dsn", required=True)
ap.add_argument("--admin", default="admin")
ap.add_argument("--admin-password", default="Admin12345")
args = ap.parse_args()
B = args.base
db = psycopg2.connect(args.dsn); db.autocommit = True
PASS = FAIL = 0
ip_counter = [0]

def q(sql, *p):
    with db.cursor() as c:
        c.execute(sql, p)
        return c.fetchall() if c.description else None

def one(sql, *p):
    r = q(sql, *p); return r[0][0] if r else None

def check(name, cond, extra=""):
    global PASS, FAIL
    if cond: PASS += 1; print(f"  PASS {name}")
    else: FAIL += 1; print(f"  FAIL {name} {extra}")

def new_ip():
    ip_counter[0] += 1
    return f"10.9.{ip_counter[0] // 250}.{ip_counter[0] % 250 + 1}"

def H(token=None, ip=None):
    h = {}
    if token: h["Authorization"] = "Bearer " + token
    if ip: h["X-Forwarded-For"] = ip
    return h

def err(r): 
    try: return r.json().get("error", {}).get("code")
    except Exception: return None

def login(u, p):
    r = requests.post(B + "/api/v1/auth/login", json={"username": u, "password": p}); r.raise_for_status()
    return r.json()["data"]["accessToken"]

def make_user(username, role):
    admin_h = H(ADMIN)
    r = requests.post(B + "/api/v1/users", headers=admin_h, json={"username": username, "password": "Matkhau123",
        "fullName": "NV " + username, "role": role})
    assert r.status_code == 201, r.text
    return login(username, "Matkhau123"), r.json()["data"]["id"]

def join(token, device=None, ip=None, pin=None):
    body = {"qrToken": token, "deviceId": device or ("dev-" + uuid.uuid4().hex[:16])}
    if pin is not None: body["pin"] = pin
    return requests.post(B + "/api/v1/public/sessions/join", json=body, headers=H(ip=ip or new_ip()))

def table_id(code): return one("SELECT id FROM dining_table WHERE code = %s", code)
def tver(tid): return one("SELECT version FROM dining_table WHERE id = %s", tid)
def token_of(url): return url.rsplit("/", 1)[1]

# ---------------------------------------------------------------------------------------------- chuẩn bị
suffix = uuid.uuid4().hex[:6]
ADMIN = login(args.admin, args.admin_password)
MANAGER, _ = make_user("ql_" + suffix, "MANAGER")
STAFF, staff_id = make_user("nv_" + suffix, "STAFF")
KITCHEN, _ = make_user("bep_" + suffix, "KITCHEN")

print("[1] Bàn: CRUD, mã duy nhất, version, phân quyền")
code = "E" + suffix[:4].upper()
r = requests.post(B + "/api/v1/tables", headers=H(ADMIN), json={"code": code, "displayName": "Bàn thử " + suffix, "area": "Khu E2E", "capacity": 4, "displayOrder": 50})
check("ADMIN tạo bàn 201", r.status_code == 201, r.text)
t = r.json()["data"]; tid = t["id"]
check("bàn mới AVAILABLE, active, version 0", t["status"] == "AVAILABLE" and t["active"] and t["version"] == 0)
r = requests.post(B + "/api/v1/tables", headers=H(ADMIN), json={"code": code.lower(), "displayName": "x", "capacity": 2})
check("trùng mã không phân biệt hoa thường -> 409 TABLE_CODE_TAKEN", r.status_code == 409 and err(r) == "TABLE_CODE_TAKEN", r.text)
r = requests.post(B + "/api/v1/tables", headers=H(ADMIN), json={"code": "bad code!", "displayName": " ", "capacity": 0})
check("validation -> 400 VALIDATION_FAILED (3 trường)", r.status_code == 400 and len(r.json()["error"]["details"]) == 3, r.text)
for tok, name in ((MANAGER, "MANAGER"), (STAFF, "STAFF"), (KITCHEN, "KITCHEN")):
    r = requests.post(B + "/api/v1/tables", headers=H(tok), json={"code": "Z" + suffix[:3], "displayName": "x", "capacity": 2})
    check(f"{name} không tạo được bàn -> 403", r.status_code == 403, r.text)
r = requests.get(B + "/api/v1/tables?area=khu e2e&q=" + code.lower(), headers=H(STAFF))
check("STAFF xem danh sách, lọc area + q", r.status_code == 200 and r.json()["data"]["totalElements"] == 1, r.text)
r = requests.get(B + "/api/v1/tables", headers=H(KITCHEN))
check("KITCHEN không xem bàn -> 403", r.status_code == 403)
r = requests.put(B + f"/api/v1/tables/{tid}", headers=H(ADMIN), json={"code": code, "displayName": "Bàn thử sửa", "area": "Khu E2E", "capacity": 6, "displayOrder": 51, "version": 0})
check("sửa đúng version -> 200, version 1", r.status_code == 200 and r.json()["data"]["version"] == 1, r.text)
audit = q("SELECT old_value::text, new_value::text FROM audit_log WHERE action='TABLE_UPDATED' AND entity_id=%s", tid)
check("audit TABLE_UPDATED chỉ trường đổi", audit and "capacity" in audit[0][1] and "code" not in audit[0][1], audit)
r = requests.put(B + f"/api/v1/tables/{tid}", headers=H(ADMIN), json={"code": code, "displayName": "y", "capacity": 6, "displayOrder": 51, "version": 0})
check("version cũ -> 409 CONCURRENT_MODIFICATION", r.status_code == 409 and err(r) == "CONCURRENT_MODIFICATION")
r = requests.get(B + "/api/v1/tables/999999", headers=H(ADMIN))
check("bàn không tồn tại -> 404", r.status_code == 404)
r = requests.delete(B + f"/api/v1/tables/{tid}", headers=H(ADMIN))
check("không có xóa bàn -> 405", r.status_code == 405, r.status_code)

print("[2] QR: sinh, xoay, thu hồi, PNG, PDF")
r = requests.post(B + f"/api/v1/tables/{tid}/qr-codes", headers=H(ADMIN))
check("sinh QR 201 kèm URL", r.status_code == 201 and r.json()["data"]["url"].startswith("http://localhost:3000/t/"), r.text)
qr1 = r.json()["data"]; tok1 = token_of(qr1["url"])
check("token >= 128 bit (43 ký tự base64url)", len(tok1) == 43)
check("DB chỉ lưu SHA-256 hex", one("SELECT token_hash FROM qr_code WHERE id=%s", qr1["id"]) == __import__("hashlib").sha256(tok1.encode()).hexdigest())
check("DB không chứa token gốc", one("SELECT count(*) FROM qr_code WHERE token_hash = %s", tok1) == 0)
r = requests.post(B + f"/api/v1/tables/{tid}/qr-codes", headers=H(ADMIN))
check("sinh lần 2 -> 409 QR_ALREADY_ACTIVE", r.status_code == 409 and err(r) == "QR_ALREADY_ACTIVE", r.text)
r = requests.get(B + f"/api/v1/tables/{tid}/qr-code", headers=H(ADMIN))
check("GET qr-code trả cùng URL (dựng lại được)", r.json()["data"]["url"] == qr1["url"] and r.json()["data"]["reproducible"] is True)
r = requests.get(B + f"/api/v1/tables/{tid}/qr-code", headers=H(MANAGER))
check("MANAGER không xem QR -> 403", r.status_code == 403)
r = requests.get(B + f"/api/v1/tables/{tid}/qr-code/image.png?size=300", headers=H(ADMIN))
from PIL import Image as PILImage
png_ok = r.status_code == 200 and r.headers.get("Content-Type") == "image/png" and r.content[:8] == b"\x89PNG\r\n\x1a\n"
check("PNG 300x300", png_ok and PILImage.open(io.BytesIO(r.content)).size == (300, 300), (r.status_code, r.headers.get("Content-Type"), r.text[:200]))
check("PNG no-store", "no-store" in r.headers.get("Cache-Control", ""))
r = requests.get(B + f"/api/v1/tables/{tid}/qr-code/image.png?size=5000", headers=H(ADMIN))
check("size ngoài giới hạn -> 400", r.status_code == 400 and err(r) == "VALIDATION_FAILED", r.text[:200])
r = requests.get(B + f"/api/v1/tables/{tid}/qr-code/print.pdf", headers=H(ADMIN))
from pypdf import PdfReader
ok = r.status_code == 200 and r.content[:5] == b"%PDF-"
check("PDF 1 bàn hợp lệ", ok, (r.status_code, r.text[:200]))
if ok:
    pdf = PdfReader(io.BytesIO(r.content)); text = pdf.pages[0].extract_text()
    check("PDF 1 trang, có tên bàn tiếng Việt", len(pdf.pages) == 1 and "Bàn thử sửa" in text, text)
check("audit QR_EXPORTED", one("SELECT count(*) FROM audit_log WHERE action='QR_EXPORTED'") >= 2)
t02 = table_id("T02")
r = requests.get(B + f"/api/v1/tables/{t02}/qr-code", headers=H(ADMIN))
check("QR seed dev: reproducible=false, không có url", r.json()["data"]["reproducible"] is False and r.json()["data"]["url"] is None, r.text)
r = requests.get(B + f"/api/v1/tables/{t02}/qr-code/image.png", headers=H(ADMIN))
check("PNG QR seed -> 409 QR_TOKEN_NOT_REPRODUCIBLE", r.status_code == 409 and err(r) == "QR_TOKEN_NOT_REPRODUCIBLE", r.text[:200])

# join bằng QR mới
r = join(tok1, device="device-e2e-0001")
check("join bằng QR mới -> phiên mới", r.status_code == 200 and r.json()["data"]["newSession"], r.text)
g1 = r.json()["data"]; s1 = g1["session"]["sessionId"]; guest1 = g1["accessToken"]
check("bàn -> OCCUPIED", one("SELECT status FROM dining_table WHERE id=%s", tid) == "OCCUPIED")
r = requests.post(B + f"/api/v1/tables/{tid}/qr-code/rotate", headers=H(ADMIN), json={"reason": "tem bị chụp lại"})
check("xoay QR -> 200 mã mới", r.status_code == 200 and r.json()["data"]["id"] != qr1["id"], r.text)
tok2 = token_of(r.json()["data"]["url"])
check("QR cũ ROTATED, deactivated_at có", q("SELECT status, deactivated_at IS NOT NULL, deactivation_reason FROM qr_code WHERE id=%s", qr1["id"])[0] == ("ROTATED", True, "tem bị chụp lại"))
check("audit QR_ROTATED", one("SELECT count(*) FROM audit_log WHERE action='QR_ROTATED' AND entity_id=%s", qr1["id"]) == 1)
r = join(tok1)
check("join bằng QR đã xoay -> 404 QR_CODE_INVALID", r.status_code == 404 and err(r) == "QR_CODE_INVALID", r.text)
r = requests.get(B + "/api/v1/guest/session", headers=H(guest1))
check("phiên đang chạy không bị ảnh hưởng khi xoay QR (BR-S05)", r.status_code == 200 and r.json()["data"]["sessionId"] == s1, r.text)
r = join(tok2, device="device-e2e-0002")
check("join QR mới vào phiên đang có (2 thiết bị)", r.status_code == 200 and not r.json()["data"]["newSession"] and r.json()["data"]["session"]["sessionId"] == s1, r.text)
check("2 session_device", one("SELECT count(*) FROM session_device WHERE session_id=%s", s1) == 2)
r = requests.post(B + f"/api/v1/tables/{tid}/qr-code/revoke", headers=H(ADMIN), json={})
check("thu hồi thiếu lý do -> 400", r.status_code == 400)
r = requests.post(B + f"/api/v1/tables/{tid}/qr-code/revoke", headers=H(ADMIN), json={"reason": "mất tem"})
check("thu hồi -> REVOKED", r.status_code == 200 and r.json()["data"]["status"] == "REVOKED", r.text)
r = join(tok2)
check("join QR đã thu hồi -> 404", r.status_code == 404 and err(r) == "QR_CODE_INVALID")
r = requests.post(B + f"/api/v1/tables/{tid}/qr-code/rotate", headers=H(ADMIN), json={})
check("xoay khi không có QR ACTIVE -> 409 QR_CODE_NOT_ACTIVE", r.status_code == 409 and err(r) == "QR_CODE_NOT_ACTIVE")
r = requests.get(B + f"/api/v1/tables/{tid}/qr-codes", headers=H(ADMIN))
check("lịch sử QR 2 bản ghi", r.status_code == 200 and [x["status"] for x in r.json()["data"]] == ["REVOKED", "ROTATED"], r.text)

print("[3] Ngừng dùng bàn (BR-S08)")
r = requests.patch(B + f"/api/v1/tables/{tid}/active", headers=H(ADMIN), json={"active": False, "version": tver(tid)})
check("ngừng dùng bàn đang có khách -> 409 TABLE_IN_USE", r.status_code == 409 and err(r) == "TABLE_IN_USE", r.text)

print("[4] Force-close, BE-05, xác nhận dọn bàn")
r = requests.get(B + "/api/v1/guest/session", headers=H(guest1))
check("Guest JWT dùng được khi phiên OPEN", r.status_code == 200)
r = requests.post(B + "/api/v1/guest/session/token", headers=H(guest1))
check("gia hạn Guest JWT", r.status_code == 200 and r.json()["data"]["accessToken"] != guest1, r.text)
guest1b = r.json()["data"]["accessToken"]
r = requests.post(B + f"/api/v1/sessions/{s1}/force-close", headers=H(STAFF), json={"reason": "khách bỏ đi"})
check("STAFF không có SESSION_FORCE_CLOSE -> 403", r.status_code == 403)
r = requests.post(B + f"/api/v1/sessions/{s1}/force-close", headers=H(MANAGER), json={"reason": " "})
check("thiếu lý do -> 400", r.status_code == 400)
r = requests.post(B + f"/api/v1/sessions/{s1}/force-close", headers=H(MANAGER), json={"reason": "khách bỏ đi", "version": 999})
check("version sai -> 409", r.status_code == 409 and err(r) == "CONCURRENT_MODIFICATION")
r = requests.post(B + f"/api/v1/sessions/{s1}/force-close", headers=H(MANAGER), json={"reason": "khách bỏ đi"})
check("MANAGER force-close -> FORCE_CLOSED", r.status_code == 200 and r.json()["data"]["status"] == "FORCE_CLOSED" and r.json()["data"]["closeReason"] == "khách bỏ đi", r.text)
check("bàn -> CLEANING", one("SELECT status FROM dining_table WHERE id=%s", tid) == "CLEANING")
check("audit SESSION_FORCE_CLOSED có lý do + người", q("SELECT reason, actor_type FROM audit_log WHERE action='SESSION_FORCE_CLOSED' AND entity_id=%s", s1)[0] == ("khách bỏ đi", "USER"))
for tk, nm in ((guest1, "token cũ"), (guest1b, "token đã gia hạn")):
    r = requests.get(B + "/api/v1/guest/session", headers=H(tk))
    check(f"BE-05: {nm} bị từ chối ngay -> 401 SESSION_CLOSED", r.status_code == 401 and err(r) == "SESSION_CLOSED", r.text)
r = requests.post(B + f"/api/v1/sessions/{s1}/force-close", headers=H(MANAGER), json={"reason": "lần 2"})
check("force-close lần 2 -> 409 SESSION_FINAL_STATE", r.status_code == 409 and err(r) == "SESSION_FINAL_STATE")
r = requests.post(B + f"/api/v1/tables/{tid}/qr-codes", headers=H(ADMIN)); tok3 = token_of(r.json()["data"]["url"])
r = join(tok3)
check("bàn CLEANING không nhận phiên mới -> 409 TABLE_NOT_READY", r.status_code == 409 and err(r) == "TABLE_NOT_READY", r.text)
r = requests.patch(B + f"/api/v1/tables/{tid}/status", headers=H(STAFF), json={"status": "OCCUPIED", "version": tver(tid)})
check("đổi tay sang OCCUPIED -> 409 INVALID_TABLE_TRANSITION", r.status_code == 409 and err(r) == "INVALID_TABLE_TRANSITION")
r = requests.patch(B + f"/api/v1/tables/{tid}/status", headers=H(ADMIN), json={"status": "AVAILABLE", "version": tver(tid)})
check("ADMIN không xác nhận dọn bàn -> 403 (BR-A06)", r.status_code == 403)
r = requests.patch(B + f"/api/v1/tables/{tid}/status", headers=H(STAFF), json={"status": "AVAILABLE", "version": tver(tid)})
check("STAFF xác nhận dọn xong -> AVAILABLE", r.status_code == 200 and r.json()["data"]["status"] == "AVAILABLE", r.text)
r = requests.patch(B + f"/api/v1/tables/{tid}/active", headers=H(ADMIN), json={"active": False, "version": tver(tid)})
check("ngừng dùng bàn trống -> active=false", r.status_code == 200 and r.json()["data"]["active"] is False, r.text)
r = join(tok3)
check("join bàn ngừng dùng -> 409 TABLE_INACTIVE", r.status_code == 409 and err(r) == "TABLE_INACTIVE", r.text)
r = requests.patch(B + f"/api/v1/tables/{tid}/active", headers=H(ADMIN), json={"active": True, "version": tver(tid)})
check("dùng lại bàn", r.status_code == 200 and r.json()["data"]["active"] is True)

print("[5] Join đồng thời trên cùng bàn (BR-S01)")
t03 = table_id("T03")
def j(i): return join("dev-qr-T03", device=f"device-conc-{i:04d}", ip=new_ip())
with concurrent.futures.ThreadPoolExecutor(12) as ex:
    res = list(ex.map(j, range(12)))
codes = [x.status_code for x in res]
sids = {x.json()["data"]["session"]["sessionId"] for x in res if x.status_code == 200}
check("12 join đồng thời đều 200", codes.count(200) == 12, codes)
check("chỉ 1 phiên được tạo", len(sids) == 1 and one("SELECT count(*) FROM table_session WHERE table_id=%s", t03) == 1, sids)
check("đúng 1 response newSession=true", sum(1 for x in res if x.status_code == 200 and x.json()["data"]["newSession"]) == 1)
check("12 thiết bị trong phiên", one("SELECT count(*) FROM session_device WHERE session_id=%s", list(sids)[0]) == 12)
s3 = list(sids)[0]

print("[6] Rate limit join 10/phút/IP (BR-S09)")
ip = new_ip()
codes = [join("dev-qr-T03", ip=ip).status_code for _ in range(10)]
r = join("dev-qr-T03", ip=ip)
check("10 lần đầu 200, lần 11 -> 429 RATE_LIMIT_EXCEEDED", codes == [200] * 10 and r.status_code == 429 and err(r) == "RATE_LIMIT_EXCEEDED", (codes, r.status_code, r.text))
check("có header Retry-After", r.headers.get("Retry-After", "0").isdigit() and int(r.headers["Retry-After"]) >= 1, r.headers)
r = join("khong-ton-tai-xyz", ip=ip)
check("yêu cầu sai cũng bị đếm -> 429", r.status_code == 429)
check("IP khác không bị ảnh hưởng", join("dev-qr-T03", ip=new_ip()).status_code == 200)

print("[7] PIN bàn (DEC-07 A)")
st = requests.get(B + "/api/v1/settings", headers=H(ADMIN)).json()["data"]
body = {k: st[k] for k in ("vatRate", "serviceFeeRate", "serviceFeeTaxable", "sessionIdleTimeoutMinutes", "guestJwtTtlMinutes",
        "batchClaimTtlSeconds", "maxPendingBatchesPerSession", "joinRateLimitPerMinute", "orderRateLimitPerMinute",
        "maxItemQuantity", "orderConfirmationMode", "requireTablePin", "cleaningAutoRelease", "version")}
body["requireTablePin"] = True
r = requests.put(B + "/api/v1/settings", headers=H(ADMIN), json=body)
check("bật require_table_pin", r.status_code == 200, r.text)
r = join("dev-qr-T04", device="device-pin-0001")
d = r.json()["data"]; s4 = d["session"]["sessionId"]
check("phiên mới: pinRequired, chưa xác minh", d["session"]["pinRequired"] and not d["session"]["pinVerified"], d)
pin = requests.get(B + f"/api/v1/sessions/{s4}", headers=H(STAFF)).json()["data"]["tablePin"]
check("nhân viên đọc được PIN 4 số", pin is not None and len(pin) == 4 and pin.isdigit(), pin)
wrong = "0000" if pin != "0000" else "1111"
r = join("dev-qr-T04", device="device-pin-0002", pin=wrong)
check("join kèm PIN sai -> 422 TABLE_PIN_INVALID", r.status_code == 422 and err(r) == "TABLE_PIN_INVALID", r.text)
check("PIN sai không gắn thiết bị", one("SELECT count(*) FROM session_device WHERE session_id=%s AND device_id='device-pin-0002'", s4) == 0)
r = join("dev-qr-T04", device="device-pin-0002", pin=pin)
check("join kèm PIN đúng -> pinVerified", r.status_code == 200 and r.json()["data"]["session"]["pinVerified"], r.text)
g4 = d["accessToken"]
r = requests.post(B + "/api/v1/guest/session/verify-pin", headers=H(g4), json={"pin": wrong})
check("verify-pin sai -> 422", r.status_code == 422 and err(r) == "TABLE_PIN_INVALID")
r = requests.post(B + "/api/v1/guest/session/verify-pin", headers=H(g4), json={"pin": pin})
check("verify-pin đúng -> pinVerified", r.status_code == 200 and r.json()["data"]["pinVerified"], r.text)
check("pin_verified_at được ghi", one("SELECT pin_verified_at IS NOT NULL FROM session_device WHERE session_id=%s AND device_id='device-pin-0001'", s4))
r = requests.get(B + "/api/v1/guest/session", headers=H(g4))
check("phản hồi khách không chứa PIN", "tablePin" not in r.text and pin not in json.dumps(r.json()["data"]), r.text)
body["requireTablePin"] = False; body["version"] = body["version"] + 1
requests.put(B + "/api/v1/settings", headers=H(ADMIN), json=body).raise_for_status()

print("[8] Nhân viên mở phiên, force-close bị chặn khi còn order")
t05 = table_id("T05")
r = requests.post(B + f"/api/v1/tables/{t05}/sessions", headers=H(STAFF))
check("STAFF mở phiên hộ khách -> 201, STAFF", r.status_code == 201 and r.json()["data"]["openedByType"] == "STAFF", r.text)
s5 = r.json()["data"]["id"]
r = requests.post(B + f"/api/v1/tables/{t05}/sessions", headers=H(STAFF))
check("mở lần 2 -> 409 SESSION_ALREADY_ACTIVE", r.status_code == 409 and err(r) == "SESSION_ALREADY_ACTIVE", r.text)
order5 = one("INSERT INTO orders (session_id) VALUES (%s) RETURNING id", s5)
# Chèn đợt + món bằng cùng mẫu với DbFixtures (thiết bị khách).
batch5 = one("""INSERT INTO order_batch (order_id, session_id, seq, client_request_id, status, requires_confirmation, submitted_by_type, device_id)
               VALUES (%s, %s, 1, %s, 'ACCEPTED', false, 'GUEST', 'device-0001') RETURNING id""", order5, s5, str(uuid.uuid4()))
prod = q("SELECT id, name, price FROM product WHERE status='AVAILABLE' ORDER BY id LIMIT 1")[0]
item5 = one("""INSERT INTO order_item (order_id, batch_id, product_id, product_name, unit_price, quantity, line_total, status, device_id, confirmed_at)
               VALUES (%s, %s, %s, %s, %s, 1, %s, 'CONFIRMED', 'device-0001', now()) RETURNING id""", order5, batch5, prod[0], prod[1], prod[2], prod[2])
r = requests.post(B + f"/api/v1/sessions/{s5}/force-close", headers=H(MANAGER), json={"reason": "thử"})
check("còn món CONFIRMED -> 409 SESSION_HAS_UNSETTLED_ORDER (GĐ3)", r.status_code == 409 and err(r) == "SESSION_HAS_UNSETTLED_ORDER", r.text)
check("phiên vẫn OPEN, bàn vẫn OCCUPIED", q("SELECT s.status, t.status FROM table_session s JOIN dining_table t ON t.id=s.table_id WHERE s.id=%s", s5)[0] == ("OPEN", "OCCUPIED"))
try:
    q("UPDATE order_item SET status='CANCELLED', status_changed_by_type='SYSTEM', reason_code='CUSTOMER_REQUEST', closed_at=now() WHERE id=%s", item5)
    cancelled = True
except Exception as ex:
    cancelled = False; print("    (không hủy được món bằng SQL:", ex, ")")
if cancelled:
    r = requests.post(B + f"/api/v1/sessions/{s5}/force-close", headers=H(MANAGER), json={"reason": "khách hủy hết"})
    check("mọi món đã hủy -> force-close được, Order VOIDED", r.status_code == 200 and one("SELECT status FROM orders WHERE id=%s", order5) == "VOIDED", r.text)

print("[9] Scheduler: EXPIRED vs needs_attention (BR-S07)")
t06, t07 = table_id("T06"), table_id("T07")
r6 = join("dev-qr-T06"); s6 = r6.json()["data"]["session"]["sessionId"]; g6 = r6.json()["data"]["accessToken"]
r7 = join("dev-qr-T07"); s7 = r7.json()["data"]["session"]["sessionId"]
order7 = one("INSERT INTO orders (session_id) VALUES (%s) RETURNING id", s7)
batch7 = one("""INSERT INTO order_batch (order_id, session_id, seq, client_request_id, status, requires_confirmation, submitted_by_type, device_id)
               VALUES (%s, %s, 1, %s, 'ACCEPTED', false, 'GUEST', 'device-0001') RETURNING id""", order7, s7, str(uuid.uuid4()))
one("""INSERT INTO order_item (order_id, batch_id, product_id, product_name, unit_price, quantity, line_total, status, device_id, confirmed_at)
       VALUES (%s, %s, %s, %s, %s, 1, %s, 'CONFIRMED', 'device-0001', now()) RETURNING id""", order7, batch7, prod[0], prod[1], prod[2], prod[2])
q("UPDATE table_session SET last_activity_at = now() - interval '181 minutes' WHERE id IN (%s, %s)", s6, s7)
deadline = time.time() + 20
while time.time() < deadline and one("SELECT status FROM table_session WHERE id=%s", s6) == "OPEN": time.sleep(0.5)
time.sleep(1)
check("phiên idle không còn đơn -> EXPIRED", one("SELECT status FROM table_session WHERE id=%s", s6) == "EXPIRED")
check("bàn T06 -> AVAILABLE", one("SELECT status FROM dining_table WHERE id=%s", t06) == "AVAILABLE")
check("audit SESSION_EXPIRED actor SYSTEM", one("SELECT actor_type FROM audit_log WHERE action='SESSION_EXPIRED' AND entity_id=%s", s6) == "SYSTEM")
r = requests.get(B + "/api/v1/guest/session", headers=H(g6))
check("Guest JWT phiên EXPIRED -> 401 SESSION_CLOSED", r.status_code == 401 and err(r) == "SESSION_CLOSED")
row7 = q("SELECT status, needs_attention, attention_reason FROM table_session WHERE id=%s", s7)[0]
check("phiên idle còn đơn chưa thanh toán -> giữ OPEN + needs_attention", row7 == ("OPEN", True, "IDLE_WITH_UNPAID_ORDER"), row7)
check("bàn T07 vẫn OCCUPIED", one("SELECT status FROM dining_table WHERE id=%s", t07) == "OCCUPIED")
n_att = one("SELECT count(*) FROM audit_log WHERE action='SESSION_NEEDS_ATTENTION' AND entity_id=%s", s7)
time.sleep(4)
check("không báo lặp ở lượt quét sau", one("SELECT count(*) FROM audit_log WHERE action='SESSION_NEEDS_ATTENTION' AND entity_id=%s", s7) == n_att == 1)
r = requests.get(B + "/api/v1/sessions/needs-attention", headers=H(STAFF))
check("danh sách cần xử lý có phiên T07", r.status_code == 200 and any(x["id"] == s7 for x in r.json()["data"]), r.text)
r = requests.get(B + f"/api/v1/sessions?status=EXPIRED&tableId={t06}", headers=H(MANAGER))
check("lọc phiên EXPIRED theo bàn", r.status_code == 200 and r.json()["data"]["totalElements"] == 1, r.text)
r = requests.get(B + "/api/v1/sessions", headers=H(ADMIN))
check("ADMIN không có REPORT_VIEW không xem phiên -> 403", r.status_code == 403)

print("[10] Sơ đồ bàn, PDF hàng loạt")
r = requests.get(B + "/api/v1/tables/floor-plan", headers=H(STAFF))
fp = {x["table"]["code"]: x for x in r.json()["data"]}
check("sơ đồ bàn có phiên T03 (12+ thiết bị)", fp["T03"]["activeSession"]["id"] == s3 and fp["T03"]["activeSession"]["deviceCount"] >= 12, fp.get("T03"))
check("sơ đồ không có bàn ngừng dùng T99", "T99" not in fp)
r = requests.post(B + f"/api/v1/tables/{t02}/qr-code/rotate", headers=H(ADMIN), json={}); assert r.status_code == 200
r = requests.get(B + f"/api/v1/qr-codes/print.pdf?tableIds={tid}&tableIds={t02}", headers=H(ADMIN))
ok = r.status_code == 200 and r.content[:5] == b"%PDF-"
check("PDF hàng loạt 2 bàn", ok and len(PdfReader(io.BytesIO(r.content)).pages) == 2, (r.status_code, r.text[:200]))
check("attachment filename", "attachment" in r.headers.get("Content-Disposition", ""), r.headers.get("Content-Disposition"))
r = requests.get(B + "/api/v1/qr-codes/print.pdf", headers=H(ADMIN))
check("PDF mọi bàn có QR dựng lại được -> 409 vì còn QR seed", r.status_code == 409 and err(r) == "QR_TOKEN_NOT_REPRODUCIBLE", (r.status_code, r.text[:200]))

print(f"\nKẾT QUẢ: {PASS} PASS, {FAIL} FAIL")
sys.exit(1 if FAIL else 0)
