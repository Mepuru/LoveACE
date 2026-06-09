package tech.loveace.appv3.data.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import tech.loveace.appv3.data.model.*
import tech.loveace.appv3.data.network.AUFEConnection

/**
 * 培养方案完成情况服务
 * 解析 zTree 数据，支持多培养方案选择，自动从学期成绩补充通过状态
 */
class PlanService(private val connection: AUFEConnection) {

    private val jwcService by lazy { JWCService(connection) }
    private var planCache = mutableMapOf<String?, PlanCompletionInfo>()
    private var cacheTimestamps = mutableMapOf<String?, Long>()
    /** 多方案选项缓存，getPlanCompletion(null) 检测到多方案时自动填充 */
    var cachedOptions: List<PlanOption> = emptyList()
        private set

    private fun isCacheValid(planId: String?): Boolean {
        val ts = cacheTimestamps[planId] ?: return false
        return System.currentTimeMillis() - ts < CACHE_VALID_MS
    }

    fun clearCache() {
        planCache.clear()
        cacheTimestamps.clear()
    }

    suspend fun getPlanOptions(): UniResponse<PlanSelectionResponse> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/student/integratedQuery/planCompletion/index"
            val response = connection.client.get(url)
            val html = response.body?.string() ?: throw Exception("响应为空")
            val selection = parsePlanSelectionHtml(html)
            if (selection != null && selection.options.isNotEmpty()) {
                UniResponse.success(selection)
            } else {
                UniResponse.success(PlanSelectionResponse(hint = "无需选择培养方案"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getPlanOptions failed", e)
            UniResponse.failure(e.message ?: "获取培养方案选项失败", retryable = true)
        }
    }

    suspend fun getPlanCompletion(
        planId: String? = null,
        forceRefresh: Boolean = false,
    ): UniResponse<PlanCompletionInfo> = withContext(Dispatchers.IO) {
        if (!forceRefresh && isCacheValid(planId) && planCache.containsKey(planId)) {
            return@withContext UniResponse.success(planCache[planId]!!, message = "培养方案获取成功（缓存）")
        }
        try {
            val url = if (!planId.isNullOrEmpty()) {
                "$BASE_URL/student/integratedQuery/planCompletion/getPyfaIndex/$planId"
            } else {
                "$BASE_URL/student/integratedQuery/planCompletion/index"
            }
            val response = connection.client.get(url)
            val html = response.body?.string() ?: throw Exception("响应为空")

            // 检查是否是多方案选择页面（仅当未指定 planId 时）
            if (planId == null) {
                val selection = parsePlanSelectionHtml(html)
                if (selection != null && selection.options.isNotEmpty()) {
                    cachedOptions = selection.options
                    return@withContext UniResponse.failure("MULTI_PLAN", retryable = false)
                }
            }

            var planInfo = parseHtml(html)

            // 如果所有课程都未通过，尝试从学期成绩补充
            if (planInfo.passedCourses == 0 && planInfo.totalCourses > 0) {
                Log.w(TAG, "所有课程未通过，尝试从学期成绩匹配...")
                planInfo = enrichWithTermScores(planInfo)
            }

            planCache[planId] = planInfo
            cacheTimestamps[planId] = System.currentTimeMillis()
            UniResponse.success(planInfo)
        } catch (e: Exception) {
            Log.e(TAG, "getPlanCompletion failed", e)
            UniResponse.failure(e.message ?: "获取培养方案失败", retryable = true)
        }
    }

    // ==================== HTML Parsing ====================

    private fun parsePlanSelectionHtml(html: String): PlanSelectionResponse? {
        try {
            val doc = Jsoup.parse(html)
            val buttons = doc.select("button.btn-success.btn-round")
            if (buttons.isEmpty()) return null

            val hint = doc.selectFirst(".alert-warning")?.text()?.trim()
                ?.replace(Regex("\\s+"), " ")

            val options = buttons.mapNotNull { button ->
                val onclick = button.attr("onclick")
                val text = button.text().trim()
                val match = Regex("getPyfaIndex\\('(\\d+)'\\)").find(onclick) ?: return@mapNotNull null
                val id = match.groupValues[1]
                val type = when {
                    text.contains("辅修") -> "辅修"
                    text.contains("微专业") -> "微专业"
                    else -> "主修"
                }
                PlanOption(id, text, type, button.hasClass("btn-success"))
            }
            if (options.isEmpty()) return null
            return PlanSelectionResponse(options, hint)
        } catch (e: Exception) {
            Log.w(TAG, "parsePlanSelectionHtml failed", e)
            return null
        }
    }

    private fun parseHtml(html: String): PlanCompletionInfo {
        val doc = Jsoup.parse(html)

        // 提取方案名称、专业、年级
        var planName = ""
        var major = ""
        var grade = ""
        for (h4 in doc.select("h4.widget-title")) {
            val text = h4.text().trim()
            if (text.contains("培养方案")) {
                planName = text
                val m = Regex("(\\d{4})级(.+?)本科培养方案").find(text)
                if (m != null) {
                    grade = m.groupValues[1]
                    major = m.groupValues[2]
                }
                break
            }
        }

        // 从 script 中提取 zTree 数据
        val ztreeNodes = extractZTreeNodes(html)
        val categories = buildCategoryTree(ztreeNodes)
        return calculateStatistics(PlanCompletionInfo(planName, major, grade, categories))
    }

    private fun extractZTreeNodes(html: String): List<JsonObject> {
        val patterns = listOf(
            Regex("""\$\.fn\.zTree\.init\s*\(\s*\$\(\s*["']#treeDemo["']\s*\)\s*,\s*\w+\s*,\s*(\[[\s\S]*?])\s*\)"""),
            Regex("""\.zTree\.init\s*\([^,]+,\s*[^,]+,\s*(\[[\s\S]*?])\s*\)"""),
            Regex("""init\s*\(\s*\$\(\s*["']#treeDemo["']\s*\)[^,]*,\s*[^,]*,\s*(\[[\s\S]*?])"""),
        )
        val doc = Jsoup.parse(html)
        for (script in doc.select("script")) {
            val content = script.data()
            if (!content.contains("zTree.init") || !content.contains("flagId")) continue
            for (pattern in patterns) {
                val match = pattern.find(content) ?: continue
                var jsonStr = match.groupValues[1]
                    .replace(Regex("//.*?\n"), "")
                    .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
                    .replace(Regex(",\\s*([}\\]])"), "$1")
                    .replace(Regex("\\s+"), " ").trim()
                try {
                    val arr = Json.parseToJsonElement(jsonStr).jsonArray
                    if (arr.isNotEmpty()) return arr.map { it.jsonObject }
                } catch (_: Exception) { continue }
            }
        }
        throw Exception("未找到有效的zTree数据")
    }

    private fun buildCategoryTree(nodes: List<JsonObject>): List<PlanCategory> {
        val nodesById = mutableMapOf<String, JsonObject>()
        for (node in nodes) {
            val id = node["id"]?.jsonPrimitive?.contentOrNull ?: continue
            nodesById[id] = node
        }
        return nodes.filter { (it["pId"]?.jsonPrimitive?.contentOrNull ?: "") == "-1" }
            .filter { (it["flagType"]?.jsonPrimitive?.contentOrNull ?: "") != "kch" }
            .map { buildCategoryWithChildren(it, nodesById) }
    }

    private fun buildCategoryWithChildren(node: JsonObject, nodesById: Map<String, JsonObject>): PlanCategory {
        val category = parseCategoryFromNode(node)
        val categoryId = node["id"]?.jsonPrimitive?.contentOrNull ?: ""
        val subcategories = mutableListOf<PlanCategory>()
        val courses = mutableListOf<PlanCourse>()

        for (child in nodesById.values) {
            val childPId = child["pId"]?.jsonPrimitive?.contentOrNull ?: ""
            if (childPId != categoryId) continue
            val childFlagType = child["flagType"]?.jsonPrimitive?.contentOrNull ?: ""
            val childId = child["id"]?.jsonPrimitive?.contentOrNull ?: ""

            when {
                childFlagType == "kch" -> courses.add(parseCourseFromNode(child))
                childFlagType == "001" || childFlagType == "002" ->
                    subcategories.add(buildCategoryWithChildren(child, nodesById))
                else -> {
                    val hasChildren = nodesById.values.any { (it["pId"]?.jsonPrimitive?.contentOrNull ?: "") == childId }
                    if (hasChildren) subcategories.add(buildCategoryWithChildren(child, nodesById))
                    else courses.add(parseCourseFromNode(child))
                }
            }
        }
        return category.copy(subcategories = subcategories, courses = courses)
    }

    private fun parseCategoryFromNode(node: JsonObject): PlanCategory {
        val name = (node["name"]?.jsonPrimitive?.contentOrNull ?: "")
            .replace(Regex("<[^>]*>"), "").replace("&nbsp;", " ").trim()
        val flagId = node["flagId"]?.jsonPrimitive?.contentOrNull ?: ""
        val statsMatch = Regex(
            """([^(]+)\(最低修读学分:([0-9.]+),通过学分:([0-9.]+),已修课程门数:(\d+),已及格课程门数:(\d+),未及格课程门数:(\d+),必修课缺修门数:(\d+)\)"""
        ).find(name)
        return if (statsMatch != null) {
            PlanCategory(
                categoryId = flagId,
                categoryName = statsMatch.groupValues[1].trim(),
                minCredits = statsMatch.groupValues[2].toDouble(),
                completedCredits = statsMatch.groupValues[3].toDouble(),
                totalCourses = statsMatch.groupValues[4].toInt(),
                passedCourses = statsMatch.groupValues[5].toInt(),
                failedCourses = statsMatch.groupValues[6].toInt(),
                missingRequiredCourses = statsMatch.groupValues[7].toInt(),
            )
        } else {
            PlanCategory(categoryId = flagId, categoryName = name)
        }
    }

    private fun parseCourseFromNode(node: JsonObject): PlanCourse {
        val name = node["name"]?.jsonPrimitive?.contentOrNull ?: ""
        val isPassed = name.contains("fa-smile-o fa-1x green")
        val statusDesc = when {
            name.contains("fa-smile-o fa-1x green") -> "已通过"
            name.contains("fa-frown-o fa-1x red") -> "未通过"
            else -> "未修读"
        }
        val clean = name.replace(Regex("<[^>]*>"), "").replace("&nbsp;", " ").trim()
        var courseCode = ""
        var courseName = ""
        var credits: Double? = null
        var score: String? = null
        var examDate: String? = null
        var courseType = ""

        val codeMatch = Regex("\\[([^]]+)]").find(clean)
        if (codeMatch != null) {
            courseCode = codeMatch.groupValues[1]
            var remaining = clean.substring(codeMatch.range.last + 1).trim()
            val creditMatch = Regex("\\[([0-9.]+)学分]").find(remaining)
            if (creditMatch != null) {
                credits = creditMatch.groupValues[1].toDoubleOrNull()
                remaining = remaining.replaceFirst(creditMatch.value, "").trim()
            }
            val parenMatch = Regex("\\(([^)]+)\\)").find(remaining)
            if (parenMatch != null) {
                val parenContent = parenMatch.groupValues[1]
                courseName = remaining.substring(0, parenMatch.range.first).trim()
                score = Regex("([0-9.]+)").find(parenContent)?.groupValues?.get(1)
                examDate = Regex("(\\d{8})").find(parenContent)?.groupValues?.get(1)
                if (parenContent.contains(",")) courseType = parenContent.split(",")[0].trim()
            } else {
                courseName = remaining
            }
        }
        return PlanCourse(courseCode, courseName, credits, score, examDate, courseType, isPassed, statusDesc)
    }

    // ==================== Statistics & Enrichment ====================

    private fun calculateStatistics(info: PlanCompletionInfo): PlanCompletionInfo {
        // 从课程列表遍历统计（用于 passedCourses / failedCourses / unreadCourses）
        var total = 0; var passed = 0; var failed = 0; var unread = 0
        fun count(cats: List<PlanCategory>) {
            for (cat in cats) {
                for (c in cat.courses) {
                    total++
                    when {
                        c.isPassed -> passed++
                        c.statusDescription == "未通过" -> failed++
                        else -> unread++
                    }
                }
                count(cat.subcategories)
            }
        }
        count(info.categories)

        // 从叶子分类节点统计（已修课程门数 / 已及格 / 未及格 / 缺修）
        var leafTotalCourses = 0; var leafPassed = 0; var leafFailed = 0; var leafMissing = 0
        fun leafCount(cats: List<PlanCategory>) {
            for (cat in cats) {
                if (cat.subcategories.isEmpty()) {
                    leafTotalCourses += cat.totalCourses
                    leafPassed += cat.passedCourses
                    leafFailed += cat.failedCourses
                    leafMissing += cat.missingRequiredCourses
                } else {
                    leafCount(cat.subcategories)
                }
            }
        }
        leafCount(info.categories)

        // 优先使用叶子节点统计（更准确），如果为 0 则回退到课程列表遍历
        val finalTotal = if (leafTotalCourses > 0) leafTotalCourses else total
        val finalPassed = if (leafPassed > 0) leafPassed else passed
        val finalFailed = if (leafTotalCourses > 0) leafFailed else failed
        val finalUnread = if (leafTotalCourses > 0) (leafTotalCourses - leafPassed - leafFailed).coerceAtLeast(0) else unread

        val estCredits = estimateGraduationCredits(info.categories)
        return info.copy(
            totalCategories = countCategories(info.categories),
            totalCourses = finalTotal, passedCourses = finalPassed,
            failedCourses = finalFailed, unreadCourses = finalUnread,
            missingRequiredCourses = leafMissing,
            estimatedGraduationCredits = estCredits,
        )
    }

    private fun countCategories(cats: List<PlanCategory>): Int =
        cats.size + cats.sumOf { countCategories(it.subcategories) }

    private fun estimateGraduationCredits(cats: List<PlanCategory>): Double {
        var total = 0.0
        fun findLeaf(cat: PlanCategory) {
            if (cat.subcategories.isEmpty()) total += cat.minCredits
            else cat.subcategories.forEach { findLeaf(it) }
        }
        cats.forEach { findLeaf(it) }
        return total
    }

    private suspend fun enrichWithTermScores(planInfo: PlanCompletionInfo): PlanCompletionInfo {
        try {
            val termResp = jwcService.getAllTerms()
            if (!termResp.success || termResp.data.isNullOrEmpty()) return planInfo

            // 获取所有学期成绩
            val allScores = mutableListOf<ScoreRecord>()
            for (term in termResp.data) {
                val scoreResp = jwcService.getTermScore(term.termCode)
                if (scoreResp.success && scoreResp.data != null) {
                    allScores.addAll(scoreResp.data.records)
                }
            }
            if (allScores.isEmpty()) return planInfo

            // 构建课程代码 -> 最佳成绩映射
            val scoreMap = mutableMapOf<String, ScoreRecord>()
            for (s in allScores) {
                val existing = scoreMap[s.courseCode]
                if (existing == null || compareScores(s, existing) > 0) {
                    scoreMap[s.courseCode] = s
                }
            }

            val updatedCats = updateCategoriesWithScores(planInfo.categories, scoreMap)
            return calculateStatistics(planInfo.copy(categories = updatedCats))
        } catch (e: Exception) {
            Log.e(TAG, "enrichWithTermScores failed", e)
            return planInfo
        }
    }

    private fun updateCategoriesWithScores(
        categories: List<PlanCategory>,
        scoreMap: Map<String, ScoreRecord>,
    ): List<PlanCategory> = categories.map { cat ->
        val updatedCourses = cat.courses.map { course ->
            val record = scoreMap[course.courseCode] ?: return@map course
            val effectiveScore = getEffectiveScore(record)
            val passed = isPassingGrade(effectiveScore)
            course.copy(
                score = effectiveScore,
                credits = course.credits ?: record.credits.toDoubleOrNull(),
                isPassed = passed,
                statusDescription = if (passed) "已通过" else "未通过",
            )
        }
        val updatedSubs = updateCategoriesWithScores(cat.subcategories, scoreMap)
        cat.copy(courses = updatedCourses, subcategories = updatedSubs)
    }

    private fun getEffectiveScore(record: ScoreRecord): String {
        if (!record.retakeScore.isNullOrEmpty()) return record.retakeScore
        if (!record.makeupScore.isNullOrEmpty()) return record.makeupScore
        return record.score
    }

    private fun isPassingGrade(score: String): Boolean {
        val num = score.toDoubleOrNull()
        if (num != null) return num >= 60
        val passing = listOf("优秀", "良好", "中等", "及格", "合格", "通过", "A", "B", "C", "D")
        return passing.any { score.contains(it, ignoreCase = true) }
    }

    private fun compareScores(a: ScoreRecord, b: ScoreRecord): Int {
        val sa = getEffectiveScore(a).toDoubleOrNull()
        val sb = getEffectiveScore(b).toDoubleOrNull()
        if (sa != null && sb != null) return sa.compareTo(sb)
        val pa = isPassingGrade(getEffectiveScore(a))
        val pb = isPassingGrade(getEffectiveScore(b))
        return when {
            pa && !pb -> 1
            !pa && pb -> -1
            else -> 0
        }
    }

    companion object {
        private const val TAG = "PlanService"
        const val BASE_URL = "http://jwcxk2-aufe-edu-cn.vpn2.aufe.edu.cn:8118"
        private const val CACHE_VALID_MS = 5 * 60 * 1000L
    }
}
