package tech.loveace.appv3.util

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import tech.loveace.appv3.data.model.ScheduleCourse
import tech.loveace.appv3.data.model.ScheduleTimePlace
import java.io.File
import java.io.FileOutputStream

/**
 * 课程表图片导出 — Windows 11 Mica 风格
 *
 * 参考 Satori 版本的设计语言：
 * - 渐变 Mica 背景 + 毛玻璃面板
 * - 左侧彩色边框条的课程卡片
 * - 多课程同时段并排显示
 * - Emoji 前缀的详情信息
 */
object ScheduleImageExporter {

    // ── Layout ──
    private const val IMG_W = 1260
    private const val PAD = 32
    private const val TIME_W = 78
    private val DAY_W = (IMG_W - PAD * 2 - TIME_W) / 7
    private const val ROW_H = 108
    private const val HDR_H = 52
    private const val TITLE_AREA_H = 90
    private const val FOOTER_H = 48
    private const val BLOCK_R = 7f

    private val SESSION_TIMES = mapOf(
        1 to "08:00", 2 to "08:50", 3 to "10:00", 4 to "10:50",
        5 to "14:00", 6 to "14:50", 7 to "16:00", 8 to "16:50",
        9 to "19:00", 10 to "19:50", 11 to "20:40", 12 to "21:30",
    )
    private val WEEKDAYS = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    // ── Mica 风格配色 ──
    private val BG_START = Color.parseColor("#E3EEFC")
    private val BG_END = Color.parseColor("#F3F8FF")
    private val PANEL_BG = Color.argb(128, 255, 255, 255)       // 半透明白色面板
    private val PANEL_BORDER = Color.argb(153, 255, 255, 255)   // 面板边框
    private val HDR_TEXT = Color.parseColor("#1A1A1A")
    private val TEXT_PRI = Color.parseColor("#1A1A1A")
    private val TEXT_SEC = Color.parseColor("#5D5D5D")
    private val TEXT_HINT = Color.parseColor("#A0A0A0")
    private val GRID_LINE = Color.argb(10, 0, 0, 0)             // 极淡网格线
    private val GRID_LINE_STRONG = Color.argb(15, 0, 0, 0)      // 稍深分隔线
    private val TIME_COL_BG = Color.argb(4, 0, 0, 0)            // 时间列淡底色

    // ── 课程颜色池（与参考一致） ──
    private data class CC(val bg: Int, val border: Int, val text: Int)

    private val CCOLORS = listOf(
        CC(Color.parseColor("#D1E7F7"), Color.argb(128, 0, 120, 212), Color.parseColor("#0063B1")),
        CC(Color.parseColor("#D4E8D4"), Color.argb(128, 16, 124, 16), Color.parseColor("#0E6E0E")),
        CC(Color.parseColor("#E4D5E7"), Color.argb(128, 136, 23, 152), Color.parseColor("#6B1076")),
        CC(Color.parseColor("#F0DFD2"), Color.argb(128, 202, 80, 16), Color.parseColor("#A34D0A")),
        CC(Color.parseColor("#D1DEF0"), Color.argb(128, 0, 99, 177), Color.parseColor("#004E8C")),
        CC(Color.parseColor("#F4D1E5"), Color.argb(128, 227, 0, 140), Color.parseColor("#B3006E")),
        CC(Color.parseColor("#D1E4E1"), Color.argb(128, 0, 133, 117), Color.parseColor("#006A5D")),
        CC(Color.parseColor("#E4DCD5"), Color.argb(128, 142, 86, 46), Color.parseColor("#6E4224")),
        CC(Color.parseColor("#E1E0E0"), Color.argb(128, 76, 74, 72), Color.parseColor("#3B3A39")),
        CC(Color.parseColor("#D1E7EF"), Color.argb(128, 0, 153, 188), Color.parseColor("#007A96")),
    )

    private data class Cell(
        val courseName: String,
        val location: String,
        val teacher: String,
        val weekDesc: String,
        val rowSpan: Int,
        val colorIndex: Int,
    )

    // ════════════════════════════════════════
    // Public API
    // ════════════════════════════════════════

