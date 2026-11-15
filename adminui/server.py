#!/usr/bin/env python3
"""校友录数据库管理页：浏览并修改 alumin-link 里的业务数据。

只监听 127.0.0.1。表名和列名一律先与 information_schema 比对（白名单），
所有值都走参数绑定，页面输入没有任何一处会被当作 SQL 标识符或语句片段拼接。
"""
from __future__ import annotations

import hmac
import os
import urllib.parse
import uuid
from collections import OrderedDict
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pymysql

DB_HOST = os.environ.get("XYL_DB_HOST", "127.0.0.1")
DB_PORT = int(os.environ.get("XYL_DB_PORT", "3307"))
DB_USER = os.environ.get("XYL_DB_USER", "root")
DB_PASS = os.environ.get("XYL_DB_PASSWORD", "123456")
DB_NAME = os.environ.get("XYL_DB_SCHEMA", "alumin-link")
BIND = os.environ.get("XYL_ADMIN_BIND", "127.0.0.1")
PORT = int(os.environ.get("XYL_ADMIN_PORT", "8080"))
PAGE_SIZE = 25

GROUPS = OrderedDict([
    ("用户与权限", ["sys_user", "alk_user_profile", "sys_role", "sys_user_roles",
                  "alk_user_tag", "sys_sessions"]),
    ("学校与组织", ["alk_school", "alk_college", "alk_major", "alk_class",
                "alk_school_admin", "alk_college_admin", "alk_major_admin",
                "alk_class_admin", "alk_organization", "alk_organization_member",
                "alk_org_class", "alk_education", "alk_work_experience"]),
    ("动态与内容", ["alk_post", "alk_comment", "alk_like", "alk_banner",
                "alk_forum_category", "alk_forum_post", "alk_forum_comment",
                "alk_forum_like", "alk_forum_share", "alk_forum_collection",
                "alk_forum_user_follow_category"]),
    ("校友关系与消息", ["alk_connection", "alk_connection_privacy", "alk_conversation",
                  "alk_message", "alk_group_conversation", "alk_group_conversation_member"]),
    ("认领 · 捐赠 · 招聘", ["alk_claim", "alk_claim_item", "alk_company", "alk_company_claim",
                       "alk_donation", "alk_job", "alk_job_application", "alk_job_favorite",
                       "alk_resume"]),
    ("文件与系统", ["oss_user_file", "sys_cache", "sys_logs", "view_user_role"]),
])

LABELS = {
    "sys_user": "用户账号", "alk_user_profile": "用户资料", "sys_role": "角色",
    "sys_user_roles": "用户角色关联", "alk_user_tag": "用户标签", "sys_sessions": "登录会话",
    "alk_school": "学校", "alk_college": "学院", "alk_major": "专业", "alk_class": "班级",
    "alk_school_admin": "学校管理员", "alk_college_admin": "学院管理员",
    "alk_major_admin": "专业管理员", "alk_class_admin": "班级管理员",
    "alk_organization": "校友组织", "alk_organization_member": "组织成员",
    "alk_org_class": "组织关联班级", "alk_education": "教育经历",
    "alk_work_experience": "工作经历",
    "alk_post": "动态", "alk_comment": "评论", "alk_like": "点赞", "alk_banner": "轮播图",
    "alk_forum_category": "论坛分区", "alk_forum_post": "论坛帖子",
    "alk_forum_comment": "论坛回复", "alk_forum_like": "论坛点赞", "alk_forum_share": "论坛分享",
    "alk_forum_collection": "论坛收藏", "alk_forum_user_follow_category": "论坛关注分区",
    "alk_connection": "好友关系", "alk_connection_privacy": "关系隐私设置",
    "alk_conversation": "单聊会话", "alk_message": "消息",
    "alk_group_conversation": "群聊会话", "alk_group_conversation_member": "群聊成员",
    "alk_claim": "认领记录", "alk_claim_item": "认领物品", "alk_company": "企业",
    "alk_company_claim": "企业认领", "alk_donation": "捐赠记录", "alk_job": "招聘职位",
    "alk_job_application": "投递记录", "alk_job_favorite": "职位收藏", "alk_resume": "简历",
    "oss_user_file": "上传文件", "sys_cache": "系统缓存", "sys_logs": "系统日志",
    "view_user_role": "用户角色视图",
    "id": "主键", "user_id": "用户", "user_name": "用户名", "real_name": "真实姓名",
    "password": "密码哈希", "phone": "手机号", "email": "邮箱", "avatar": "头像",
    "background": "背景图", "is_use": "已启用", "name": "名称", "content": "内容",
    "images": "图片", "location": "位置", "visibility": "可见性", "likes_count": "点赞数",
    "comments_count": "评论数", "shares_count": "分享数", "category_id": "分类",
    "school_id": "学校", "college_id": "学院", "major_id": "专业", "class_id": "班级",
    "school": "学校名", "college": "学院名", "major": "专业名", "class_name": "班级名",
    "grade": "年级", "student_id": "学号", "faculty": "院系", "gender": "性别",
    "birthday": "生日", "graduation_year": "毕业年份", "current_city": "所在城市",
    "introduction": "简介", "company": "公司", "position": "职位", "industry": "行业",
    "wechat": "微信", "qq": "QQ", "is_public": "公开", "member_count": "成员数",
    "status": "状态", "role_name": "角色名", "type": "类型", "value": "值", "key": "键",
    "created_at": "创建时间", "created_by": "创建人", "updated_at": "更新时间",
    "updated_by": "更新人", "deleted": "已删除",
}
SECRET_BITS = ("password", "token", "secret", "credential")
DATE_TYPES = ("timestamp", "datetime", "date", "time")
LONG_TYPES = ("text", "tinytext", "mediumtext", "longtext", "json")


