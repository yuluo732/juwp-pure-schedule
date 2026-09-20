package com.juwp.schedule.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** 一条课程安排（已按「课程名+周次+节次+教室」拆开） */
@Entity(
    tableName = "course_arrangement",
    indices = [Index("termId"), Index(value = ["uniqueKey", "termId"], unique = true)],
)
data class CourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val termId: String,
    val uniqueKey: String,
    val name: String,
    val teachers: String,
    val room: String,
    val courseCode: String,
    val classes: String,
    val studentCount: String,
    val assessment: String,
    val totalHours: String,
    val weeksRaw: String,
    /** 周次集合，逗号分隔，例如 "1,2,4,5" */
    val weeksCsv: String,
    val periodRaw: String,
    /** 节次集合，逗号分隔 */
    val periodsCsv: String,
    val weekday: Int,
    val periodRowIndex: Int,
    val rowSpan: Int,
) {
    val weeks: Set<Int> get() = weeksCsv.split(',').mapNotNull { it.toIntOrNull() }.toSet()
    val periods: Set<Int> get() = periodsCsv.split(',').mapNotNull { it.toIntOrNull() }.toSet()
}

/** 节次行定义（每个学期一套） */
@Entity(tableName = "period_info", primaryKeys = ["termId", "rowIndex"])
data class PeriodEntity(
    val termId: String,
    val rowIndex: Int,
    val label: String,
    val timeRange: String,
)

/** 学期元信息 */
@Entity(tableName = "term_meta")
data class TermMetaEntity(
    @PrimaryKey val termId: String,
    val termName: String,
    val isSelected: Boolean,
    val fetchedAt: Long,
)

@Dao
interface ScheduleDao {

    @Query("SELECT * FROM course_arrangement WHERE termId = :termId ORDER BY weekday, periodRowIndex")
    fun observeCourses(termId: String): Flow<List<CourseEntity>>

    @Query("SELECT * FROM course_arrangement WHERE termId = :termId ORDER BY weekday, periodRowIndex")
    suspend fun courses(termId: String): List<CourseEntity>

    @Query("SELECT * FROM period_info WHERE termId = :termId ORDER BY rowIndex")
    fun observePeriods(termId: String): Flow<List<PeriodEntity>>

    @Query("SELECT * FROM period_info WHERE termId = :termId ORDER BY rowIndex")
    suspend fun periods(termId: String): List<PeriodEntity>

    @Query("SELECT * FROM term_meta ORDER BY termId DESC")
    fun observeTerms(): Flow<List<TermMetaEntity>>

    @Query("SELECT * FROM term_meta WHERE isSelected = 1 LIMIT 1")
    suspend fun selectedTerm(): TermMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourses(courses: List<CourseEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPeriods(periods: List<PeriodEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTerms(terms: List<TermMetaEntity>)

    @Query("DELETE FROM course_arrangement WHERE termId = :termId")
    suspend fun deleteCourses(termId: String)

    @Query("DELETE FROM period_info WHERE termId = :termId")
    suspend fun deletePeriods(termId: String)

    /**
     * 该学期是否成功同步过。
     * 用 period_info 的行数判定（每个学期同步后都会写入节次行，
     * 空学期也会写一行占位），**不能用课程数** —— 空学期本来就没有课程。
     */
    @Query("SELECT COUNT(*) > 0 FROM period_info WHERE termId = :termId")
    suspend fun isTermSynced(termId: String): Boolean

    @Query("DELETE FROM term_meta")
    suspend fun clearTerms()

    @Query("DELETE FROM course_arrangement")
    suspend fun clearAllCourses()

    @Query("DELETE FROM period_info")
    suspend fun clearAllPeriods()

    /**
     * 整学期覆盖写入，保证旧数据不残留（只清理本学期的数据，其它学期缓存保留）。
     *
     * ⚠️ 关键：**即使课表为空也要写入 [periods] 占位行**。
     * 早期实现在空学期时不写任何行，导致「该学期已同步、但确实没有课」和
     * 「该学期从未同步」两种情况无法区分，界面会把空学期误报成「还没有课表数据」，
     * 并且继续显示上一个学期的课表（选中学期与显示内容不一致）。
     *
     * @param termId 本次同步的学期（空课表时 [courses] 和 [periods] 都可能为空，
     *   不能再从它们里推断学期 id）
     */
    @Transaction
    suspend fun replaceTerm(
        courses: List<CourseEntity>,
        periods: List<PeriodEntity>,
        terms: List<TermMetaEntity>,
        termId: String? = null,
    ) {
        val id = termId
            ?: courses.firstOrNull()?.termId
            ?: periods.firstOrNull()?.termId
        if (id != null) {
            deleteCourses(id)
            deletePeriods(id)
        }
        if (courses.isNotEmpty()) insertCourses(courses)
        // 空课表：写一行占位，标记「该学期已同步过且确实没有课」
        val periodsToWrite = if (periods.isEmpty() && id != null) {
            listOf(
                PeriodEntity(
                    termId = id,
                    rowIndex = ScheduleDatabase.EMPTY_TERM_MARKER_ROW,
                    label = "",
                    timeRange = "",
                )
            )
        } else {
            periods
        }
        if (periodsToWrite.isNotEmpty()) insertPeriods(periodsToWrite)
        if (terms.isNotEmpty()) {
            clearTerms()
            insertTerms(terms)
        }
    }

    @Transaction
    suspend fun clearEverything() {
        clearAllCourses()
        clearAllPeriods()
        clearTerms()
    }
}

@Database(
    entities = [CourseEntity::class, PeriodEntity::class, TermMetaEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class ScheduleDatabase : RoomDatabase() {
    abstract fun scheduleDao(): ScheduleDao

    companion object {
        /**
         * 空课表学期的占位行号。
         * 正常节次行号是 0..n，用一个负数行号作为「已同步但无课」的标记，
         * 读取时必须过滤掉（见 [buildScheduleFromCache]）。
         */
        const val EMPTY_TERM_MARKER_ROW = -1

        fun build(context: Context): ScheduleDatabase =
            Room.databaseBuilder(context, ScheduleDatabase::class.java, "schedule.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
