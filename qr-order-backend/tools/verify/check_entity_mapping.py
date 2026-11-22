"""
Đối chiếu tĩnh 26 JPA entity (Kotlin) với catalog PostgreSQL sau khi chạy migration.

Mục đích: phát hiện sớm lỗi ánh xạ mà Hibernate `ddl-auto: validate` sẽ báo, khi chưa build được ứng dụng.
Đây là kiểm tra BỔ SUNG, không thay thế validate thật (integration test SchemaValidationIT).

Quy tắc kiểm tra (mô phỏng AbstractSchemaValidator của Hibernate 6 + quy ước dự án):
  1. Mỗi bảng có đúng 1 entity; mỗi cột được ánh xạ đúng 1 lần; không có cột thừa.
  2. Kiểu Kotlin khớp kiểu PostgreSQL:
       int8->Long, int4->Int, int2->Short, numeric(p,s)->BigDecimal(precision,scale), bool->Boolean,
       varchar(n)->String|enum (length = n), text->String + columnDefinition "text",
       bpchar(n)->String + columnDefinition "bpchar(n)", timestamptz->OffsetDateTime + columnDefinition
       "timestamptz" (Hibernate 6 không coi Types.TIMESTAMP_WITH_TIMEZONE tương đương timestamptz
       nếu thiếu), uuid->UUID, date->LocalDate, jsonb->@JdbcTypeCode(JSON), inet->InetAddress + INET.
  3. Nullability: cột NULL được phép -> thuộc tính Kotlin phải nullable. Cột NOT NULL mà thuộc tính
     nullable chỉ chấp nhận khi giá trị do DB/Hibernate sinh (@Id, @Generated, insertable=false, @UpdateTimestamp).
     @Column(nullable=...) phải khớp DB.
  4. Cột `version` <=> @Version.
  5. Enum: mọi giá trị CHECK cho phép phải có trong enum Kotlin (đọc được dữ liệu); enum dùng chung
     rộng hơn CHECK chỉ được ghi chú (NOTE), DB vẫn chặn khi ghi.
Chạy: python3 tools/verify/check_entity_mapping.py "dbname=qr_prod user=postgres host=localhost password=postgres"
"""
import pathlib
import re
import sys

import psycopg

ROOT = pathlib.Path(__file__).resolve().parents[2] / "src" / "main" / "kotlin"
DSN = sys.argv[1]

# --------------------------------------------------------------------------- đọc Kotlin
sources = {p: p.read_text(encoding="utf-8") for p in ROOT.rglob("*.kt")}
all_text = "\n".join(sources.values())

enums = {}
for m in re.finditer(r"enum class (\w+)\s*(?:\([^)]*\))?\s*\{([^}]*)\}", all_text, re.S):
    body = m.group(2).split(";")[0]
    consts = [c.strip().split("(")[0].strip() for c in body.split(",")]
    enums[m.group(1)] = {c for c in consts if re.fullmatch(r"[A-Z][A-Z0-9_]*", c)}


def parse_annotation_args(text):
    args = {}
    for k, v in re.findall(r'(\w+)\s*=\s*("(?:[^"\\]|\\.)*"|\[[^\]]*\]|[\w.]+)', text):
        args[k] = v.strip('"')
    return args


def segments(text):
    """Tách các khai báo @Entity / @Embeddable cấp cao nhất."""
    starts = [m.start() for m in re.finditer(r"^(?:@Entity|@Embeddable)\b", text, re.M)]
    for i, s in enumerate(starts):
        e = starts[i + 1] if i + 1 < len(starts) else len(text)
        yield text[s:e]


def parse_fields(seg):
    fields = []
    pending = []
    lines = seg.splitlines()
    i = 0
    while i < len(lines):
        line = lines[i].strip()
        if line.startswith("@") and not line.startswith(("@Entity", "@Table", "@Embeddable", "@Immutable")):
            ann = line
            depth = ann.count("(") - ann.count(")")
            while depth > 0:
                i += 1
                ann += " " + lines[i].strip()
                depth = ann.count("(") - ann.count(")")
            pending.append(ann)
        else:
            m = re.match(r"(?:override\s+)?(?:var|val)\s+(\w+)\s*:\s*(.+)$", line)
            if m and pending:
                ktype = re.split(r"\s=\s", m.group(2))[0].rstrip(",) ").strip()
                fields.append({"name": m.group(1), "type": ktype, "anns": pending})
            if m or (line and not line.startswith(("/**", "*", "//"))):
                pending = [] if m else pending
        i += 1
    return fields


