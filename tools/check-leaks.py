"""对仓库做「多形态凭据泄露」扫描。

为什么不能只跑明文 grep：
    本项目真实踩过一次坑 —— 清理时只搜明文，结果漏掉了文档里贴的
    `Base64(学号)` / `Base64(密码)`。明文全绿，但那串字符解码就是账号密码。

本脚本覆盖四类形态：
    1) 明文（已知凭据及其大小写变体）
    2) Base64：把仓库里所有「像 base64 的长串」穷举解码，看解出来是不是可读文本
    3) URL 编码（%XX 连续出现）
    4) 已知的个人信息（设备序列号、服务器 IP、真实 Cookie/ticket 形态）

⚠️ 本脚本自身**绝不硬编码任何真实凭据**（否则它自己就成了泄露源 —— 这个坑
   本项目刚踩过一次）。需要比对的「明文凭据」从环境变量 / tools/.env 读取；
   没配置时只做「结构性」检查（Base64 穷举、Cookie/IP/ticket 形态），仍然有用。

用法：
    python tools/check-leaks.py            # 扫描纯文本文件
    python tools/check-leaks.py --bin      # 额外做二进制文件的字节级扫描

配置比对用的凭据（可选）：
    $env:JW_ACCOUNT="学号"; $env:JW_PASSWORD="密码"; python tools/check-leaks.py
    或在 tools/.env 里写 JW_ACCOUNT= / JW_PASSWORD= （该文件已 gitignore）
"""
import base64
import binascii
import os
import re
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

REPO = Path(__file__).resolve().parent.parent


def _load_dotenv():
    """读 tools/.env（存在才读），与 _credentials.mjs 保持同一套约定。"""
    env_file = Path(__file__).resolve().parent / ".env"
    try:
        for line in env_file.read_text(encoding="utf-8").splitlines():
            s = line.strip()
            if not s or s.startswith("#") or "=" not in s:
                continue
            k, v = s.split("=", 1)
            os.environ.setdefault(k.strip(), v.strip().strip("\"'"))
    except OSError:
        pass


_load_dotenv()

_account = os.environ.get("JW_ACCOUNT", "").strip()
_password = os.environ.get("JW_PASSWORD", "").strip()

# 明文比对清单：只放「从环境变量拿到的东西」，不写字面量
KNOWN_PLAINTEXT = [x for x in {_account, _password} if x]
# 大小写变体由真实值派生，同样不写字面量
for base in (_password,):
    if base:
        KNOWN_PLAINTEXT += [base.lower(), base.upper(), base.capitalize()]

# 个人信息：设备序列号也从环境变量取（adb 序列号因机器而异）
KNOWN_PERSONAL = [x for x in {os.environ.get("JW_DEVICE_SERIAL", "").strip()} if x]

PERSONAL_PATTERNS = [
    # ⚠️ 这里刻意**不写学校的真实 IP 段** —— 否则本脚本自己就违反了
    #    AGENTS.md §五「不要写入服务器真实 IP」。改为检测「公网 IPv4」这个更宽的形态，
    #    并排除私网/保留网段；若要精确比对，用环境变量 JW_SERVER_IP 传入。
    #    ⚠️ 这是**启发式**，必然有假阳性，两道过滤：
    #       ① 排除末三段全为 0 的形态（`124.0.0.0` 这种只可能是软件版本号，不是可路由地址）；
    #       ② BENIGN_MARKERS 里排除 User-Agent 之类正常技术文本。
    (r"(?<!\d)(?!10\.)(?!192\.168\.)(?!172\.(?:1[6-9]|2\d|3[01])\.)"
     r"(?!127\.)(?!0\.)\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}(?!\d)",
     "疑似公网 IPv4（示例/私网段已排除）"),
    (r"ST-\d{6}-[A-Za-z0-9]{4,}", "疑似 CAS 真实票据"),
    (r"(?:bzb_jsxsd|JSESSIONID|TGC|bzb_njw)=[0-9A-Fa-f]{16,}", "疑似真实会话 Cookie"),
]

