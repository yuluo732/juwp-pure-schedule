package com.juwp.schedule.domain

import java.time.LocalDate

/**
 * 学期与日期的匹配。
 *
 * 教务系统的学期 id 形如 `2026-2027-1`，含义是「2026-2027 学年第 1 学期」。
 * 国内高校普遍按这个规则划分：
 *
 *  - **第 1 学期（秋季）**：9 月 ~ 次年 1 月 → 学年起始年的 9 月所在自然年
 *    例：2026-09-01 ~ 2027-01-31 ⇒ `2026-2027-1`
 *  - **第 2 学期（春季）**：2 月 ~ 8 月 → **上一自然年**是学年起始年
 *    例：2027-02-01 ~ 2027-08-31 ⇒ `2026-2027-2`
 *
 * 之所以第 2 学期要把年份减 1，是因为春季学期在教务系统里属于**上一个学年**的
 * 后半段（2026-2027 学年的第 2 学期就是 2027 年春天）。
 */
object TermMatcher {

    private val TERM_ID = Regex("""^(\d{4})-(\d{4})-([12])$""")

    /**
     * 推导 [date] 应该对应哪个学期 id。
     * 返回值形如 `2026-2027-1`；调用方拿它去 [terms] 里匹配。
     */
    fun termIdFor(date: LocalDate): String {
        val year = date.year
        val month = date.monthValue
        return if (month >= 9) {
            // 秋季学期：本年是学年起始年
            "$year-${year + 1}-1"
        } else if (month == 1) {
            // 1 月仍属于上一年 9 月开始的秋季学期
            "${year - 1}-$year-1"
        } else {
            // 2~8 月：春季学期，属于「上一年开学的那个学年」
            "${year - 1}-$year-2"
        }
    }

    /**
     * 在一堆候选学期 id 里挑出与 [date] 匹配的那个。
     *
     * 匹配策略（按优先级）：
     *  1. 精确等于 [termIdFor] 推导结果；
     *  2. 否则取「学年起始年相同」的学期里 id 最大的（应对教务系统写法学年略有出入）；
     *  3. 都不匹配返回 null。
     */
    fun pickTerm(terms: List<String>, date: LocalDate = LocalDate.now()): String? {
        if (terms.isEmpty()) return null
        val want = termIdFor(date)
        terms.firstOrNull { it.trim() == want }?.let { return it.trim() }

        val wantStartYear = want.substringBefore('-').toIntOrNull() ?: return null
        val sameYear = terms.mapNotNull { id ->
            val m = TERM_ID.find(id.trim()) ?: return@mapNotNull null
            if (m.groupValues[1].toIntOrNull() == wantStartYear) id.trim() else null
        }
        return sameYear.maxOrNull()
    }

    /** 学期 id 的学年起始年，形如 `2026-2027-1` → 2026；解析失败返回 null */
    fun startYearOf(termId: String): Int? =
        TERM_ID.find(termId.trim())?.groupValues?.get(1)?.toIntOrNull()

    /** 学期是否形如合法的 `YYYY-YYYY-N` */
    fun isValidTermId(termId: String): Boolean = TERM_ID.matches(termId.trim())
}