entities, embeddables = {}, {}
for path, text in sources.items():
    for seg in segments(text):
        cm = re.search(r"class (\w+)", seg)
        if not cm:
            continue
        cls = cm.group(1)
        fields = parse_fields(seg)
        if seg.startswith("@Embeddable"):
            embeddables[cls] = fields
        else:
            tm = re.search(r'@Table\(name = "(\w+)"\)', seg)
            entities[cls] = {"table": tm.group(1) if tm else None, "fields": fields, "file": path.name,
                             "immutable": "@Immutable" in seg.split("class ")[0]}


def column_of(field):
    for a in field["anns"]:
        if a.startswith("@Column("):
            return parse_annotation_args(a[len("@Column("):-1])
    return None


def has(field, prefix):
    return any(a.startswith(prefix) for a in field["anns"])


# --------------------------------------------------------------------------- đọc catalog
with psycopg.connect(DSN) as c:
    cols = c.execute("""
        SELECT table_name, column_name, udt_name, is_nullable = 'YES', character_maximum_length,
               numeric_precision, numeric_scale
          FROM information_schema.columns WHERE table_schema = 'public'
         ORDER BY table_name, ordinal_position""").fetchall()
    checks = c.execute("""
        SELECT rel.relname, pg_get_constraintdef(con.oid)
          FROM pg_constraint con JOIN pg_class rel ON rel.oid = con.conrelid
          JOIN pg_namespace n ON n.oid = rel.relnamespace
         WHERE con.contype = 'c' AND n.nspname = 'public'""").fetchall()
    tables = {r[0] for r in c.execute(
        "SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE' "
        "AND table_name <> 'flyway_schema_history'").fetchall()}

db = {}
for t, col, udt, nullable, clen, prec, scale in cols:
    if t == "flyway_schema_history":
        continue
    db.setdefault(t, {})[col] = dict(udt=udt, nullable=nullable, len=clen, prec=prec, scale=scale)


def allowed_values(table, column):
    """Hợp các giá trị mà mọi CHECK của bảng cho phép cột nhận:
    (col)::text = ANY (ARRAY[...])  và  (col)::text = 'X'::text."""
    values = set()
    col = re.escape(column)
    for t, d in checks:
        if t != table:
            continue
        for m in re.finditer(r"\(%s\)::text = ANY \(\(ARRAY\[(.*?)\]" % col, d):
            values |= set(re.findall(r"'([A-Z_]+)'::character varying", m.group(1)))
        values |= set(re.findall(r"\(%s\)::text = '([A-Z_]+)'::text" % col, d))
    return values or None


# --------------------------------------------------------------------------- so sánh
errors, notes, checked_cols = [], [], 0
mapped_tables = {}
for cls, e in entities.items():
    if not e["table"]:
        errors.append(f"{cls}: thiếu @Table(name=...)")
        continue
    if e["table"] in mapped_tables:
        errors.append(f"Bảng {e['table']} được ánh xạ bởi 2 entity: {mapped_tables[e['table']]}, {cls}")
    mapped_tables[e["table"]] = cls

for t in sorted(tables - set(mapped_tables)):
    errors.append(f"Bảng {t} chưa có entity")

TYPE_OF = {"int8": "Long", "int4": "Int", "int2": "Short", "numeric": "BigDecimal", "bool": "Boolean",
           "uuid": "UUID", "date": "LocalDate", "timestamptz": "OffsetDateTime"}