# 允许出现的占位符（避免误报）
PLACEHOLDER_HINTS = ("<", ">", "占位", "placeholder", "redacted", "xxxx", "XXXX", "HEX")

# 明显是库/框架常量或正常技术内容的串，命中上面的模式也不算隐私。
# 首版没定义这个常量（引用它却没赋值）—— 这是运行期 NameError，必须存在。
BENIGN_MARKERS = (
    "9223372036854775807",   # Long.MAX_VALUE
    "-9223372036854775808",  # Long.MIN_VALUE
    "CREATE TABLE", "ALTER TABLE", "INSERT INTO", "SELECT ", "DROP TABLE",
    "WorkSpec", "_new_", "access$",
    # User-Agent 里的浏览器版本号形如 IPv4（如 Chrome/124.0.0.0），必须排除
    "Mozilla", "AppleWebKit", "Chrome/", "Safari", "Gecko", "User-Agent",
)

TEXT_EXT = {
    ".md", ".txt", ".mjs", ".js", ".kt", ".kts", ".py", ".ps1",
    ".json", ".xml", ".html", ".properties", ".gitignore", ".yml", ".yaml",
    ".toml", ".gradle", ".pro", ".cfg", ".ini",
}
SKIP_DIRS = {".git", "build", ".gradle", ".idea", ".kotlin", "out", "node_modules"}

B64_RE = re.compile(r"[A-Za-z0-9+/]{16,}={0,2}")
URLENC_RE = re.compile(r"(?:%[0-9A-Fa-f]{2}){3,}")


def iter_files(include_binary=False):
    for p in REPO.rglob("*"):
        if not p.is_file():
            continue
        if any(part in SKIP_DIRS for part in p.parts):
            continue
        if include_binary or p.suffix.lower() in TEXT_EXT or p.name == ".gitignore":
            yield p


def scan_plaintext(paths):
    print("\n=== 1) 明文凭据 / 个人信息 ===")
    hits = 0
    needles = KNOWN_PLAINTEXT + KNOWN_PERSONAL
    for p in paths:
        try:
            text = p.read_text(encoding="utf-8", errors="replace")
        except Exception:
            continue
        for needle in needles:
            if needle in text:
                for i, line in enumerate(text.split("\n"), 1):
                    if needle in line:
                        hits += 1
                        print(f"  🔴 {p.relative_to(REPO)}:{i}  ← '{needle}'")
                        print(f"       {line.strip()[:110]}")
    for pat, label in PERSONAL_PATTERNS:
        for p in paths:
            try:
                text = p.read_text(encoding="utf-8", errors="replace")
            except Exception:
                continue
            for m in re.finditer(pat, text):
                # 拿到命中所在的那一整行，用于上下文过滤
                line_start = text.rfind("\n", 0, m.start()) + 1
                line_end = text.find("\n", m.end())
                line = text[line_start: line_end if line_end >= 0 else len(text)]

                # 假阳性过滤 ①：整行里出现正常技术文本（User-Agent 里的浏览器版本号等）
                if any(marker in line for marker in BENIGN_MARKERS):
                    continue
                # 假阳性过滤 ②：末三段全 0 的「IP」只可能是软件版本号（如 Chrome/124.0.0.0），
                # 不可能是可路由地址。早期只在注释里承诺了这条却忘了实现，导致三次假阳性。
                octets = m.group(0).split(".")
                if len(octets) == 4 and octets[1] == "0" and octets[2] == "0" and octets[3] == "0":
                    continue
                hits += 1
                print(f"  🔴 {p.relative_to(REPO)}  ← {label}: {m.group(0)[:60]}")
                print(f"       {line.strip()[:110]}")
    if hits == 0:
        print("  ✅ 干净")
    return hits