class _Current:
    """让数据库自己取时间：绑定参数会经过时区换算，直接写 CURRENT_TIMESTAMP 才在营机
    的 timestamp 列上和应用写进去的值保持一致。"""
    def __repr__(self):
        return "CURRENT_TIMESTAMP"


CURRENT = _Current()


def default_text(col) -> str:
    """information_schema 会把字符串默认值连引号一起返回，比较前要去掉。"""
    raw = col["default"]
    return "" if raw is None else str(raw).strip().strip("'\"")


def invalid_default(col) -> bool:
    """存量表里 updated_at 常见 `not null default '0000-00-00 00:00:00'`，
    在当前 sql_mode（STRICT + NO_ZERO_DATE）下按默认值插入会直接报错。"""
    return (col["data_type"] in DATE_TYPES
            and default_text(col).startswith("0000-00-00"))


def sql_value(value):
    return "current_timestamp" if value is CURRENT else "%s"


def bind_values(vals):
    return [v for v in vals.values() if v is not CURRENT]


def label(name: str) -> str:
    return LABELS.get(name, name)


def is_secret(name: str) -> bool:
    low = name.lower()
    return any(bit in low for bit in SECRET_BITS)


def ident(name: str) -> str:
    """只用于已经过白名单校验的真实表名/列名。"""
    return "`" + name.replace("`", "``") + "`"


def connect():
    return pymysql.connect(host=DB_HOST, port=DB_PORT, user=DB_USER, password=DB_PASS,
                           database=DB_NAME, charset="utf8mb4",
                           cursorclass=pymysql.cursors.DictCursor,
                           connect_timeout=5, read_timeout=20, write_timeout=20, autocommit=True)


def read_schema() -> "OrderedDict[str, dict]":
    with connect() as c, c.cursor() as cur:
        cur.execute("select table_name t, table_type ty, table_comment cm"
                    " from information_schema.tables where table_schema=%s order by table_name",
                    (DB_NAME,))
        tables = OrderedDict((r["t"], {"type": r["ty"], "comment": (r["cm"] or "").strip(),
                                       "cols": []}) for r in cur.fetchall())
        cur.execute("select table_name t, column_name n, column_type ct, data_type dt,"
                    " is_nullable nu, column_default dft, column_key k, extra x"
                    " from information_schema.columns where table_schema=%s"
                    " order by table_name, ordinal_position", (DB_NAME,))
        for r in cur.fetchall():
            tbl = tables.get(r["t"])
            if tbl is None:
                continue
            extra = (r["x"] or "").upper()
            tbl["cols"].append({
                "name": r["n"], "column_type": r["ct"] or "", "data_type": r["dt"] or "",
                "nullable": r["nu"] == "YES", "default": r["dft"], "pk": r["k"] == "PRI",
                "auto": "auto_increment" in extra, "generated": "GENERATED" in extra,
            })
    return tables


def row_counts(tables) -> dict:
    out = {}
    with connect() as c, c.cursor() as cur:
        for name, tbl in tables.items():
            if tbl["type"] == "BASE TABLE":
                cur.execute("select count(*) n from %s" % ident(name))
                out[name] = cur.fetchone()["n"]
    return out


def pk_column(tbl):
    for c in tbl["cols"]:
        if c["pk"]:
            return c
    return None


