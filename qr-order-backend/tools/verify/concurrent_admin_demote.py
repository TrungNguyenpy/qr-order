import sys, threading, psycopg
dsn = sys.argv[1]
setup = psycopg.connect(dsn, autocommit=True)
setup.execute("SET session_replication_role = replica"); setup.execute("DELETE FROM refresh_token"); setup.execute("DELETE FROM audit_log"); setup.execute("DELETE FROM user_permission"); setup.execute("UPDATE restaurant_settings SET updated_by=NULL"); setup.execute("DELETE FROM users")
setup.execute("SET session_replication_role = origin")
a = setup.execute("INSERT INTO users (username,password_hash,full_name,role) VALUES ('admin_a','x','A','ADMIN') RETURNING id").fetchone()[0]
b = setup.execute("INSERT INTO users (username,password_hash,full_name,role) VALUES ('admin_b','x','B','ADMIN') RETURNING id").fetchone()[0]
for uid in (a, b):
    setup.execute("INSERT INTO refresh_token (user_id, family_id, token_hash, expires_at) VALUES (%s, gen_random_uuid(), md5(random()::text)||md5(random()::text), now()+interval '7 days')", (uid,))
rounds, ok_rounds = 20, 0
for r in range(rounds):
    setup.execute("UPDATE users SET role='ADMIN', status='ACTIVE' WHERE id IN (%s,%s)", (a, b))
    barrier = threading.Barrier(2); out = {}
    def demote(actor, target):
        c = psycopg.connect(dsn)
        try:
            c.execute("SELECT count(*) FROM users WHERE role='ADMIN' AND status='ACTIVE'")  # pre-check của service
            ver = c.execute("SELECT version FROM users WHERE id=%s", (target,)).fetchone()[0]
            barrier.wait()
            # Câu UPDATE kiểu Hibernate: đặt lại mọi cột, WHERE id AND version
            c.execute("""UPDATE users SET created_by=created_by, full_name=full_name, last_login_at=last_login_at, password_changed_at=password_changed_at,
                          password_hash=password_hash, role='MANAGER', status=status, updated_at=now(), username=username, version=%s
                         WHERE id=%s AND version=%s""", (ver + 1, target, ver))
            c.execute("INSERT INTO audit_log (actor_type, actor_user_id, action, entity_type, entity_id, old_value, new_value, reason, reauthenticated) VALUES ('USER',%s,'USER_ROLE_CHANGED','users',%s,'{\"role\":\"ADMIN\"}','{\"role\":\"MANAGER\"}','x',true)", (actor, target))
            c.commit(); out[actor] = "OK"
        except Exception as e:
            c.rollback(); out[actor] = str(e).split(":")[0]
        finally:
            c.close()
    ts = [threading.Thread(target=demote, args=(a, b)), threading.Thread(target=demote, args=(b, a))]
    [t.start() for t in ts]; [t.join(timeout=30) for t in ts]
    admins = setup.execute("SELECT count(*) FROM users WHERE role='ADMIN' AND status='ACTIVE'").fetchone()[0]
    res = sorted(out.values())
    if res == ["LAST_ACTIVE_ADMIN", "OK"] and admins == 1: ok_rounds += 1
    else: print("Vòng", r, res, "admins=", admins)
revoked = setup.execute("SELECT revoke_reason FROM refresh_token WHERE revoked_at IS NOT NULL").fetchall()
print(f"{ok_rounds}/{rounds} vòng: đúng 1 thành công + 1 LAST_ACTIVE_ADMIN, không deadlock, luôn còn 1 ADMIN; token thu hồi: {revoked}")
sys.exit(0 if ok_rounds == rounds else 1)
