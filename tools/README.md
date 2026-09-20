# tools/ —— 开发与维护工具

这里的脚本分三类：**离线测试**、**接口抓取**、**仓库自查**。
都是给维护者用的，**普通使用者不需要碰**。

> ⚠️ 依赖与网络：抓取类脚本需要 **Node.js 18+**（用了内置 `fetch`），
> 自查类需要 **Python 3.8+**，测试类需要 **JDK**（会自动下载 Kotlin 编译器）。
> 抓取类脚本会把原始 HTML 写到 `tools/out/`（**已 gitignore，不入库**）。

---

## 1. 离线测试（改解析器后必跑）

| 脚本 | 说明 |
|---|---|
| `run-parser-check.ps1` | **最重要**。用 `validation/fixtures/sample-timetable.html` 跑 49 项解析器断言。**不需要安卓设备、不需要 Android SDK**，首次运行会自动下载 Kotlin 编译器到 `%USERPROFILE%\ktc` |
| `inspect-fixture.py` | 列出样例课表**每一行每个格子**的内容与 td 数，用于核对结构。改样例 HTML 时用它自查 |

```powershell
pwsh -File tools/run-parser-check.ps1
python tools/inspect-fixture.py validation/fixtures/sample-timetable.html
```

> 💡 改样例 HTML 时的两个约束：
>
> 1. **没被 rowspan 跨越的行要有 7 个 `<td>`**（周一..周日），否则后面的格子会挪位。
> 2. **被 rowspan 跨越的行本来就少一个 `<td>`**，这是对的 —— HTML 不会重复给出被跨的格子。
>    解析器会追踪列号并跳过被占用的列（见 `ScheduleParser.parseCells` 的 `occupied`），
>    样例第 5 行只有 6 个 td 就是这种情况。
>
> 改完用 `python tools/inspect-fixture.py validation/fixtures/sample-timetable.html` 核对每格内容；
> 再跑 `run-parser-check.ps1`，**「列偏移回归」那 3 项专门守这个坑**。

## 2. 接口抓取（教务系统改版时才需要）

当学校教务系统改版、App 抓不到课表时，用这几个脚本重新勘察。

**先配置凭据**（脚本从环境变量或 `tools/.env` 读取，**不要把凭据写进源码**）：

```powershell
Copy-Item tools\.env.example tools\.env
notepad tools\.env      # 填入学号密码
```

| 脚本 | 说明 |
|---|---|
| `fetch-terms.mjs` | 抓取**多个学期**的课表 HTML 到 `tools/out/terms/`，并输出根因分析。改版时先用它确认「还能不能拿到数据」 |
| `fetch-kb-html.mjs` | 抓取单次课表页 HTML，保存为解析夹具 |
| `parse-schedule.mjs` | 把课表 HTML 解析成结构化 JSON。**同时是 Kotlin 解析逻辑的「黄金参照实现」**——两边结果应一致 |

```powershell
node tools/fetch-terms.mjs 2024-2025-1 2025-2026-1
node tools/parse-schedule.mjs                    # 用默认路径（kb-xskb_list.html）
node tools/parse-schedule.mjs tools/out/xxx.html # 或显式指定 HTML
```

**典型流水线**：`fetch-terms` 拿到 HTML → `parse-schedule` 转成 JSON →
据此改 Kotlin 解析器 → `run-parser-check` 验证（49 项断言，含「一格多课 = 不同周次」的守门断言）。

## 3. 仓库自查

| 脚本 | 说明 |
|---|---|
| `check-leaks.py` | **发布前必跑**。扫描四种形态的凭据泄露：明文 / **Base64 穷举解码** / URL 编码 / 二进制字节级。本项目曾因只查明文而漏掉文档里的 `Base64(学号)`，故有此脚本 |

```powershell
# 不带凭据也能做结构性检查（Cookie/IP/票据形态）
python tools/check-leaks.py --bin

# 带上自己的凭据做精确比对（更彻底）
$env:JW_ACCOUNT="学号"; $env:JW_PASSWORD="密码"; python tools/check-leaks.py --bin
```

脚本**自身不含任何硬编码凭据**（从环境变量读，否则它自己就成了泄露源）。

---

## 凭据约定

所有需要登录的脚本统一从 **`_credentials.mjs`** 取凭据：

```
优先级：环境变量 JW_ACCOUNT / JW_PASSWORD  →  tools/.env 文件
```

`tools/.env` 已在 `.gitignore` 中。**新增抓取脚本请照此办理**，
不要图省事把凭据写进代码 —— 这个仓库是公开的。