def scan_base64(paths):
    """穷举解码仓库里所有像 base64 的长串，人工看解出来是不是可读文本。"""
    print("\n=== 2) Base64 编码的凭据（穷举解码）===")
    hits = 0
    suspicious = []
    for p in paths:
        try:
            text = p.read_text(encoding="utf-8", errors="replace")
        except Exception:
            continue
        for m in B64_RE.finditer(text):
            s = m.group(0)
            # 补齐 padding
            pad = (-len(s)) % 4
            try:
                raw = base64.b64decode(s + "=" * pad, validate=True)
            except (binascii.Error, ValueError):
                continue
            # 只关心解出来是「可打印 ASCII/UTF-8」的
            try:
                dec = raw.decode("utf-8")
            except UnicodeDecodeError:
                continue
            if not dec or not all(32 <= ord(c) < 127 or c in "\r\n\t" for c in dec):
                continue
            low = dec.lower()
            # 命中已知凭据 = 真泄露
            if any(k.lower() in low for k in KNOWN_PLAINTEXT):
                hits += 1
                print(f"  🔴 {p.relative_to(REPO)}  ← Base64 解码出凭据!")
                print(f"       {s}  ->  {dec!r}")
            elif len(dec) >= 6:
                # 其它可读串记为「待人工确认」，不算泄露
                suspicious.append((str(p.relative_to(REPO)), s, dec))
    if hits == 0:
        print("  ✅ 未发现 Base64 形态的凭据")
    if suspicious:
        print(f"\n  ℹ️ 另有 {len(suspicious)} 个可解码的 base64 串（多为 Room schema 哈希等，人工扫一眼）：")
        for path, s, dec in suspicious[:12]:
            print(f"       {path}: {s[:36]}{'...' if len(s) > 36 else ''} -> {dec[:48]!r}")
        if len(suspicious) > 12:
            print(f"       ...另有 {len(suspicious) - 12} 个")
    return hits


def scan_urlencoded(paths):
    print("\n=== 3) URL 编码 ===")
    hits = 0
    for p in paths:
        try:
            text = p.read_text(encoding="utf-8", errors="replace")
        except Exception:
            continue
        for m in URLENC_RE.finditer(text):
            s = m.group(0)
            if any(h in s for h in PLACEHOLDER_HINTS):
                continue
            try:
                dec = bytes.fromhex(s.replace("%", "")).decode("utf-8")
            except Exception:
                continue
            if any(k.lower() in dec.lower() for k in KNOWN_PLAINTEXT):
                hits += 1
                print(f"  🔴 {p.relative_to(REPO)}  ← URL 编码解码出凭据: {s} -> {dec!r}")
    if hits == 0:
        print("  ✅ 干净")
    return hits


def scan_binary():
    print("\n=== 4) 二进制文件字节级扫描 ===")
    hits = 0
    count = 0
    for p in REPO.rglob("*"):
        if not p.is_file() or any(part in SKIP_DIRS for part in p.parts):
            continue
        if p.suffix.lower() in TEXT_EXT:
            continue
        count += 1
        try:
            data = p.read_bytes()
        except Exception:
            continue
        for needle in KNOWN_PLAINTEXT + KNOWN_PERSONAL:
            if needle.encode() in data:
                hits += 1
                print(f"  🔴 {p.relative_to(REPO)}  ← 二进制中含 '{needle}'")
    print(f"  扫描了 {count} 个二进制文件")
    if hits == 0:
        print("  ✅ 干净")
    return hits


def main():
    include_bin = "--bin" in sys.argv
    paths = list(iter_files())
    print(f"扫描 {len(paths)} 个文本文件（仓库：{REPO}）")
    if KNOWN_PLAINTEXT or KNOWN_PERSONAL:
        print(f"明文比对清单：{len(KNOWN_PLAINTEXT)} 个凭据变体 + {len(KNOWN_PERSONAL)} 个个人信息项")
    else:
        print("⚠️ 未配置 JW_ACCOUNT / JW_PASSWORD —— 跳过明文比对，只做结构性检查")
        print("   （配置方法见本脚本开头注释；不配也能查出 Base64 / Cookie / IP / ticket）")
    total = scan_plaintext(paths) + scan_base64(paths) + scan_urlencoded(paths)
    if include_bin:
        total += scan_binary()
    print("\n" + "=" * 60)
    if total == 0:
        print("✅ 全部通过：未发现任何形态的凭据泄露")
    else:
        print(f"🔴 发现 {total} 处问题，必须修复后才能公开")
    return 0 if total == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
