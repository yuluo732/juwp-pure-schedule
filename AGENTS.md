# AGENTS.md

> 给接手本项目的 AI Agent / 开发者的**约束与已验证结论**。
> 项目：**Pure Schedule / 极简课程表**（Android，抓取江西水利电力大学教务系统课表）
>
> **本文件的价值在于「防止返工」**：下面每一条都是**实际踩过坑之后**写下的，
> 其中若干条是「看起来完全合理、实际已被实测证伪」的做法。
> 修改代码前请通读第二、三节。

---

## 一、项目定位与技术栈（不要随意扩张）

| 项 | 值 |
|---|---|
| 包名 | `com.juwp.schedule` |
| 语言 / UI | Kotlin 2.0.21 + Jetpack Compose（Material 3） |
| 构建 | AGP 8.13.2 + Gradle 9.3.0（wrapper 锁定）+ JDK 17~21 |
| SDK | `minSdk 26` / `targetSdk 36` / `compileSdk 36` |
| 数据 | Room 2.6.1 + DataStore Preferences |
| 网络 | OkHttp 4.12（CAS/SSO 手工跳转）+ Jsoup 1.18.3（HTML 解析） |

### ⚠️ 刻意**不**引入的依赖

**不要为了「架构更漂亮」加 Hilt / Navigation / Retrofit / Koin。**
本项目刻意保持轻依赖：手写 `AppContainer` 做依赖注入，手写 `when (screen)` 做导航。
理由是依赖图小、构建稳定、出问题时容易定位。这是设计决策，不是遗漏。

### 代码风格

- **注释用中文，且写「为什么」而不是「是什么」。** 现有代码都是这个风格。
- 站点相关的 URL 与正则**全部集中在 `data/net/JwUrls.kt`**，改版时只改这一处。
- 布局问题不要靠缩字号掩盖（用户曾明确批评过这种做法）。

---

## 二、⚠️ 三条铁律（违反必然返工）

这三条是**实测验证过的接口/数据事实**，不要重新怀疑或"顺手重构"。

### 铁律 1：登录是 CAS + SSO 跨 4 个 host，必须手动逐跳跟随重定向

```
① GET  https://jiaowu.juwp.edu.cn:81/sso.jsp
② POST https://eapp2.juwp.edu.cn:9443/cas/login?service=...   （execution 需现场解析，不能缓存）
③ 手动跟随：:80/sso.jsp?ticket=… → :80/sso.jsp → :8080/jsxsd/xk/LoginToXk → :8080/jsxsd/framework/xsMainV.htmlx
④ GET  http://jiaowu.juwp.edu.cn:8080/jsxsd/xskb/xskb_list.do?viweType=0
```

`OkHttpClient` **必须设 `followRedirects(false)`**，由 `JwClient.followSso()` 逐跳手动跟随。
跳转横跨 `:81 → :9443 → :80 → :8080`，**自动跟随会在跨协议跳转时丢 Cookie**。

另外：登录页里有 `var encrypt = "false"` → 密码**明文**提交。
若将来变成 `true`，需要改成 AES/ECB/Pkcs7。

### 铁律 2：课表被 iframe 套了一层

`/jsxsd/xskb/xskb_list.do`（**不带参数**）只是外壳，真实数据在 **`?viweType=0`**。

### 铁律 3：一个格子里多门课 = 不同周次，不是并课

实测：多课格子中，**全部**由「不同周次」产生，同一周次真的并存 **0** 次。

两个直接后果：

1. 解析结果必须是「**课程安排列表**」（每条带自己的周次集合），
   **不能按课程名去重合并**。同一门课换教室也要各自成独立记录。
2. **渲染行高必须按「当前这一周」统计**课程数。用全周次数量会把行高撑到 5 倍。

---

## 三、⚠️ 已实测证伪的做法（**别再试**）

### ❌ 不要用 `LocalDensity` 缩放弹窗尺寸

`CompositionLocalProvider(LocalDensity)` **对 `Dialog` 无效** —— 弹窗内容在独立子组合里渲染。

实测证据（把系数调到极端的 0.55，正常一眼能看出差别）：

| 对象 | 期望（若生效） | 实测 |
|---|---|---|
| 日期格子 | ~92×92 px | **168×168 px**（= 48dp × 3.5，原始密度） |
| 选项文字高 | ~31 px | **57 px**（= 14sp × 3.5，原始密度） |

