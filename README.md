<div align="center">

# Pure Schedule · 极简课程表

**从江西水利电力大学教务系统直接读取课表的安卓 App**
**An Android app that reads your class schedule straight from the JUWP academic system**

[![Platform](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84?logo=android&logoColor=white)](#环境要求--requirements)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Version](https://img.shields.io/badge/version-1.5.0-blue.svg)](../../releases)

</div>

---

> ## ⚠️ 适用学校 / Supported school
>
> **本 App 只适配江西水利电力大学（JUWP）的教务系统。**
> 教务地址、统一身份认证跳转链路、课表 HTML 结构全部是按这一所学校硬编码的，
> **其他学校的账号无法登录**。
>
> **This app only works with Jiangxi University of Water Resources and Electric Power (JUWP).**
> Endpoints, the CAS/SSO redirect chain, and the schedule HTML structure are all hard-coded
> for this one institution — **accounts from other schools will not work.**

---

## 简体中文

### 这是什么

一个**不需要打开浏览器**就能看课表的安卓 App。它用你的学号密码登录学校统一身份认证，
自动抓取整学期课表并缓存在本机，之后**离线也能看**。

### 为什么做它

市面上常见的课程表 App 有两个通病：**臃肿**（塞进社交、二手交易、校园墙、资讯流）
和**广告多**（开屏广告、信息流广告、营销推送）。查一节课的时间，不该被这些东西消耗掉。

所以这个 App 刻意只做一件事：

| | |
|---|---|
| **没有广告** | 一行广告 SDK 都没有；没有开屏广告、没有信息流、没有营销推送 |
| **没有多余功能** | 不做社交、不做商城、不做资讯。功能表就是下面那张，没有隐藏项 |
| **没有服务器** | 没有后端、不收集任何数据，账号只发给学校自己的认证服务器 |
| **没有重依赖** | 不引入 Hilt / Navigation / Retrofit，依赖图小、构建稳定、行为可预期 |
| **不多要权限** | 只要网络、通知、闹钟、开机自启——都是「看课表 + 提醒」必需的 |

如果你想要的正是「打开就看、看完就关」，这个 App 就是按这个标准做的。

### 功能

| | |
|---|---|
| **今日页** | 打开就是今天要上的课，显示第几教学周 |
| **周课表** | 左右滑动切周，当前周与今天高亮 |
| **自动选学期** | 按日期自动切到当前学期，不用每学期手动改 |
| **离线可看** | 课表缓存在本机，没网也能查看 |
| **上课提醒** | 提前 N 分钟通知，可设 5～60 分钟 |
| **个性化** | 六种主题色、深浅模式、自定义背景图 |
| **强制竖屏** | 课表是 7 列网格，横屏下每列太窄没法看，故锁竖屏 |

### 权限用途 / Permissions

| 权限 | 用途 |
|---|---|
| `INTERNET` / `ACCESS_NETWORK_STATE` | 登录教务系统、抓课表 |
| `POST_NOTIFICATIONS` | 上课提醒弹通知 |
| `SCHEDULE_EXACT_ALARM` | 让提醒准时；未授权时自动降级为不精确闹钟 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 国产 ROM 杀后台会导致提醒不响，仅用于引导你去系统设置放行 |
| `RECEIVE_BOOT_COMPLETED` | 重启后重新排课表提醒 |

**没有**读取通讯录、位置、相册、设备标识等权限。

### 环境要求 / Requirements

- Android 8.0（API 26）及以上
- 手机能访问学校教务系统（公网可直连，**不需要校园网 VPN**）

> 💡 **不要开代理软件**。已知 Clash 等工具的 TUN 模式会把学校域名劫持到
> 虚拟网段导致连不上。如果提示「无法连接教务系统」，先关掉代理。

### 安装

到 [Releases](../../releases) 页面下载 APK，传到手机点击安装。
手机可能提示「未知来源应用」，允许即可（App 不在应用商店，属正常）。

详细图文步骤见 [`docs/user-guide.md`](docs/user-guide.md)。

### 自己编译

```bash
git clone https://github.com/yuluo732/pure-schedule.git
cd pure-schedule
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

需要 **JDK 17+** 与 **Android SDK（platform 36）**。
Gradle 版本由 wrapper 锁定（9.3.0），不需要手动装。

#### 打正式发布包（可选）

`assembleRelease` 开了 R8 混淆与资源压缩，产物只有 debug 包的约 **1/9**（1.9 MB vs 17.7 MB）。
但对外发布需要**你自己的签名密钥**：

```bash
# 1) 生成密钥（一条命令，密码自己设，务必记住并备份）
keytool -genkeypair -v -keystore my-release.jks -alias my-alias \
  -keyalg RSA -keysize 2048 -validity 10000

# 2) 配置（模板见 keystore.properties.example）
cp keystore.properties.example keystore.properties
# 编辑 keystore.properties 填入上面设的密码

# 3) 打包
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

> `keystore.properties` 与 `*.jks` **已被 `.gitignore` 排除**，不会误传。
> 如果**没有**配密钥，`assembleRelease` 依然能构建成功，但会退回 **debug 签名** ——
> 那样的包不适合发布（换机器后签名会变，用户无法覆盖升级）。

⚠️ **签名密钥务必多处备份。** 密钥丢失后无法为已安装的版本发布覆盖升级包，
用户只能卸载重装（本机课表缓存与设置会一起丢失）。

### 工作原理（简述）

```
学号密码
   ↓
CAS 统一身份认证（跨 4 个 host/端口，必须手动逐跳跟随重定向，否则跨协议会丢 Cookie）
   ↓
SSO 建立教务系统会话
   ↓
GET /jsxsd/xskb/xskb_list.do?viweType=0   ← 少了 ?viweType=0 只能拿到空壳 iframe
   ↓
Jsoup 解析 HTML → Room 落库 → Compose 渲染
```

登录链路为什么必须这样写、课表该抓哪个地址，见 [`AGENTS.md`](AGENTS.md) 第二节「三条铁律」。

### 项目结构

```
app/src/main/java/com/juwp/schedule/
├── data/
│   ├── net/          登录链路（JwUrls 集中所有 URL 与正则）
│   ├── parse/        课表 HTML 解析器
│   ├── db/           Room 实体 / DAO
│   ├── prefs/        DataStore 设置
│   └── repo/         编排「登录 → 抓取 → 解析 → 落库」
├── domain/           教学周计算、学期匹配（纯逻辑，可离线测试）
├── reminder/         AlarmManager 排程与通知
└── ui/               Compose 界面（今日 / 周课表 / 设置）

validation/ParserCheck.kt   49 项解析器断言（用合成的样例课表，不需要安卓设备）
validation/fixtures/        合成样例课表 HTML（结构与教务系统一致、内容虚构）
tools/                      开发与维护工具（用法见 tools/README.md）
```

---

## English

### What is this

An Android app that shows your class schedule **without opening a browser**.
It signs in to the university's central authentication with your student ID, fetches the
whole semester's timetable, caches it locally, and works **offline** afterwards.

The motivation is simple: the academic system is painful on a phone — logging in, waiting
for pages, and reading a timetable squeezed onto a small screen. This app does one thing
and does it well.

### Features

| | |
|---|---|
| **Today** | Opens straight to today's classes, with the current teaching week |
| **Week view** | Swipe between weeks; today and the current week are highlighted |
| **Auto term** | Picks the current term by date — no manual switching each semester |
| **Offline** | The timetable is cached locally and viewable without a network |
| **Reminders** | Class notifications 5–60 minutes ahead |
| **Theming** | Six accent colours, light/dark mode, custom background image |
| **Few dependencies** | No Hilt / Navigation / Retrofit — a small, stable dependency graph |

### How it works (brief)

```
student ID + password
   ↓
CAS central authentication (spans 4 host/port combinations; redirects must be followed
manually hop-by-hop, or cookies are dropped on cross-protocol jumps)
   ↓
SSO establishes the academic-system session
   ↓
GET /jsxsd/xskb/xskb_list.do?viweType=0   ← without ?viweType=0 you only get an empty iframe shell
   ↓
Parse HTML with Jsoup → persist to Room → render with Compose
```

See section 2 ("Three iron rules") of [`AGENTS.md`](AGENTS.md) for why the login chain must be written this way and which URL actually returns the timetable.

### Build

```bash
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
```

Requires **JDK 17+** and **Android SDK (platform 36)**. The Gradle version is pinned by the
wrapper (9.3.0).

### Privacy / 隐私

Your credentials are stored **only on your device** (DataStore) and are sent solely to the
university's own authentication server. There is **no backend server** — the app talks
directly to the school's system, and no data leaves your phone.

账号密码**只保存在你自己手机里**，只发给学校自己的认证服务器。
这个 App **没有服务器**，所有数据都在本机。

---

## 已知限制 / Known limitations

| 限制 | 说明 |
|---|---|
| 仅支持一所学校 | 见顶部声明。其他学校需要改写 `data/net/JwUrls.kt` 与解析器 |
| 「选择开学日期」弹窗较宽 | 安卓官方 `DatePicker` 把宽度写死为 360dp，本机密度下约等于整屏宽，**官方未开放缩小参数**，无法调整 |
| 强制竖屏 | 课表是 7 列网格，横屏下每列不足 1/10 屏宽，暂不适配 |
| 仅调试签名 | Releases 里的 APK 使用 debug 签名，长期使用建议自行配置正式签名 |

## 参与贡献 / Contributing

欢迎提 Issue 反馈问题。改动代码前请先读 **[`AGENTS.md`](AGENTS.md)** ——
那份文档记录了本项目**已经验证过的结论**和**踩过的坑**，
能帮你避开大量返工（例如「弹窗尺寸无法用密度缩放」这类已实测证伪的做法）。

Found a bug? Open an issue. Before changing code, please read **[`AGENTS.md`](AGENTS.md)** —
it records what has already been verified and which approaches were tried and **failed**,
so you don't repeat them.

## 许可证 / License

[MIT](LICENSE) © 2026 Pure Schedule contributors

---

<div align="center">
<sub>

**免责声明**：本项目为个人学习用途的非官方工具，与江西水利电力大学无隶属关系。
使用时请遵守学校相关规定。

**Disclaimer**: An unofficial tool for personal use, not affiliated with JUWP.
Please comply with your institution's policies when using it.

</sub>
</div>
