# 用独立 kotlinc 编译并运行「课表解析器」离线验证（不需要安卓设备、不需要 Android SDK）。
#
#   pwsh -File tools/run-parser-check.ps1
#
# 为什么单独做这套离线验证：
#   解析器是纯 Kotlin 逻辑（Kotlin 源码 + Jsoup），完全可以脱离 Android 运行。
#   用一份固定的样例 HTML 跑 49 项断言，改解析器时几秒内就知道有没有改坏，
#   比装 APK 到手机上肉眼验证快得多、也可靠得多。
#
# 样例 HTML 是**合成数据**（validation/fixtures/sample-timetable.html），
# 结构与教务系统一致但内容为虚构 —— 真实课表含个人信息，不适合放进公开仓库。
#
# 依赖（Kotlin 编译器 + 库）会自动下载到 %USERPROFILE%\ktc，首次运行约需一两分钟。

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$ktc = Join-Path $env:USERPROFILE 'ktc'
$v = '2.0.21'
New-Item -ItemType Directory -Force -Path $ktc | Out-Null

# 依赖清单：Kotlin 编译器本体 + stdlib/reflect + 解析器用到的 jsoup
$deps = [ordered]@{
    "kotlin-compiler-$v.jar"              = "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-compiler/$v/kotlin-compiler-$v.jar"
    "kotlin-stdlib-$v.jar"                = "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/$v/kotlin-stdlib-$v.jar"
    "kotlin-reflect-$v.jar"               = "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-reflect/$v/kotlin-reflect-$v.jar"
    "kotlin-script-runtime-$v.jar"        = "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-script-runtime/$v/kotlin-script-runtime-$v.jar"
    "kotlin-daemon-embeddable-$v.jar"     = "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-daemon-embeddable/$v/kotlin-daemon-embeddable-$v.jar"
    "trove4j-1.0.20200330.jar"            = "https://repo1.maven.org/maven2/org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar"
    "annotations-13.0.jar"                = "https://repo1.maven.org/maven2/org/jetbrains/annotations/13.0/annotations-13.0.jar"
    # ⚠️ kotlin-compiler 自身需要 coroutines：缺了会抛
    #    NoClassDefFoundError: kotlinx/coroutines/CoroutineScope（编译阶段就崩）
    "kotlinx-coroutines-core-jvm-1.8.0.jar" = "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.0/kotlinx-coroutines-core-jvm-1.8.0.jar"
    "jsoup-1.18.3.jar"                    = "https://repo1.maven.org/maven2/org/jsoup/jsoup/1.18.3/jsoup-1.18.3.jar"
}

$missing = @()
foreach ($name in $deps.Keys) {
    $dest = Join-Path $ktc $name
    if (-not (Test-Path $dest)) { $missing += $name }
}

if ($missing.Count -gt 0) {
    Write-Host '=== 首次运行：下载 Kotlin 编译器与依赖 ===' -ForegroundColor Cyan
    foreach ($name in $missing) {
        $dest = Join-Path $ktc $name
        Write-Host "  下载 $name ..." -NoNewline
        try {
            Invoke-WebRequest -Uri $deps[$name] -OutFile $dest -UseBasicParsing
            Write-Host ' 完成' -ForegroundColor Green
        } catch {
            Write-Host " 失败：$($_.Exception.Message)" -ForegroundColor Red
            Write-Host "`n可手动下载后放到：$ktc" -ForegroundColor Yellow
            exit 1
        }
    }
}

$jarPaths = $deps.Keys | ForEach-Object { Join-Path $ktc $_ }
$compilerCp = ($jarPaths | Where-Object { $_ -notmatch 'jsoup' }) -join ';'
$stdlib = Join-Path $ktc "kotlin-stdlib-$v.jar"
$jsoup = Join-Path $ktc 'jsoup-1.18.3.jar'

$out = Join-Path $root 'validation\out'
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out | Out-Null

# 只编译解析所需的源文件 + 验证程序（不碰 Compose / Room / 安卓部分）
$sources = @(
    'app\src\main\java\com\juwp\schedule\data\model\ScheduleModels.kt',
    'app\src\main\java\com\juwp\schedule\data\net\JwUrls.kt',
    'app\src\main\java\com\juwp\schedule\domain\TermMatcher.kt',
    'app\src\main\java\com\juwp\schedule\data\parse\ScheduleParser.kt',
    'validation\ParserCheck.kt'
)

Write-Host ''
Write-Host '=== 编译 ===' -ForegroundColor Cyan
java -cp $compilerCp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -nowarn `
    -jvm-target 1.8 `
    -cp "$stdlib;$jsoup" `
    -d $out @sources
if ($LASTEXITCODE -ne 0) { Write-Host '编译失败' -ForegroundColor Red; exit $LASTEXITCODE }

$fixture = 'validation\fixtures\sample-timetable.html'
if (-not (Test-Path $fixture)) {
    Write-Host "缺少样例数据：$fixture" -ForegroundColor Red
    exit 1
}

Write-Host ''
Write-Host '=== 运行验证 ===' -ForegroundColor Cyan
Write-Host '（控制台中文若显示为乱码属终端编码问题，请以 PASS / FAIL 计数为准）'
# -D 参数必须整体加引号：PowerShell 会把 -Dfile.encoding=UTF-8 拆成两个参数传给 java
java "-Dfile.encoding=UTF-8" -cp "$out;$stdlib;$jsoup" validate.ParserCheckKt $fixture
exit $LASTEXITCODE