数值**完全不动**。三种放置位置全试过，都无效：
① 包在 `DatePickerDialog` 外；② 包在 `BasicAlertDialog` 外；③ 自己照抄 M3 结构直接调 `Dialog`。

**根因**：`Dialog` 用 `rememberCompositionContext()` 取调用点的组合上下文，内容在各自窗口的**新组合**里渲染。

**结论**：弹窗尺寸只能靠「换更小的组件 / 更小的内边距」来压。
**不要再写 `ScaledDialogDensity` 这类包装，也不要留不生效的常量。**

> 相关事实：M3 的 `DatePickerDialog` 把宽度写死 `requiredWidth(360.dp)`、
> 日期格子写死 `48.dp × 6 行`，**无参数可调**。本机密度 3.5 下 360dp ≈ 整屏宽，
> 这是**无解**的，不要试图"优化"。
>
> 另：`DatePickerModalTokens` 是 **internal**，外部代码不能引用，需要这些数值时写成本地常量。

### ❌ 不要改动课程卡片的配色方案

**「卡片透明化」是透明度功能，不是换主题功能。**

```kotlin
// TimetableGrid.kt — CourseChip（定稿，不要再动）
val cardBg       = pastelOf(base)        // 粉彩底：课程色往白里提
val textColor    = onPastelOf(base)      // 同色系深色字
val subTextColor = textColor
val cardAlpha    = if (translucent) 0.68f else 1f   // ← 透明化【只改这一个值】
```

**两种模式共用同一套配色。** 已被用户明确否掉两次：

| 错误尝试 | 用户反馈 |
|---|---|
| 按 WCAG 对比度把文字**加深**"防发虚" | 「还不如原来的」——文字变灰，失去与卡片的同色系关系 |
| 改成**深色底 + 白字** | 「非常难看」——底色与未开启模式不一致、变深 |
| 按明暗给不同 alpha（深 0.5 / 浅 0.72） | 同样被推翻，会让「开透明化后底色比不开时深」重现 |

若用户觉得 0.68 不合适，**只调 `cardAlpha` 这一个值**。
`readableTextOn()` / `translucentCardBase()` 已被删除，**不要再引入**。

### ❌ 不要用 `Select-String` 过滤 gradle 构建输出

`gradle.bat` 的 stdout/stderr 在 PowerShell 里会交错，过滤后**可能看不到 `BUILD SUCCESSFUL`**，
从而误判「构建失败 / 没产出 APK」。要落盘就用 `*>`，再 `Get-Content` 读文件。

### ❌ 不要把「切走再切回回到本周」当 bug 修

`ScheduleScreen` 的 `rememberPagerState(initialPage = state.currentWeek - 1)`，
切到别的 Tab 时页面被销毁、重建后回到本周。**这是用户确认过的预期行为**
（原话：「切走再切回，自动切回本周不用更改」）。
不要改成「记住上次翻到第几周」。

### ❌ 不要用 PowerShell 的 `-replace` 批量改含反引号的 Markdown

`-replace` 的替换串里，反引号与 `$` 会被 PowerShell 当转义符处理。
本项目**真实发生过**：整份交接文档被改坏成
「反引号 → 字母 `a`」「字母 `a` → `d`」（`` `gradle` `` 变成 `` `grddle` ``、
`` `app` `` 变成 `` `dpp` ``），最后只能整份重写。

**批量改文本一律写 Python 脚本再执行**；需要含反引号的字面量时，
写成脚本文件，也不要走 PowerShell 内联命令。

### ❌ 不要给连堂课卡片直接写死 `rowHeight × rowSpan`

`rememberRowHeights` 返回的是**逐行**高度（不是统一值），且跨行课程的需求
**分摊到它覆盖的每一行**。若改回「整表同一高度」，连堂课卡片会因为
超出所在行的高度而被父级裁掉下半截（`Row` 用 `IntrinsicSize.Min`，
父 `Box` 的可开销高度就是该行高度）。

样例里已有覆盖：`validation/fixtures/sample-timetable.html` 的
**第七八节·周一**是 `rowspan="2"` 的连堂课，并有 3 项对应断言。

### ❌ 不要用「第 N 个 td = 星期 N」定位星期

`ScheduleParser.parseCells` **必须**用 `occupied` 追踪「哪一列已被上方 rowspan 占用」。
HTML 里 rowspan 跨行时，**被跨越的行不会重复给出那个 td**，
于是「本行第 N 个 td」≠「星期 N」，简单按序号算会让**整行左移若干天**
（实测：周二的课 → 周一、周日的课 → 周六，用户会看到课出现在错的日子）。

