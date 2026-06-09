package tech.loveace.appv3.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import tech.loveace.appv3.data.model.*
import java.io.File
import java.io.FileOutputStream

object CsvExporter {

    /** CSV 转义：含逗号/引号/换行的字段用双引号包裹 */
    private fun escape(value: String): String {
        return if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            "\"${value.replace("\"", "\"\"")}\""
        } else value
    }

    private fun buildCsv(headers: List<String>, rows: List<List<String>>): String {
        val sb = StringBuilder()
        sb.append('\uFEFF') // BOM for Excel Chinese support
        sb.appendLine(headers.joinToString(",") { escape(it) })
        rows.forEach { row ->
            sb.appendLine(row.joinToString(",") { escape(it) })
        }
        return sb.toString()
    }

    // ── 学期成绩 ──

    fun exportTermScores(context: Context, scores: List<ScoreRecord>, termId: String): Result<String> {
        val headers = listOf("序号", "学期ID", "课程代码", "课程班级", "课程名称(中文)", "课程名称(英文)",
            "学分", "学时", "课程性质", "考试性质", "成绩", "重修成绩", "补考成绩")
        val rows = scores.map { s ->
            listOf(s.sequence.toString(), s.termId, s.courseCode, s.courseClass,
                s.courseNameCn, s.courseNameEn, s.credits, s.hours.toString(),
                s.courseType ?: "", s.examType ?: "", s.score,
                s.retakeScore ?: "", s.makeupScore ?: "")
        }
        val csv = buildCsv(headers, rows)
        val fileName = "学期成绩_${termId}_${System.currentTimeMillis()}.csv"
        return saveAndOpen(context, csv, fileName)
    }

    // ── 爱安财 ──

    fun exportAACScores(context: Context, categories: List<AACCreditCategory>): Result<String> {
        val headers = listOf("类别ID", "类别名称", "类别总分", "项目ID", "项目标题", "项目类型", "用户编号", "得分", "添加时间")
        val rows = mutableListOf<List<String>>()
        for (cat in categories) {
            if (cat.children.isEmpty()) {
                rows.add(listOf(cat.id, cat.typeName, cat.totalScore.toString(),
                    "", "", "", "", "", ""))
            } else {
                for (item in cat.children) {
                    rows.add(listOf(cat.id, cat.typeName, cat.totalScore.toString(),
                        item.id, item.title, item.typeName, item.userNo,
                        item.score.toString(), item.addTime))
                }
            }
        }
        val csv = buildCsv(headers, rows)
        val fileName = "爱安财详细分数_${System.currentTimeMillis()}.csv"
        return saveAndOpen(context, csv, fileName)
    }

    // ── 培养方案 ──

    fun exportPlanCompletion(context: Context, planInfo: PlanCompletionInfo): Result<String> {
        val headers = listOf("类别ID", "类别名称", "最低学分", "已修学分", "完成率(%)",
            "总课程数", "已通过课程数", "未通过课程数", "缺失必修课数", "是否完成", "状态描述",
            "课程代码", "课程名称", "是否通过", "学分", "成绩", "考试日期", "课程类型", "状态说明")
        val rows = mutableListOf<List<String>>()

        fun addCategory(cat: PlanCategory) {
            if (cat.courses.isEmpty()) {
                rows.add(listOf(cat.categoryId, cat.categoryName,
                    cat.minCredits.toString(), cat.completedCredits.toString(),
                    "%.1f".format(cat.completionPercentage),
                    cat.totalCourses.toString(), cat.passedCourses.toString(),
                    cat.failedCourses.toString(), cat.missingRequiredCourses.toString(),
                    if (cat.isCompleted) "是" else "否", "",
                    "", "", "", "", "", "", "", ""))
            } else {
                for (course in cat.courses) {
                    rows.add(listOf(cat.categoryId, cat.categoryName,
                        cat.minCredits.toString(), cat.completedCredits.toString(),
                        "%.1f".format(cat.completionPercentage),
                        cat.totalCourses.toString(), cat.passedCourses.toString(),
                        cat.failedCourses.toString(), cat.missingRequiredCourses.toString(),
                        if (cat.isCompleted) "是" else "否", "",
                        course.courseCode, course.courseName,
                        if (course.isPassed) "是" else "否",
                        course.credits?.toString() ?: "", course.score ?: "",
                        course.examDate ?: "", course.courseType, course.statusDescription))
                }
            }
            cat.subcategories.forEach { addCategory(it) }
        }

        planInfo.categories.forEach { addCategory(it) }
        val csv = buildCsv(headers, rows)
        val fileName = "培养方案_${planInfo.major}_${planInfo.grade}_${System.currentTimeMillis()}.csv"
        return saveAndOpen(context, csv, fileName)
    }

    // ── 保存并打开 ──

    private fun saveAndOpen(context: Context, csv: String, fileName: String): Result<String> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+: MediaStore
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
                ) ?: return Result.failure(Exception("无法创建文件"))
                context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
                // Open file
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "text/csv")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) { /* no app to open csv */ }
                Result.success(fileName)
            } else {
                // Android 9 and below
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                val file = File(dir, fileName)
                FileOutputStream(file).use { it.write(csv.toByteArray(Charsets.UTF_8)) }
                try {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "text/csv")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) { /* no app to open csv */ }
                Result.success(fileName)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