    fun exportScheduleImage(
        context: Context,
        courses: List<ScheduleCourse>,
        termName: String,
        courseCount: Int,
        totalUnits: Double,
    ): Result<String> {
        return try {
            val grid = buildGrid(courses)
            val maxSession = getMaxSession(grid)
            val bitmap = render(grid, maxSession, termName, courseCount, totalUnits)
            try {
                val safeName = termName.replace(Regex("[/\\\\:*?\"<>|]"), "_")
                val fileName = "课程表_${safeName}_${System.currentTimeMillis()}.png"
                Result.success(saveBitmap(context, bitmap, fileName))
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }


    // ════════════════════════════════════════
    // Grid Building（与参考代码逻辑一致，合并同课程不同周数）
    // ════════════════════════════════════════

    private fun buildGrid(courses: List<ScheduleCourse>): Map<String, List<Cell>> {
        val colorMap = mutableMapOf<String, Int>()
        var ci = 0
        data class E(val c: ScheduleCourse, val tp: ScheduleTimePlace)
        val tmp = mutableMapOf<String, MutableList<E>>()

        for (c in courses) {
            colorMap.getOrPut(c.courseCode) { ci++ % CCOLORS.size }
            for (tp in c.timeAndPlaceList) {
                tmp.getOrPut("${tp.classDay}-${tp.classSessions}") { mutableListOf() }.add(E(c, tp))
            }
        }

        return tmp.mapValues { (_, entries) ->
            data class GK(val code: String, val room: String, val span: Int)
            val grouped = mutableMapOf<GK, MutableList<String>>()
            val first = mutableMapOf<GK, E>()
            for (e in entries) {
                val gk = GK(e.c.courseCode, e.tp.classroomName, e.tp.continuingSession)
                grouped.getOrPut(gk) { mutableListOf() }.add(e.tp.weekDescription)
                first.putIfAbsent(gk, e)
            }
            grouped.map { (gk, weeks) ->
                val e = first[gk]!!
                val merged = weeks.distinct().let {
                    if (it.size == 1) it[0]
                    else it.joinToString(",").let { j -> if (j.length > 15) "${it[0]}等" else j }
                }
                Cell(e.c.courseName, e.tp.classroomName, e.c.attendClassTeacher, merged, gk.span, colorMap[e.c.courseCode] ?: 0)
            }
        }
    }

    private fun getMaxSession(grid: Map<String, List<Cell>>): Int {
        var max = 0
        for ((key, cells) in grid) {
            val s = key.substringAfter("-").toIntOrNull() ?: continue
            for (cell in cells) max = maxOf(max, s + cell.rowSpan - 1)
        }
        return max.coerceIn(8, 12)
    }

    /** 检查某格是否被上方课程覆盖 */
    private fun isCoveredByAbove(grid: Map<String, List<Cell>>, day: Int, session: Int): Boolean {
        for (s in (session - 1) downTo 1) {
            val cells = grid["$day-$s"] ?: continue
            if (cells.any { s + it.rowSpan > session }) return true
        }
        return false
    }


    // ════════════════════════════════════════
    // Canvas Rendering — Mica 风格
    // ════════════════════════════════════════

    private fun render(
        grid: Map<String, List<Cell>>, maxSession: Int,
        termName: String, courseCount: Int, totalUnits: Double,
    ): Bitmap {
        val gridH = maxSession * ROW_H
        val tableTop = PAD + TITLE_AREA_H + 20
        val imgH = tableTop + HDR_H + gridH + FOOTER_H + PAD
        val bmp = Bitmap.createBitmap(IMG_W, imgH, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)

        // 1. Mica 渐变背景
        val bgPaint = Paint()
        bgPaint.shader = LinearGradient(
            0f, 0f, IMG_W.toFloat(), imgH.toFloat(),
            BG_START, BG_END, Shader.TileMode.CLAMP,
        )
        cv.drawRect(0f, 0f, IMG_W.toFloat(), imgH.toFloat(), bgPaint)

        // 2. 右上角光晕
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        glowPaint.shader = RadialGradient(
            IMG_W - 60f, -60f, 200f,
            Color.argb(26, 0, 120, 212), Color.argb(0, 0, 120, 212),
            Shader.TileMode.CLAMP,
        )
        cv.drawCircle(IMG_W - 60f, -60f, 200f, glowPaint)

        val gridLeft = PAD + TIME_W
        val bodyTop = tableTop + HDR_H

        drawTitle(cv, termName)
        drawTablePanel(cv, tableTop, gridH)
        drawHeader(cv, gridLeft, tableTop)
        drawGridLines(cv, gridLeft, tableTop, maxSession)
        drawTimeLabels(cv, bodyTop, maxSession)
        drawCourses(cv, grid, gridLeft, bodyTop, maxSession)
        drawFooter(cv, imgH - PAD + 4, courseCount, totalUnits)

        return bmp
    }

    /** 标题区域 — 图标 + 标题 + 副标题 */
    private fun drawTitle(cv: Canvas, termName: String) {
        val iconSize = 56f
        val iconLeft = PAD.toFloat()
        val iconTop = PAD.toFloat()

        // 图标背景圆角矩形
        val iconRect = RectF(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        iconPaint.shader = LinearGradient(
            iconRect.left, iconRect.top, iconRect.right, iconRect.bottom,
            Color.parseColor("#0078D4"), Color.parseColor("#106EBE"),
            Shader.TileMode.CLAMP,
        )
        // 图标阴影
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        shadowPaint.setShadowLayer(12f, 0f, 4f, Color.argb(64, 0, 120, 212))
        shadowPaint.color = Color.TRANSPARENT
        cv.drawRoundRect(iconRect, 12f, 12f, shadowPaint)
        cv.drawRoundRect(iconRect, 12f, 12f, iconPaint)

        // 图标文字（用 🎓 的替代：简单画一个学位帽符号）
        val iconTextP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 28f; textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        cv.drawText("📋", iconRect.centerX(), iconRect.centerY() + 10f, iconTextP)

        // 标题
        val titleLeft = iconLeft + iconSize + 16f
        val titleP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1C1C1C"); textSize = 30f; letterSpacing = -0.02f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        cv.drawText("课程表", titleLeft, iconTop + 28f, titleP)

        // 副标题
        val subP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_SEC; textSize = 18f; letterSpacing = 0.02f
        }
        cv.drawText(termName, titleLeft, iconTop + 52f, subP)
    }


    /** 毛玻璃面板背景 */
    private fun drawTablePanel(cv: Canvas, tableTop: Int, gridH: Int) {
        val panelRect = RectF(
            PAD.toFloat(), tableTop.toFloat(),
            (IMG_W - PAD).toFloat(), (tableTop + HDR_H + gridH).toFloat(),
        )
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = PANEL_BG
        cv.drawRoundRect(panelRect, 12f, 12f, p)
        // 面板边框
        p.color = PANEL_BORDER
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f
        cv.drawRoundRect(panelRect, 12f, 12f, p)
        p.style = Paint.Style.FILL
    }

    /** 表头 — 节次 + 周一~周日 */
    private fun drawHeader(cv: Canvas, left: Int, top: Int) {
        // 节次列
        val hdrP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_HINT; textSize = 16f; textAlign = Paint.Align.CENTER
        }
        cv.drawText("节次", PAD + TIME_W / 2f, top + HDR_H / 2f + 6f, hdrP)

        // 分隔线
        val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GRID_LINE_STRONG; strokeWidth = 1f
        }
        cv.drawLine(PAD.toFloat(), (top + HDR_H).toFloat(), (IMG_W - PAD).toFloat(), (top + HDR_H).toFloat(), lineP)

        // 星期
        val dayP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = HDR_TEXT; textSize = 19f; textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        for (d in 0..6) {
            val cx = left + d * DAY_W + DAY_W / 2f
            cv.drawText(WEEKDAYS[d], cx, top + HDR_H / 2f + 7f, dayP)
        }
    }