⚠️ 三个已经踩过的细节：
- 占位计数登记时要用 `rowSpan`（**不是** `rowSpan - 1`）—— 它在行末统一递减一次，
  写 `rowSpan - 1` 会提前归零，等于没修（off-by-one，实测复现过）。
- 断言**必须直接断言被跨越行里各格子的真实星期**。只断言「位置总数」或
  「rowspan 课在它起始行」是无效的：整行错位后两者都不变，**照样全绿**。
  `ParserCheck.kt` 的「列偏移回归」那 3 项就是为此而设。
- 被跨越的行少一个 `<td>` 是**正确形态**，不要"补齐"它。

---

## 四、构建与测试

```bash
./gradlew assembleDebug                    # Windows: gradlew.bat assembleDebug
pwsh -File tools/run-parser-check.ps1      # 解析器断言，必须全绿（当前 49 项）
```

- 改**解析器**或**数据模型**后**必须**跑解析器断言。
- 它用 `validation/fixtures/sample-timetable.html`（**合成数据**，结构与教务系统一致但内容虚构）
  做离线验证，**不需要安卓设备、不需要 Android SDK**，秒级完成。
  首次运行会自动下载 Kotlin 编译器到 `%USERPROFILE%\ktc`。
- 控制台中文在某些终端会显示成乱码，但 ASCII 的 `PASS` / `FAIL` 可靠 ——
  **用 PASS 计数判断结果，不要看乱码就以为失败**。
- ⚠️ 修样例 HTML 时注意 td 数量：
  **没被 rowspan 跨越的行要有 7 个 `<td>`**（周一..周日）；
  **被跨越的行少一个才是对的**（HTML 不会重复给出被跨的格子）。
  解析器用 `occupied` 追踪列号并跳过被占用的列，所以两种情况都能正确处理。
  可用 `python tools/inspect-fixture.py validation/fixtures/sample-timetable.html`
  核对每格内容（该脚本会先剥掉 HTML 注释，否则文件头的说明文字会干扰计数）。

### Gradle 版本说明

wrapper 锁定 **Gradle 9.3.0**，且已实测 `9.3.0 + AGP 8.13.2` 构建成功。
（项目早期文档曾写「不要用 9.x」，那是**未经实测的假设**，已被推翻。）
Gradle 9.3.0 会提示 `Deprecated Gradle features ... incompatible with Gradle 10`，
即将来升 Gradle 10 需要处理这些弃用，**现在不影响使用**。

---

## 五、安全红线

**任何时候都不要把真实学号 / 密码 / 会话 Cookie 写进源码或文档。**
**包括它们的任何编码形态（Base64 / Hex / URL 编码）。**

- `tools/` 下的抓取脚本从 **`tools/_credentials.mjs`** 读取凭据，
  取值顺序：环境变量 `JW_ACCOUNT` / `JW_PASSWORD` → `tools/.env`（已在 `.gitignore` 中）。
- 新增抓取脚本时，一律 `import { ACCOUNT, PASSWORD } from './_credentials.mjs'`。

### ⚠️ 本项目真实发生过的一次泄露（教训）

清理时**只做了明文搜索**，漏掉了 Base64 形态 —— 当时一份内部文档（接口勘察报告）
里贴了「算法自证」的输入输出样例，其中 `Base64(学号)` 与 `Base64(密码)` 就是真实凭据。
明文 grep 全绿，但那串字符复制去解码就是账号密码。
（该文档**未随仓库发布**。这条留在这里是为了提醒：如果你要新增「举例说明算法」
之类的内容，**一律用占位符**，不要写真实值。）

**所以提交前的自查不能只查明文。** 至少覆盖四类形态：

```bash
# 1) 明文（含常见变体）
grep -rniE "学号|password|pwd" --include=* . | grep -viE "占位|占位符|placeholder|<.*>"

# 2) 硬编码赋值
grep -rnE "(ACCOUNT|PASSWORD|USER|PWD)\s*=\s*['\"][^'\"]{4,}['\"]" --include=*.mjs --include=*.kt .

# 3) Base64：把自己知道的凭据编码后搜（这是上次漏掉的那类）
#    例：printf '%s' '<你的学号>' | base64  然后搜结果
#    更稳妥：对仓库里所有长 base64 串做穷举解码，人工过一遍可读结果
grep -rnoE "[A-Za-z0-9+/]{16,}={0,2}" --include=*.md --include=*.mjs . | sort -u

# 4) 其它编码形态
grep -rniE "%[0-9A-F]{2}[0-9A-F]{2}%[0-9A-F]{2}" --include=*.md .   # URL 编码
```

