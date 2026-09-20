# Pure Schedule / 极简课程表 —— R8 / ProGuard 规则
#
# 为什么这个文件几乎是空的：
#   本项目的 release 构建开了 `isMinifyEnabled` + `isShrinkResources`，
#   但**没有引入任何需要额外 keep 规则的库**：
#     · OkHttp / Jsoup 自带 consumer rules（无需手写）
#     · Room 通过 KSP 生成代码，AndroidX 的 consumer rules 已覆盖
#     · Compose 由 AGP 与 compose 编译器插件自动处理
#   四大组件（Activity / Receiver ×2 / Application）由 AGP 依据 Manifest 自动 keep，
#   实测 R8 的 `configuration.txt` 已包含它们，所以确实不需要手写。
#
# ⚠️ 但这个文件**必须存在**：`build.gradle.kts` 的 `proguardFiles` 引用了它，
#   缺文件时 release 构建会报：
#     `Supplied proguard configuration does not exist`
#   （此前仓库里就缺它 —— debug 构建不受影响，所以一直没被发现。）
#
# 如果将来引入需要反射的库，把规则加在这里，并**实测 release 包**再提交。
