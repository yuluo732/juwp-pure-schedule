package com.juwp.schedule.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * 各安卓厂商的「后台限制」差异很大：原生系统（Pixel 等）什么都不用设，
 * 而国产 ROM 普遍会冻结后台应用、拦掉通知。这里按品牌给出对应设置路径，
 * 并提供「一键跳到系统设置页」——尽量把用户送到需要手动开的那一页。
 */
object ReminderHelp {

    /** 归一化后的品牌名（用于文案展示） */
    fun brandName(): String {
        val m = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        return when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> "小米 / 红米"
            m.contains("huawei") -> "华为"
            m.contains("honor") -> "荣耀"
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") -> "OPPO / 一加 / realme"
            m.contains("vivo") || m.contains("iqoo") -> "vivo / iQOO"
            m.contains("samsung") -> "三星"
            m.contains("meizu") -> "魅族"
            m.contains("google") || m.contains("pixel") -> "Google Pixel"
            else -> "你的手机"
        }
    }

    /** 是否需要用户手动去系统里放行（原生类系统不需要） */
    fun needsManualSetup(): Boolean {
        val m = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        val stock = m.contains("google") || m.contains("pixel") ||
            m.contains("nothing") || m.contains("lineage")
        return !stock
    }

    /** 该品牌需要手动开的项目（按顺序展示给用户） */
    fun steps(): List<String> = when {
        brandName().startsWith("vivo") -> listOf(
            "应用信息 → 「暂停闲置应用的活动」关掉（它会停掉通知）",
            "应用信息 → 允许「自启动」「关联启动」「后台运行」",
            "设置 → 电池 → 后台耗电管理 → 允许「完全后台行为」",
            "最近任务里给本 App 加锁（下拉卡片点锁图标）：划掉卡片会把闹钟一起取消",
        )
        brandName().startsWith("小米") -> listOf(
            "应用信息 → 「自启动」打开",
            "应用信息 → 省电策略 → 选「无限制」",
            "最近任务里给本 App 加锁（下拉卡片点锁图标）：划掉卡片会把闹钟一起取消",
        )
        brandName() == "华为" || brandName() == "荣耀" -> listOf(
            "应用启动管理 → 关闭「自动管理」，手动勾选允许自启动/关联启动/后台活动",
            "电池 → 更多电池设置 → 关闭「智能省电」对本应用的优化",
            "最近任务里给本 App 加锁（下拉卡片点锁图标），避免被一键清理",
        )
        brandName().startsWith("OPPO") -> listOf(
            "应用信息 → 允许「自启动」「后台运行」",
            "电池 → 应用耗电管理 → 允许「完全后台行为」",
            "最近任务里给本 App 加锁（下拉卡片点锁图标）",
        )
        brandName() == "三星" -> listOf(
            "设置 → 电池 → 后台使用限制 → 把本应用加入「永不进入休眠的应用」",
        )
        brandName().startsWith("Google") -> listOf(
            "原生系统只需允许「通知」即可，无需其它设置",
        )
        else -> listOf(
            "在应用信息 / 电池设置里允许本应用「自启动」和「后台运行」",
            "把省电策略设为「无限制」或加入电池优化白名单",
        )
    }

    /**
     * 尽力跳到「能放行后台」的系统页面：先试各家的自启动/权限管理页，失败则退回应用详情页。
     * @return true 表示至少跳到了某个系统页面
     */
    fun openBackgroundSettings(context: Context): Boolean {
        val pkg = context.packageName
        val candidates = listOf(
            // 通用：应用详情页（几乎所有 ROM 都有）
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")),
            // 通用：电池优化白名单页
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            // 小米 / 红米 自启动管理
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity").toIntent(),
            // 华为 / 荣耀 应用启动管理
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity").toIntent(),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity").toIntent(),
            // OPPO / 一加 / realme 自启动
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity").toIntent(),
            ComponentName("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity").toIntent(),
            // vivo / iQOO 后台高耗电 / 自启动
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity").toIntent(),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity").toIntent(),
            // 魅族
            ComponentName("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity").toIntent(),
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ok = runCatching { context.startActivity(intent); true }
                .getOrElse { false }
            if (ok) return true
            Log.d("ReminderHelp", "跳转失败，尝试下一个：$intent")
        }
        return false
    }

    private fun ComponentName.toIntent() = Intent().setComponent(this)
}
