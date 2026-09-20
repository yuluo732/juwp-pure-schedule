package com.juwp.schedule.data.net

/**
 * 江西水利电力大学 教务系统相关地址常量。
 *
 * 全部经实测验证（重新勘察的方法见 AGENTS.md 第二节与 tools/README.md）：
 *
 *  1) 教务系统 SSO 入口（注意：**HTTPS + 81 端口**，且 service 参数指向 **80 端口**）
 *     https://jiaowu.juwp.edu.cn:81/sso.jsp
 *        → Location: https://eapp2.juwp.edu.cn:9443/cas/login?service=http%3A%2F%2Fjiaowu.juwp.edu.cn%2Fsso.jsp
 *  2) 统一身份认证 CAS（Apereo CAS 标准表单，无验证码，密码明文）
 *        POST https://eapp2.juwp.edu.cn:9443/cas/login?service=...
 *        → 302 Location: http://jiaowu.juwp.edu.cn/sso.jsp?ticket=ST-xxxx
 *  3) 带 ticket 回调 → 服务端建会话并跳转 jsxsd
 *        http://jiaowu.juwp.edu.cn/sso.jsp?ticket=...
 *        → http://jiaowu.juwp.edu.cn/sso.jsp
 *        → http://jiaowu.juwp.edu.cn:8080/jsxsd/xk/LoginToXk?method=jwxt&ticket1=xxxx
 *        → http://jiaowu.juwp.edu.cn:8080/jsxsd/framework/xsMainV.htmlx   ← 登录成功
 *  4) 课表（外层壳，内含 iframe）
 *        GET http://jiaowu.juwp.edu.cn:8080/jsxsd/xskb/xskb_list.do
 *  5) 课表真实数据（iframe 内容，整学期一张大表）
 *        GET http://jiaowu.juwp.edu.cn:8080/jsxsd/xskb/xskb_list.do?viweType=0
 *  6) 学期切换（POST 表单 xnxq01id）
 *        POST http://jiaowu.juwp.edu.cn:8080/jsxsd/xskb/xskb_list.do?viweType=0
 *             xnxq01id=2026-2027-1
 */
object JwUrls {

    const val CAS_HOST = "eapp2.juwp.edu.cn"
    const val CAS_PORT = 9443
    const val CAS_BASE = "https://$CAS_HOST:$CAS_PORT"

    /** SSO 入口（HTTPS 81） */
    const val SSO_ENTRY = "https://jiaowu.juwp.edu.cn:81/sso.jsp"

    /** CAS 回跳的 service 地址（HTTP 80，注意不是 81、也不是 8080） */
    const val SSO_SERVICE = "http://jiaowu.juwp.edu.cn/sso.jsp"

    /** jsxsd 主站（cookie bzb_jsxsd 绑定在 /jsxsd 路径下） */
    const val JW_BASE = "http://jiaowu.juwp.edu.cn:8080"

    /** 登录后的学生首页，用来验证会话是否有效 */
    const val JW_HOME = "$JW_BASE/jsxsd/framework/xsMainV.htmlx"

    /** 课表外层壳 */
    const val KB_SHELL = "$JW_BASE/jsxsd/xskb/xskb_list.do"

    /** 课表真实数据（整学期大表） */
    const val KB_DATA = "$JW_BASE/jsxsd/xskb/xskb_list.do?viweType=0"

    const val TERM_PARAM = "xnxq01id"

    /** 判定响应是否为「登录页」：出现该标记说明会话已失效 */
    const val LOGIN_PAGE_MARKER = "id=\"userAccount\""

    /** CAS 登录页里的 execution 隐藏域 */
    val EXECUTION_REGEX = Regex("""name="execution"\s+value="([^"]+)"""")
}

/** 站点相关的正则集中放这里，方便改版时一处修改 */
object JwRegex {

    /** 登录页随机干扰串 */
    val SCODE = Regex("""var\s+scode\s*=\s*"([^"]*)"""")
    val SXH = Regex("""var\s+sxh\s*=\s*"([^"]*)"""")

    /** 节次行：第X节 + 时间，例如「第一二节 08:30~09:55」 */
    val PERIOD_LABEL = Regex("""第[一二三四五六七八九十]+[二三四五六]?节""")
    val PERIOD_TIME = Regex("""\d{1,2}:\d{2}\s*[~\-]\s*\d{1,2}:\d{2}""")

    /** 课程详情里的字段 */
    val TEACHER = Regex("""老师[:：]\s*([^;；]*)""")
    val WEEKS = Regex("""时间[:：]\s*([^;；\[]*)\[""")
    val PERIODS = Regex("""\[([^\]]*?)\]""")
    val ROOM = Regex("""地点[:：]\s*([^;；]*)""")
    val COURSE_CODE = Regex("""课程编号[:：]\s*([^;；\s]*)""")
    val CLASSES = Regex("""班级[:：]\s*([^;；]*)""")
    val STUDENT_COUNT = Regex("""总人数[:：]\s*([^;；\s]*)""")
    val ASSESSMENT = Regex("""考核方式[:：]\s*([^;；\s]*)""")
    val TOTAL_HOURS = Regex("""总学时[:：]\s*([^;；\s]*)""")

    /** 周次串里的一个片段：1-5 或 7 */
    val WEEK_RANGE = Regex("""(\d+)\s*-\s*(\d+)""")
    val WEEK_SINGLE = Regex("""\d+""")
}
