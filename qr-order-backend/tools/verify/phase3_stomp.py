#!/usr/bin/env python3
"""
Kiểm chứng STOMP Giai đoạn 3 trên backend ĐANG CHẠY: /topic/tables, /topic/session/{id}, BE-05 khi CONNECT.
    python3 phase3_stomp.py --base http://localhost:18080 --origin http://localhost:3000 --manager <user> --password <pw>
"""
import argparse, json, threading, time, uuid
import requests, websocket

ap = argparse.ArgumentParser()
ap.add_argument("--base", default="http://localhost:18080")
ap.add_argument("--origin", default="http://localhost:3000")
ap.add_argument("--manager", required=True)
ap.add_argument("--password", required=True)
ap.add_argument("--table-qr", default="dev-qr-T08")
ap.add_argument("--attention-qr", default="dev-qr-T09")
ap.add_argument("--dsn", default=None)
a = ap.parse_args()
WS = a.base.replace("http", "ws", 1) + "/ws/websocket"
PASS = FAIL = 0

def check(name, cond, extra=""):
    global PASS, FAIL
    if cond: PASS += 1; print("  PASS", name)
    else: FAIL += 1; print("  FAIL", name, extra)

def frame(cmd, headers, body=""):
    return cmd + "\n" + "".join(f"{k}:{v}\n" for k, v in headers.items()) + "\n" + body + "\x00"

class Client:
    def __init__(self, token):
        self.ws = websocket.create_connection(WS, header=[f"Origin: {a.origin}"], timeout=5)
        self.frames = []; self.alive = True
        self.ws.send(frame("CONNECT", {"accept-version": "1.2", "heart-beat": "0,0", "Authorization": "Bearer " + token}))
        first = self.ws.recv()
        self.connected = first.startswith("CONNECTED")
        self.first = first
        if self.connected:
            threading.Thread(target=self._loop, daemon=True).start()
    def _loop(self):
        while self.alive:
            try:
                data = self.ws.recv()
                if data and data.strip("\n\x00"): self.frames.append(data)
            except Exception: break
    def sub(self, dest):
        self.ws.send(frame("SUBSCRIBE", {"id": str(uuid.uuid4()), "destination": dest}))
    def messages(self, dest):
        out = []
        for f in self.frames:
            if f.startswith("MESSAGE") and f"destination:{dest}\n" in f:
                out.append(json.loads(f.split("\n\n", 1)[1].rstrip("\x00")))
        return out
    def close(self):
        self.alive = False
        try: self.ws.close()
        except Exception: pass

tok = requests.post(a.base + "/api/v1/auth/login", json={"username": a.manager, "password": a.password}).json()["data"]["accessToken"]
mgr = Client(tok)
check("MANAGER CONNECT", mgr.connected, mgr.first[:200])
mgr.sub("/topic/tables")
time.sleep(0.5)
r = requests.post(a.base + "/api/v1/public/sessions/join", json={"qrToken": a.table_qr, "deviceId": "stomp-device-0001"}, headers={"X-Forwarded-For": "10.77.0.1"})
assert r.status_code == 200, r.text
g = r.json()["data"]; sid = g["session"]["sessionId"]
guest = Client(g["accessToken"])
check("Guest CONNECT bằng Guest JWT", guest.connected, guest.first[:200])
guest.sub(f"/topic/session/{sid}")
time.sleep(1)
tables = mgr.messages("/topic/tables")
check("/topic/tables nhận TABLE_CHANGED OCCUPIED sau commit", any(m["sessionId"] == sid and m["status"] == "OCCUPIED" for m in tables), tables)
r = requests.post(a.base + f"/api/v1/sessions/{sid}/force-close", headers={"Authorization": "Bearer " + tok}, json={"reason": "kiểm tra STOMP"})
assert r.status_code == 200, r.text
time.sleep(1.5)
sess = guest.messages(f"/topic/session/{sid}")
check("/topic/session/{id} nhận SESSION_CHANGED CLOSED", any(m["change"] == "CLOSED" and m["status"] == "FORCE_CLOSED" for m in sess), sess)
check("payload phiên không lộ thông tin nhân viên", all("closedBy" not in m and "closeReason" not in m for m in sess), sess)
tables = mgr.messages("/topic/tables")
check("/topic/tables nhận CLEANING", any(m["tableId"] == g["session"]["table"]["id"] and m["status"] == "CLEANING" for m in tables), tables)
check("eventId tăng dần", [m["eventId"] for m in tables] == sorted(m["eventId"] for m in tables))
late = Client(g["accessToken"])
check("BE-05: CONNECT bằng Guest JWT của phiên đã đóng bị từ chối", not late.connected and late.first.startswith("ERROR"), late.first[:200])
# FR-SES-06: phiên idle còn đơn chưa thanh toán -> /topic/staff/orders (cần DSN để chèn order và lùi last_activity_at)
if a.dsn:
    import psycopg2
    db = psycopg2.connect(a.dsn); db.autocommit = True; cur = db.cursor()
    mgr.sub("/topic/staff/orders"); time.sleep(0.5)
    r = requests.post(a.base + "/api/v1/public/sessions/join", json={"qrToken": a.attention_qr, "deviceId": "stomp-device-0002"}, headers={"X-Forwarded-For": "10.77.0.2"})
    s2 = r.json()["data"]["session"]["sessionId"]
    cur.execute("INSERT INTO orders (session_id) VALUES (%s) RETURNING id", (s2,)); oid = cur.fetchone()[0]
    cur.execute("""INSERT INTO order_batch (order_id, session_id, seq, client_request_id, status, requires_confirmation, submitted_by_type, device_id)
                   VALUES (%s, %s, 1, %s, 'ACCEPTED', false, 'GUEST', 'device-0001') RETURNING id""", (oid, s2, str(uuid.uuid4()))); bid = cur.fetchone()[0]
    cur.execute("SELECT id, name, price FROM product WHERE status='AVAILABLE' ORDER BY id LIMIT 1"); p = cur.fetchone()
    cur.execute("""INSERT INTO order_item (order_id, batch_id, product_id, product_name, unit_price, quantity, line_total, status, device_id, confirmed_at)
                   VALUES (%s, %s, %s, %s, %s, 1, %s, 'CONFIRMED', 'device-0001', now())""", (oid, bid, p[0], p[1], p[2], p[2]))
    cur.execute("UPDATE table_session SET last_activity_at = now() - interval '1 day' WHERE id = %s", (s2,))
    deadline = time.time() + 15
    while time.time() < deadline and not mgr.messages("/topic/staff/orders"): time.sleep(0.5)
    att = mgr.messages("/topic/staff/orders")
    check("/topic/staff/orders nhận SESSION_NEEDS_ATTENTION", any(m["type"] == "SESSION_NEEDS_ATTENTION" and m["sessionId"] == s2 for m in att), att)
    
for c in (mgr, guest, late): c.close()
print(f"\nKẾT QUẢ STOMP: {PASS} PASS, {FAIL} FAIL")
raise SystemExit(1 if FAIL else 0)