    /** 网格线 — 极淡风格 */
    private fun drawGridLines(cv: Canvas, left: Int, top: Int, maxSession: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GRID_LINE; strokeWidth = 1f
        }
        val bodyTop = top + HDR_H
        val bottom = bodyTop + maxSession * ROW_H

        // 水平线
        for (s in 1 until maxSession) {
            val y = (bodyTop + s * ROW_H).toFloat()
            cv.drawLine(PAD.toFloat(), y, (IMG_W - PAD).toFloat(), y, p)
        }

        // 时间列右侧分隔线
        val timeLineP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GRID_LINE_STRONG; strokeWidth = 1f
        }
        cv.drawLine(left.toFloat(), top.toFloat(), left.toFloat(), bottom.toFloat(), timeLineP)

        // 垂直列分隔线
        for (d in 1..6) {
            val x = (left + d * DAY_W).toFloat()
            cv.drawLine(x, (top + HDR_H).toFloat(), x, bottom.toFloat(), p)
        }
    }

    /** 时间列 — 节次数字 + 时间 */
    private fun drawTimeLabels(cv: Canvas, top: Int, maxSession: Int) {
        // 时间列淡底色
        val bgP = Paint().apply { color = TIME_COL_BG }
        cv.drawRect(
            PAD.toFloat(), top.toFloat(),
            (PAD + TIME_W).toFloat(), (top + maxSession * ROW_H).toFloat(), bgP,
        )

        val cx = PAD + TIME_W / 2f
        val numP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_PRI; textSize = 22f; textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val timeP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_HINT; textSize = 13f; textAlign = Paint.Align.CENTER
        }
        for (s in 1..maxSession) {
            val cy = top + (s - 1) * ROW_H + ROW_H / 2f
            cv.drawText("$s", cx, cy - 6f, numP)
            SESSION_TIMES[s]?.let { cv.drawText(it, cx, cy + 14f, timeP) }
        }
    }


    /** 绘制所有课程 — 支持多课程并排 */
    private fun drawCourses(
        cv: Canvas, grid: Map<String, List<Cell>>,
        left: Int, top: Int, maxSession: Int,
    ) {
        for (day in 1..7) {
            for (s in 1..maxSession) {
                if (isCoveredByAbove(grid, day, s)) continue
                val cells = grid["$day-$s"] ?: continue
                if (cells.isEmpty()) continue

                val x = left + (day - 1) * DAY_W
                val y = top + (s - 1) * ROW_H
                val cellCount = cells.size
                val innerPad = 3
                val availableW = DAY_W - innerPad * 2
                val gap = if (cellCount > 1) 3 else 0
                val eachW = (availableW - gap * (cellCount - 1)) / cellCount

                for ((idx, cell) in cells.withIndex()) {
                    val blockX = x + innerPad + idx * (eachW + gap)
                    val blockH = cell.rowSpan * ROW_H
                    drawBlock(cv, cell, blockX, y + innerPad, eachW, blockH - innerPad * 2)
                }
            }
        }
    }

    /** 单个课程块 — 左侧彩色边框 + 信息 */
    private fun drawBlock(cv: Canvas, cell: Cell, x: Int, y: Int, w: Int, h: Int) {
        val cc = CCOLORS[cell.colorIndex % CCOLORS.size]
        val r = RectF(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // 背景
        p.color = cc.bg
        cv.drawRoundRect(r, BLOCK_R, BLOCK_R, p)

        // 左侧彩色边框条（3px 宽）
        val barRect = RectF(r.left, r.top + 2f, r.left + 4.5f, r.bottom - 2f)
        p.color = cc.border
        cv.drawRoundRect(barRect, 2f, 2f, p)

        // 文字区域
        val tl = r.left + 10f
        val tw = (r.right - 6f - tl).toInt().coerceAtLeast(10)
        var ty = r.top + 5f

        // 课程名
        val nameP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cc.text; textSize = 15f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val maxNameLines = when {
            cell.rowSpan >= 3 -> 3
            cell.rowSpan >= 2 -> 2
            else -> 2
        }
        val nameLayout = StaticLayout.Builder
            .obtain(cell.courseName, 0, cell.courseName.length, nameP, tw)
            .setMaxLines(maxNameLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setLineSpacing(1f, 1.1f)
            .build()
        cv.save(); cv.translate(tl, ty); nameLayout.draw(cv); cv.restore()
        ty += nameLayout.height + 3f

        // 详情信息（带 emoji 前缀）
        val detailP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#555555"); textSize = 12f
        }

        // 📍 地点
        if (cell.location.isNotBlank() && ty < r.bottom - 16f) {
            val locText = "📍 ${cell.location}"
            cv.drawText(trunc(detailP, locText, tw.toFloat()), tl, ty + 12f, detailP)
            ty += 17f
        }

        // 📅 周次（rowSpan >= 2 时显示）
        if (cell.rowSpan >= 2 && cell.weekDesc.isNotBlank() && ty < r.bottom - 16f) {
            detailP.color = Color.parseColor("#666666")
            val weekText = "📅 ${cell.weekDesc}"
            cv.drawText(trunc(detailP, weekText, tw.toFloat()), tl, ty + 12f, detailP)
            ty += 17f
        }

        // 👤 教师（rowSpan >= 2 时显示）
        if (cell.rowSpan >= 2 && cell.teacher.isNotBlank() && ty < r.bottom - 16f) {
            detailP.color = Color.parseColor("#777777")
            val teacherText = "👤 ${cell.teacher}"
            cv.drawText(trunc(detailP, teacherText, tw.toFloat()), tl, ty + 12f, detailP)
        }
    }

    private fun trunc(p: Paint, text: String, maxW: Float): String {
        if (p.measureText(text) <= maxW) return text
        for (i in text.length downTo 1) {
            val t = text.substring(0, i) + "…"
            if (p.measureText(t) <= maxW) return t
        }
        return "…"
    }

    /** 底部信息 */
    private fun drawFooter(cv: Canvas, y: Int, courseCount: Int, totalUnits: Double) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_HINT; textSize = 15f; textAlign = Paint.Align.CENTER
        }.let { cv.drawText("LoveACE · 共 $totalUnits 学分 · ${courseCount} 门课", IMG_W / 2f, y.toFloat(), it) }
    }


    // ════════════════════════════════════════
    // Save to gallery
    // ════════════════════════════════════════

    private fun saveBitmap(context: Context, bitmap: Bitmap, fileName: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/LoveACE")
            }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values,
            ) ?: throw Exception("无法创建图片文件")
            context.contentResolver.openOutputStream(uri)?.use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            } ?: throw Exception("无法写入图片")
            return "已保存到 相册/LoveACE/$fileName"
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "LoveACE",
            )
            dir.mkdirs()
            val file = File(dir, fileName)
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png"), null)
            return "已保存到 ${file.absolutePath}"
        }
    }
}