for cls, e in sorted(entities.items()):
    table = e["table"]
    if table not in db:
        errors.append(f"{cls}: bảng {table} không tồn tại trong DB")
        continue
    flat = []
    for f in e["fields"]:
        if has(f, "@EmbeddedId"):
            emb = embeddables.get(f["type"].rstrip("?"))
            if emb is None:
                errors.append(f"{cls}.{f['name']}: không tìm thấy @Embeddable {f['type']}")
                continue
            flat += [dict(x, embedded_id=True) for x in emb]
        else:
            flat.append(f)
    seen = {}
    for f in flat:
        col = column_of(f)
        if col is None:
            errors.append(f"{cls}.{f['name']}: thiếu @Column(name=...)")
            continue
        name = col.get("name")
        where = f"{cls}.{f['name']} ({table}.{name})"
        if name in seen:
            errors.append(f"{where}: cột bị ánh xạ 2 lần")
        seen[name] = f
        meta = db[table].get(name)
        if meta is None:
            errors.append(f"{where}: cột không tồn tại trong DB")
            continue
        checked_cols += 1
        ktype = f["type"].rstrip("?").strip()
        knullable = f["type"].endswith("?")
        cdef = col.get("columnDefinition")
        udt = meta["udt"]
        enum_name = ktype if ktype in enums else None
        # 2. kiểu
        if udt == "varchar":
            if not (ktype == "String" or (enum_name and has(f, "@Enumerated(EnumType.STRING)"))):
                errors.append(f"{where}: varchar cần String hoặc enum @Enumerated(STRING), gặp {ktype}")
            if str(meta["len"]) != col.get("length", "255"):
                errors.append(f"{where}: length={col.get('length', '255 (mặc định)')} khác DB varchar({meta['len']})")
        elif udt == "text":
            if ktype != "String" or cdef != "text":
                errors.append(f"{where}: text cần String + columnDefinition=\"text\"")
        elif udt == "bpchar":
            if ktype != "String" or cdef != f"bpchar({meta['len']})":
                errors.append(f"{where}: char({meta['len']}) cần columnDefinition=\"bpchar({meta['len']})\", gặp {cdef}")
        elif udt == "jsonb":
            if not has(f, "@JdbcTypeCode(SqlTypes.JSON)") or cdef != "jsonb":
                errors.append(f"{where}: jsonb cần @JdbcTypeCode(SqlTypes.JSON) + columnDefinition=\"jsonb\"")
        elif udt == "inet":
            if ktype != "InetAddress" or not has(f, "@JdbcTypeCode(SqlTypes.INET)") or cdef != "inet":
                errors.append(f"{where}: inet cần InetAddress + @JdbcTypeCode(SqlTypes.INET) + columnDefinition=\"inet\"")
        elif udt in TYPE_OF:
            if ktype != TYPE_OF[udt]:
                errors.append(f"{where}: {udt} cần {TYPE_OF[udt]}, gặp {ktype}")
            if udt == "timestamptz" and cdef != "timestamptz":
                errors.append(f"{where}: timestamptz cần columnDefinition=\"timestamptz\" (Hibernate validate)")
            if udt == "numeric" and (col.get("precision") != str(meta["prec"]) or col.get("scale") != str(meta["scale"])):
                errors.append(f"{where}: numeric({meta['prec']},{meta['scale']}) cần precision/scale khớp")
        else:
            errors.append(f"{where}: kiểu DB {udt} chưa có quy tắc")
        # 3. nullability
        generated = has(f, "@Id") or has(f, "@Generated") or has(f, "@UpdateTimestamp") or \
            col.get("insertable") == "false" or f.get("embedded_id")
        if meta["nullable"] and not knullable and not f.get("embedded_id"):
            errors.append(f"{where}: DB cho phép NULL nhưng thuộc tính Kotlin không nullable")
        if not meta["nullable"] and knullable and not generated:
            errors.append(f"{where}: DB NOT NULL nhưng thuộc tính nullable mà không do DB/Hibernate sinh")
        if col.get("nullable") == "false" and meta["nullable"]:
            errors.append(f"{where}: @Column(nullable=false) nhưng DB cho phép NULL")
        if not meta["nullable"] and col.get("nullable") != "false" and not f.get("embedded_id"):
            errors.append(f"{where}: DB NOT NULL nhưng thiếu @Column(nullable = false)")
        # 4. version
        if (name == "version") != has(f, "@Version"):
            errors.append(f"{where}: cột version phải (và chỉ nó) có @Version")
        # 5. enum
        if enum_name:
            allowed = allowed_values(table, name)
            if allowed is None:
                errors.append(f"{where}: không tìm thấy CHECK IN (...) để đối chiếu enum {enum_name}")
            elif not allowed <= enums[enum_name]:
                errors.append(f"{where}: DB cho phép {sorted(allowed - enums[enum_name])} mà enum {enum_name} không có")
            elif allowed != enums[enum_name]:
                notes.append(f"{where}: dùng chung enum {enum_name}; DB chỉ cho phép {sorted(allowed)}")
    for name in db[table]:
        if name not in seen:
            errors.append(f"{cls}: cột {table}.{name} chưa được ánh xạ")

print(f"Entity: {len(entities)} | bảng DB: {len(tables)} | cột đã đối chiếu: {checked_cols} | "
      f"cột DB: {sum(len(v) for v in db.values())} | enum: {len(enums)}")
for note in notes:
    print("NOTE |", note)
for err in errors:
    print("FAIL |", err)
print("KẾT QUẢ:", "PASS" if not errors else f"{len(errors)} lỗi")
sys.exit(1 if errors else 0)