def uuid_pk(tbl) -> bool:
    """业务表主键是 varchar，留空时由管理页生成 UUID；int 自增则交给数据库。"""
    col = pk_column(tbl)
    return col is not None and col["data_type"] == "varchar" and not col["auto"]


def writable(tbl) -> bool:
    """单主键的普通表才允许增删改；视图、无主键和复合主键只读。"""
    return tbl["type"] == "BASE TABLE" and sum(1 for c in tbl["cols"] if c["pk"]) == 1


def pk_value(tbl, row):
    col = pk_column(tbl)
    if col is None:
        return ""
    val = row.get(col["name"])
    return "" if val is None else str(val)


def display(col, raw) -> str:
    """零日期显示成空白：原样写回会被 sql_mode 拒绝，留空则交给默认值。"""
    if raw is None:
        return ""
    text = str(raw)
    if col["data_type"] in DATE_TYPES and text.startswith("0000-00-00"):
        return ""
    return text


# ------------------------------- 渲染 -------------------------------

CSS = """
:root{--bd:#dcdfe6;--fg:#1f2329;--mut:#6b7280;--bg:#f6f7f9;--pri:#2f6fed}
*{box-sizing:border-box}
body{margin:0;font:14px/1.65 "Noto Sans CJK SC","Noto Sans SC",sans-serif;color:var(--fg);background:var(--bg)}
a{color:var(--pri);text-decoration:none}a:hover{text-decoration:underline}
header{background:#fff;border-bottom:1px solid var(--bd);padding:10px 18px;display:flex;gap:16px;
align-items:baseline;flex-wrap:wrap}
header b{font-size:16px}header span{color:var(--mut);font-size:12px}
main{padding:18px;max-width:1700px}h2{font-size:16px;margin:20px 0 8px}
.card{background:#fff;border:1px solid var(--bd);border-radius:6px;padding:12px 14px;margin-bottom:14px}
table{border-collapse:collapse;width:100%;background:#fff;font-size:13px}
th,td{border-bottom:1px solid var(--bd);padding:6px 8px;text-align:left;vertical-align:top}
th{background:#fafbfc;white-space:nowrap;font-weight:600}
td.c{max-width:320px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;
font-family:ui-monospace,Menlo,monospace;font-size:12px}
.grid{overflow:auto;max-height:76vh;border:1px solid var(--bd);border-radius:6px;background:#fff}
label{display:block;font-size:12px;color:var(--mut);margin-bottom:3px}
input,select,textarea{width:100%;padding:6px 8px;border:1px solid var(--bd);border-radius:5px;
font:13px ui-monospace,Menlo,monospace;background:#fff}
textarea{min-height:78px}
.btn{display:inline-block;padding:6px 14px;border:1px solid var(--bd);border-radius:5px;background:#fff;
color:var(--fg);font-size:13px;cursor:pointer;text-decoration:none}
.btn.pri{background:var(--pri);border-color:var(--pri);color:#fff}
.btn.dan{color:#b91c1c;border-color:#f0b4b4}
.row{display:flex;gap:16px;flex-wrap:wrap}
.row>div{flex:1 1 280px;max-width:560px}
.msg{padding:9px 12px;border-radius:6px;margin-bottom:14px;font-size:13px;white-space:pre-wrap}
.msg.ok{background:#eaf6ee;border:1px solid #b6e0c3;color:#166534}
.msg.err{background:#fdecec;border:1px solid #f3c1c1;color:#991b1b}
.mut{color:var(--mut)}
.tag{font-size:11px;color:var(--mut);border:1px solid var(--bd);border-radius:9px;padding:0 7px;margin-left:8px}
.mask{color:#b45309}
.pager{display:flex;gap:10px;align-items:center;margin:12px 0;font-size:13px}
form.inline{display:flex;gap:8px;align-items:flex-end;flex-wrap:wrap}
form.inline>div{flex:0 1 260px}
"""


def esc(x) -> str:
    if x is None:
        return ""
    return (str(x).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace('"', "&quot;").replace("'", "&#39;"))


def note(msg, kind):
    return "<div class='msg %s'>%s</div>" % (kind, esc(msg)) if msg else ""


def field(name, value):
    return "<input type='hidden' name='%s' value='%s'>" % (esc(name), esc(value))


def quote(x):
    return urllib.parse.quote(x, safe="")


def list_url(name):
    return "/t/" + quote(name)