> 💡 **更根本的做法**：不要把自己的真实凭据写进**任何**文档，
> 哪怕是"举例说明算法"。文档里一律用 `<学号>` / `<密码>` 占位符，
> 真实值只存在于 `tools/.env`（已 gitignore）与环境变量中。

### 其它个人信息

同样不要写入：**设备序列号**（`adb devices` 输出里的那串）、**服务器真实 IP**、
**姓名/手机号**、以及 `tools/out/` 下的抓取产物（含 UI dump 与 DataStore 备份）。
本项目历史上这些都曾出现在交接文档里，已清理。

---

## 六、验证 UI 改动的方法（不要靠目测）

改完 UI 必须**在真机上取数**再回复，不要只说「改好了」。

| 手段 | 用途 |
|---|---|
| `adb shell uiautomator dump` | 取控件树与像素 bounds，比截图精确 |
| `adb shell screencap -p /sdcard/x.png` + `adb pull` | 截图。**不要用 `exec-out screencap -p > file`**，PowerShell 重定向会破坏二进制 |
| 像素级比对（PIL 取固定坐标均值） | 判断"颜色是否变了"。**不要用 MD5 比整图**——状态栏每秒都在变 |

### 几个具体坑

1. **`$matches` 在 PowerShell 管道里丢作用域**：
   `[regex]::Matches(...) | ForEach-Object { $matches[1] }` 会报
   `Cannot index into a null array`。改用 Python 脚本解析。
2. **Python 脚本必须设 stdout 编码**：
   `sys.stdout.reconfigure(encoding="utf-8", errors="replace")`，
   否则密码框的 `•`（U+2022）会让脚本在 GBK 控制台下抛 `UnicodeEncodeError` 直接崩。
3. **点按钮必须按文字定位中心**，硬编码坐标会随改版失效。
   但注意同名文字可能出现在说明正文里（如「确定退出」在对话框正文中也出现一次），
   点之前先确认命中的 bounds 在预期区域。
4. **`adb shell input keyevent 4`（返回键）会把 App 直接退到桌面**，
   不要用它关下拉框 / 弹窗。
5. **`adb install` 可能静默卡在手机的安装确认弹窗**（表现为命令长时间无输出）。
   先执行：`adb shell settings put global adb_install_need_confirm 0`
   和 `verifier_verify_adb_installs 0`。
6. **`CompositionLocalProvider(LocalDensity)`**：见第三节，对 Dialog 无效。
7. **带 alpha 的 `Surface` 必须显式给 `contentColor`**：
   透明容器会让 `contentColorFor` 匹配失败、回退成黑色文字（深色模式下就是bug）。

---

## 七、当前状态与待办

**当前版本**：v1.5.0（versionCode 31）。解析器 49 项断言全绿。

### 已知待办（按优先级）

| 优先级 | 事项 |
|---|---|
| P1 | `2025-2026-2` 学期该学期多课格子最多，逐周翻看确认无残留文字截断 |
| P2 | 统一 `term_meta.isSelected` 与 `settings.currentTermId` 两个真相源，建议以 `currentTermId` 为准 |
| P3 | 桌面小组件、单课程备注/自定义颜色、凭据加密存储、调课检测 |

> ~~实现 `rowSpan` 连堂课跨行渲染~~ → **已完成**：`rememberRowHeights` 改为逐行高度、
> 跨行需求分摊到覆盖行；样例新增 rowspan 用例与 3 项断言。详见第三节末条。

### 开发过程文档在哪

本仓库**只保留结论**（就是本文档）。完整的迭代记录（哪一轮改了什么、为什么、
踩了什么坑，共 10 份交接文档 + 接口勘察报告 + 版本史）**未随仓库发布**，
存放在开发者的本地目录里，排查历史问题时另行索取。

这样做是刻意的：那些文档是过程材料（含内部调试细节、已废弃的方案、历史凭据清理记录），
对使用者没有价值，只让仓库显得杂乱。**结论沉淀在本文档与 `README.md` 里就够了。**