def page_args(query):
    return {"page": (query.get("page") or ["1"])[0],
            "size": (query.get("size") or [str(PAGE_SIZE)])[0],
            "col": (query.get("col") or [""])[0],
            "q": (query.get("q") or [""])[0]}


def list_href(name, args, msg=None):
    extra = dict(args)
    if msg:
        return list_url(name) + "?" + urllib.parse.urlencode(extra) + "&msg=" + quote(msg)
    return list_url(name) + "?" + urllib.parse.urlencode(extra)


# ------------------------------- 处理器 -------------------------------

class Handler(BaseHTTPRequestHandler):
    server_version = "XYL-AdminDB"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        if os.environ.get("XYL_ADMIN_VERBOSE"):
            super().log_message(fmt, *args)

    # ---------- 收发 ----------
    def send_html(self, title, body, code=200):
        html = ("<!doctype html><html lang='zh'><head><meta charset='utf-8'>"
                "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                "<title>%s · 校友录数据库管理</title><style>%s</style></head><body>"
                "<header><b>校友录 · 数据库管理</b><span>%s@%s:%d / %s</span>"
                "<span><a href='/'>表目录</a></span>"
                "<span class='mut'>直连业务库，保存即时生效</span></header><main>%s</main></body></html>"
                % (esc(title), CSS, esc(DB_USER), esc(DB_HOST), DB_PORT, esc(DB_NAME), body))
        data = html.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Frame-Options", "DENY")
        csrf = getattr(self, "_csrf", "")
        if csrf:
            self.send_header("Set-Cookie", "xyl_admin_csrf=%s; Path=/; HttpOnly; SameSite=Lax" % csrf)
        self.end_headers()
        self.wfile.write(data)

    def send_redirect(self, loc):
        self.send_response(303)
        self.send_header("Location", loc)
        self.send_header("Content-Length", "0")
        csrf = getattr(self, "_csrf", "")
        if csrf:
            self.send_header("Set-Cookie", "xyl_admin_csrf=%s; Path=/; HttpOnly; SameSite=Lax" % csrf)
        self.end_headers()

    def cookie_value(self):
        ck = SimpleCookie()
        ck.load(self.headers.get("Cookie") or "")
        got = ck.get("xyl_admin_csrf")
        return got.value if got else ""

    def init_csrf(self):
        """每个请求都要带上 cookie：浏览器没有就现场发一个。"""
        self._csrf = self.cookie_value() or uuid.uuid4().hex

    def csrf_field(self):
        return field("__t", self._csrf)

    def csrf_ok(self, form):
        return hmac.compare_digest(self._csrf, form.get("__t", ""))

    def read_form(self):
        length = int(self.headers.get("Content-Length") or 0)
        if not 0 < length <= 8_000_000:
            raise ValueError("提交内容过大")
        raw = self.rfile.read(length).decode("utf-8", "replace")
        return {k: v[0] for k, v in urllib.parse.parse_qs(raw, keep_blank_values=True).items()}

    def route(self):
        parts = [urllib.parse.unquote(p) for p in urllib.parse.urlparse(self.path).path.split("/") if p]
        if parts[:1] != ["t"] or len(parts) not in (2, 3):
            raise LookupError("没有这个页面")
        tables = read_schema()
        if parts[1] not in tables:
            raise LookupError("数据库里没有表 %s" % parts[1])
        return parts, tables[parts[1]], parts[1]

    # ---------- 入口 ----------
    def do_GET(self):
        self.init_csrf()
        query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        try:
            if urllib.parse.urlparse(self.path).path in ("/", "/index.html"):
                self.home()
                return
            parts, tbl, name = self.route()
            if len(parts) == 3 and parts[2] == "new":
                self.edit(tbl, name, None, page_args(query))
            elif len(parts) == 3:
                self.edit(tbl, name, parts[2], page_args(query))
            else:
                self.browse(tbl, name, query)
        except LookupError as exc:
            self.send_html("找不到", note(str(exc), "err") + "<a class='btn' href='/'>返回表目录</a>", 404)
        except Exception as exc:
            self.send_html("出错了", note("%s：%s" % (type(exc).__name__, exc), "err")
                           + "<a class='btn' href='/'>返回表目录</a>", 500)

    def do_POST(self):
        self.init_csrf()
        try:
            form = self.read_form()
            if not self.csrf_ok(form):
                raise PermissionError("会话校验没通过，请刷新页面重试")
            parts, tbl, name = self.route()
            if len(parts) != 3 or parts[2] not in ("save", "delete"):
                raise LookupError("未知操作")
            args = page_args({"page": [form.get("__page", "1")], "size": [form.get("__size", "25")],
                              "col": [form.get("__fcol", "")], "q": [form.get("__fq", "")]})
            if parts[2] == "save":
                self.save(tbl, name, form, args)
            else:
                self.remove(tbl, name, form, args)
        except PermissionError as exc:
            self.send_html("拒绝", note(str(exc), "err"), 403)
        except LookupError as exc:
            self.send_html("找不到", note(str(exc), "err"), 404)
        except Exception as exc:
            self.send_html("保存失败", note("%s：%s" % (type(exc).__name__, exc), "err")
                           + "<a class='btn' href='/'>返回表目录</a>", 500)

    # ---------- 表目录 ----------
    def home(self):
        tables = read_schema()
        counts = row_counts(tables)
        used, blocks = set(), []
        for title, names in GROUPS.items():
            rows = []
            for n in names:
                if n in tables:
                    used.add(n)
                    rows.append(self.home_row(tables[n], n, counts))
            if rows:
                blocks.append("<h2>%s</h2><div class='card'><table><tbody>%s</tbody></table></div>"
                              % (esc(title), "".join(rows)))
        rest = [self.home_row(tables[n], n, counts) for n in tables if n not in used]
        if rest:
            blocks.append("<h2>其他</h2><div class='card'><table><tbody>%s</tbody></table></div>"
                          % "".join(rest))
        self.send_html("表目录", "".join(blocks))

    def home_row(self, tbl, name, counts):
        info = "%d 行" % counts[name] if tbl["type"] == "BASE TABLE" else "只读视图"
        act = "浏览 / 修改" if writable(tbl) else "只读浏览"
        return ("<tr><td style='width:250px'><a href='%s'><b>%s</b></a>"
                "<div class='mut' style='font-size:12px'>%s</div></td>"
                "<td style='width:110px' class='mut'>%s</td>"
                "<td style='width:120px'><a href='%s'>%s</a></td>"
                "<td class='mut' style='font-size:12px'>%s</td></tr>"
                % (esc(list_url(name)), esc(label(name)), esc(name), esc(info),
                   esc(list_url(name)), esc(act), esc(tbl["comment"])))

    # ---------- 列表 ----------
    def browse(self, tbl, name, query):
        msg = (query.get("msg") or [""])[0]
        cols = [c["name"] for c in tbl["cols"]]
        fcol = (query.get("col") or [""])[0]
        fval = (query.get("q") or [""])[0]
        if fcol and fcol not in cols:
            raise LookupError("筛选列 %s 不存在" % fcol)
        where, args = "", []
        if fval != "":
            like = fval.replace("\\", "\\\\").replace("%", r"\%").replace("_", r"\_")
            where = " where %s like %%s" % ident(fcol or cols[0])
            args = ["%" + like + "%"]
        size = clamp_int((query.get("size") or [""])[0], PAGE_SIZE, 1, 200)
        with connect() as c, c.cursor() as cur:
            cur.execute("select count(*) n from %s%s" % (ident(name), where), args)
            total = cur.fetchone()["n"]
            pages = max((total + size - 1) // size, 1)
            pageno = clamp_int((query.get("page") or [""])[0], 1, 1, pages)
            sql = "select %s from %s%s" % (", ".join(ident(x) for x in cols), ident(name), where)
            pk = pk_column(tbl)
            if pk:
                sql += " order by %s" % ident(pk["name"])
            cur.execute(sql + " limit %s offset %s", args + [size, (pageno - 1) * size])
            rows = cur.fetchall()
        if tbl["type"] != "BASE TABLE":
            self.readonly_grid(tbl, name, cols, rows[:200], msg)
            return
        self.data_grid(tbl, name, cols, rows, total, pageno, pages, size, fcol, fval, msg)

    def data_grid(self, tbl, name, cols, rows, total, pageno, pages, size, fcol, fval, msg):
        can = writable(tbl)
        args = {"page": pageno, "size": size, "col": fcol, "q": fval}
        head = "".join("<th>%s<div class='mut' style='font-size:11px'>%s</div></th>"
                       % (esc(label(c)), esc(c)) for c in cols)
        if can:
            head += "<th style='width:120px'>操作</th>"
        body = []
        for r in rows:
            tds = []
            for col in tbl["cols"]:
                c = col["name"]
                if is_secret(c):
                    text, cls = "******", "c mask"
                else:
                    text = display(col, r.get(c))
                    if r.get(c) is None:
                        text, cls = "NULL", "c mut"
                    else:
                        cls = "c"
                    if len(text) > 140:
                        text = text[:140] + "…"
                tds.append("<td class='%s' title='%s'>%s</td>" % (cls, esc(text), esc(text)))
            act = ""
            if can:
                pk = pk_value(tbl, r)
                href = "%s/%s?%s" % (list_url(name), quote(pk), urllib.parse.urlencode(args))
                act = ("<a href='%s'>修改</a> <form method='post' action='%s/delete' style='display:inline'>"
                       "%s%s%s%s%s"
                       "<button class='btn dan' style='padding:2px 9px'"
                       " onclick=\"return confirm('确定删除这一行？删除后无法撤销。')\">删除</button></form>"
                       % (esc(href), esc(list_url(name)), self.csrf_field(), field("__pk", pk),
                          field("__page", pageno), field("__size", size),
                          field("__fcol", fcol) + field("__fq", fval)))
            body.append("<tr>%s%s</tr>" % ("".join(tds), ("<td style='white-space:nowrap'>%s</td>" % act)
                                           if can else ""))
        opts = "".join('<option value="%s"%s>%s · %s</option>'
                       % (esc(c), " selected" if c == fcol else "", esc(label(c)), esc(c)) for c in cols)
        bar = ("<div class='card'><form class='inline' method='get' action='%s'>"
               "<div><label>筛选列</label><select name='col'>%s</select></div>"
               "<div><label>值包含</label><input name='q' value='%s' placeholder='留空表示不过滤'></div>"
               "<div style='flex:0 0 92px'><label>每页</label><input name='size' value='%d'></div>"
               "<input type='hidden' name='page' value='1'>"
               "<button class='btn pri' type='submit'>筛选</button>"
               "<a class='btn' href='%s'>重置</a></form></div>"
               % (esc(list_url(name)), opts, esc(fval), size, esc(list_url(name))))
        pager = ""
        if pages > 1:
            def link(p, text):
                if 1 <= p <= pages and p != pageno:
                    return "<a href='%s'>%s</a>" % (esc(list_href(name, {**args, "page": p})), text)
                return "<span class='mut'>%s</span>" % text
            pager = ("<div class='pager'>%s%s<span class='mut'>第 %d / %d 页，共 %d 行</span>%s%s</div>"
                     % (link(1, "首页"), link(pageno - 1, "上一页"), pageno, pages, total,
                        link(pageno + 1, "下一页"), link(pages, "末页")))
        else:
            pager = "<div class='pager'><span class='mut'>共 %d 行</span></div>" % total
        newbtn = "<a class='btn pri' href='%s/new?%s'>＋ 新增一行</a>" % (
            esc(list_url(name)), esc(urllib.parse.urlencode(args))) if can else ""
        hint = "" if can else "<div class='msg err'>这张表没有单列主键，只能浏览，不能修改。</div>"
        self.send_html(label(name), note(msg, "ok") + hint
                       + "<h2>%s <span class='tag'>%s</span></h2>"
                         "<div style='margin-bottom:10px'>%s</div>%s"
                         "<div class='grid'><table><thead><tr>%s</tr></thead><tbody>%s</tbody></table></div>%s"
                       % (esc(label(name)), esc(name), newbtn, bar, head,
                          "".join(body) or "<tr><td class='mut'>这张表当前没有数据</td></tr>", pager))

    def readonly_grid(self, tbl, name, cols, rows, msg):
        if not rows:
            self.send_html(name, note(msg, "ok") + note("这个视图当前没有数据。", "ok"))
            return
        head = "".join("<th>%s</th>" % esc(label(c)) for c in cols)
        body = "".join("<tr>%s</tr>" % "".join("<td class='c'>%s</td>" % esc(str(v)[:140]) for v in r.values())
                       for r in rows)
        self.send_html(name, note(msg, "ok") + "<h2>%s <span class='tag'>只读视图，不可修改</span></h2>"
                       "<div class='grid'><table><thead><tr>%s</tr></thead><tbody>%s</tbody></table></div>"
                       % (esc(label(name)), head, body))

    # ---------- 编辑表单 ----------
    def edit(self, tbl, name, pk, args, message="", kind="ok", prefill=None):
        if not writable(tbl):
            raise LookupError("这张表是视图或缺少单列主键，只能浏览")
        row = None
        if pk is not None:
            row = load_row(tbl, name, pk)
            if row is None:
                raise LookupError("表 %s 里没有主键 %s 的行" % (name, pk))
        if prefill is not None:
            row = prefill
        fields_html = [self.field_html(col, row, pk is not None, prefill is not None)
                       for col in tbl["cols"]]
        title = ("新增 · %s" % label(name)) if pk is None else ("修改 · %s" % label(name))
        hidden = field("__pk", pk) if pk is not None else ""
        keep = "".join([field("__page", args["page"]), field("__size", args["size"]),
                        field("__fcol", args["col"]), field("__fq", args["q"])])
        back = list_href(name, args)
        self.send_html(title, note(message, kind)
                       + "<h2>%s <span class='tag'>%s</span></h2><div class='card'>"
                         "<form method='post' action='%s/save'>%s%s%s"
                         "<div class='row'>%s</div>"
                         "<div style='margin-top:14px'><button class='btn pri' type='submit'>保存</button>"
                         " <a class='btn' href='%s'>返回不保存</a>"
                         "<span class='mut' style='margin-left:14px'>留空＝可空列存 NULL、其余沿用默认值或保持原值；"
                         "打码列留空表示不修改；新增行必须填带 * 的列。</span></div></form></div>"
                       % (esc(title), esc(name), esc(list_url(name)), self.csrf_field(), hidden, keep,
                          "".join(fields_html), esc(back)))

    def field_html(self, col, row, existing, from_form):
        cn = col["name"]
        val = "" if row is None else display(col, row.get(cn))
        hint = col["column_type"]
        if col["generated"]:
            return wrap(col, "<input value='%s（生成列，不可写）' disabled>" % esc(hint))
        if col["pk"]:
            if existing:
                return wrap(col, "<input value='%s' disabled>" % esc(val))
            blank = "留空自动生成" if (col["auto"] or col["data_type"] == "varchar") else "必填"
            return wrap(col, "<input name='%s' value='%s' placeholder='%s'>" % (esc(cn), esc(val), esc(blank)))
        if is_secret(cn):
            if from_form and val:
                # 回显的是用户刚提交的内容，不是库里的哈希
                return wrap(col, "<input name='%s' value=\"%s\" placeholder='将写入的新值'>"
                            % (esc(cn), esc(val)))
            ph = "库里已是哈希，留空表示不修改" if existing else "填 BCrypt 哈希，或留空"
            return wrap(col, "<input name='%s' value='' placeholder='%s'>" % (esc(cn), esc(ph)))
        if col["data_type"] == "tinyint" and hint.startswith("tinyint(1)"):
            options = ([("", "NULL")] if col["nullable"] else []) + [("0", "否"), ("1", "是")]
            if val in ("0", "1"):
                current = val
            elif col["nullable"] and val == "":
                current = ""
            else:
                current = "0"
            sel = "".join('<option value="%s"%s>%s</option>'
                          % (esc(v), " selected" if v == current else "", esc(t)) for v, t in options)
            return wrap(col, "<select name='%s'>%s</select>" % (esc(cn), sel))
        if col["data_type"] in LONG_TYPES:
            return wrap(col, "<textarea name='%s' placeholder='%s'>%s</textarea>"
                        % (esc(cn), esc(hint), esc(val)))
        nullbox = ""
        if col["nullable"]:
            nullbox = ("<label style='margin-top:4px'><input type='checkbox' name='%s__null' "
                       "style='width:auto;margin-right:6px'%s>存为 NULL</label>"
                       % (esc(cn), " checked" if row is not None and row.get(cn) is None else ""))
        return wrap(col, "<input name='%s' value=\"%s\" placeholder='%s'>%s"
                    % (esc(cn), esc(val), esc(hint), nullbox))

    # ---------- 写库 ----------
    def save(self, tbl, name, form, args):
        pk = form.get("__pk", "")
        vals, missing = collect_values(tbl, form, bool(pk))
        if missing:
            self.edit(tbl, name, pk or None, args,
                      "这些必填列没有填：%s" % "、".join(label(c) for c in missing), "err",
                      prefill=prefill_row(tbl, form, pk))
            return
        with connect() as c, c.cursor() as cur:
            if pk:
                if not vals:
                    self.send_redirect(list_href(name, args, "没有需要修改的列（值没变化，或必填列留空）。"))
                    return
                cur.execute("update %s set %s where %s=%%s"
                            % (ident(name),
                               ", ".join("%s=%s" % (ident(k), sql_value(v)) for k, v in vals.items()),
                               ident(pk_column(tbl)["name"])), bind_values(vals) + [pk])
                if cur.rowcount == 0:
                    self.send_redirect(list_href(name, args, "没有找到主键 %s 的行，可能已被删除。" % pk))
                    return
                msg = "已保存 %s（主键 %s）的修改。" % (label(name), pk)
            else:
                if uuid_pk(tbl):
                    pkn = pk_column(tbl)["name"]
                    if not str(vals.get(pkn) or "").strip():
                        vals[pkn] = str(uuid.uuid4())
                if not vals:
                    self.send_redirect(list_href(name, args, "没有可写入的列。"))
                    return
                cur.execute("insert into %s (%s) values (%s)"
                            % (ident(name), ", ".join(ident(k) for k in vals),
                               ", ".join(sql_value(v) for v in vals.values())), bind_values(vals))
                msg = "已新增一行到 %s。" % label(name)
        self.send_redirect(list_href(name, args, msg))

    def remove(self, tbl, name, form, args):
        pk = form.get("__pk", "")
        if not pk:
            raise ValueError("没有告诉我要删除哪一行")
        with connect() as c, c.cursor() as cur:
            cur.execute("delete from %s where %s=%%s"
                        % (ident(name), ident(pk_column(tbl)["name"])), [pk])
            gone = cur.rowcount
        msg = ("已删除 %s 中主键 %s 的行。" % (label(name), pk)) if gone \
            else "没有找到主键 %s 的行，未删除任何数据。" % pk
        self.send_redirect(list_href(name, args, msg))


# ------------------------------- 纯函数 -------------------------------

def clamp_int(raw, default, low, high):
    try:
        return min(max(int(raw), low), high)
    except (TypeError, ValueError):
        return default


def load_row(tbl, name, pk):
    cols = [c["name"] for c in tbl["cols"]]
    with connect() as c, c.cursor() as cur:
        cur.execute("select %s from %s where %s=%%s"
                    % (", ".join(ident(x) for x in cols), ident(name),
                       ident(pk_column(tbl)["name"])), [pk])
        return cur.fetchone()


def prefill_row(tbl, form, pk=""):
    """保存失败时用用户刚填的内容回填表单，避免重打一遍。"""
    row = {}
    if pk:
        row[pk_column(tbl)["name"]] = pk
    for col in tbl["cols"]:
        cn = col["name"]
        if cn in form:
            row[cn] = None if form.get(cn + "__null") else form[cn]
    return row


def collect_values(tbl, form, updating):
    """返回 (要写入的列名到值的映射, 必填却没填的列)。"""
    vals, missing = {}, []
    for col in tbl["cols"]:
        cn = col["name"]
        if col["generated"]:
            continue
        if cn not in form:
            # 浏览器总会提交每一列；请求里缺列时，只给“零日期默认值在当前 sql_mode 下不可用”
            # 的时间列补上当前时间，其余一律不动。
            if not updating and not col["nullable"] and invalid_default(col):
                vals[cn] = CURRENT
            continue
        raw = form[cn]
        if col["pk"]:
            if not updating:
                if raw.strip():
                    vals[cn] = raw
                elif not (col["auto"] or col["nullable"] or col["default"] is not None
                          or uuid_pk(tbl)):
                    missing.append(cn)
            continue
        if updating and is_secret(cn) and not raw.strip():
            continue
        if form.get(cn + "__null") and col["nullable"]:
            vals[cn] = None
            continue
        if not raw.strip():
            if col["nullable"]:
                vals[cn] = None
            elif updating or col["auto"]:
                continue
            elif invalid_default(col):
                vals[cn] = CURRENT
            elif col["default"] is not None:
                continue
            else:
                missing.append(cn)
            continue
        vals[cn] = raw
    return vals, missing


def wrap(col, widget):
    cn = col["name"]
    flags = []
    if col["nullable"]:
        flags.append("可空")
    if col["auto"]:
        flags.append("自增")
    if col["default"] is not None:
        flags.append("默认 " + str(col["default"]))
    if col["pk"]:
        flags.append("主键")
    star = "" if (col["nullable"] or col["auto"] or col["default"] is not None
                   or col["pk"] or col["generated"]) else " <b style='color:#b91c1c'>*</b>"
    return ("<div><label>%s · %s%s<div class='mut'>%s</div></label>%s</div>"
            % (esc(label(cn)), esc(cn), star, esc("，".join(flags)), widget))


def main():
    print("校友录数据库管理页 http://%s:%d （数据源 %s@%s:%d/%s）"
          % (BIND, PORT, DB_USER, DB_HOST, DB_PORT, DB_NAME))
    ThreadingHTTPServer.allow_reuse_address = True
    ThreadingHTTPServer((BIND, PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
