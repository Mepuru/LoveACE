package tech.loveace.appv3.data.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import tech.loveace.appv3.data.model.*
import tech.loveace.appv3.data.network.AUFEConnection

/**
 * 竞赛信息服务 - 获奖项目和学分汇总（支持分页）
 */
class CompetitionService(private val connection: AUFEConnection) {

    suspend fun getCompetitionInfo(): UniResponse<CompetitionFullResponse> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/xsXmMain.aspx"

            // Step 1: GET 初始页面，提取 ASP.NET 表单字段
            val indexResponse = connection.client.get(url)
            val indexHtml = indexResponse.body?.string() ?: throw Exception("页面为空")
            val indexDoc = Jsoup.parse(indexHtml)

            val viewState = indexDoc.selectFirst("input[name=__VIEWSTATE]")?.attr("value") ?: ""
            val viewStateGen = indexDoc.selectFirst("input[name=__VIEWSTATEGENERATOR]")?.attr("value") ?: ""
            val eventValidation = indexDoc.selectFirst("input[name=__EVENTVALIDATION]")?.attr("value") ?: ""

            // Step 2: POST 点击"已申报奖项"标签，必须设置正确的 __EVENTTARGET
            val postResponse = connection.client.post(url, formData = mapOf(
                "__VIEWSTATE" to viewState,
                "__VIEWSTATEGENERATOR" to viewStateGen,
                "__EVENTVALIDATION" to eventValidation,
                "__EVENTTARGET" to "ctl00\$ContentPlaceHolder1\$ContentPlaceHolder2\$DataList1\$ctl01\$LinkButton1",
                "__EVENTARGUMENT" to "",
                "__LASTFOCUS" to "",
                "ctl00\$ContentPlaceHolder1\$ContentPlaceHolder2\$ddlSslb" to "%",
                "ctl00\$ContentPlaceHolder1\$ContentPlaceHolder2\$txtSsmc" to "",
            ))
            val postHtml = postResponse.body?.string() ?: throw Exception("数据页面为空")
            var currentDoc = Jsoup.parse(postHtml)

            // 解析第一页
            val allAwards = mutableListOf<AwardProject>()
            allAwards.addAll(parseAwardProjects(currentDoc))
            val creditsSummary = parseCreditsSummary(currentDoc)

            // 解析分页信息
            val totalPages = parseTotalPages(currentDoc)
            Log.d(TAG, "第1页: ${allAwards.size} 项, 总页数: $totalPages")

            // Step 3: 循环获取剩余页面
            var currentHtml = postHtml
            for (page in 2..totalPages) {
                Log.d(TAG, "正在获取第 $page 页...")
                val pageDoc = Jsoup.parse(currentHtml)
                val nextFormData = extractNextPageFormData(pageDoc, page)
                if (nextFormData["__VIEWSTATE"].isNullOrEmpty()) {
                    Log.w(TAG, "第 $page 页表单数据提取失败，停止翻页")
                    break
                }
                val pageResponse = connection.client.post(url, formData = nextFormData)
                currentHtml = pageResponse.body?.string() ?: break
                val pageAwards = parseAwardProjects(Jsoup.parse(currentHtml))
                allAwards.addAll(pageAwards)
                Log.d(TAG, "第 $page 页: ${pageAwards.size} 项")
            }

            Log.i(TAG, "竞赛信息获取成功，共 ${allAwards.size} 项")
            UniResponse.success(CompetitionFullResponse(allAwards, creditsSummary))
        } catch (e: Exception) {
            Log.e(TAG, "getCompetitionInfo failed", e)
            UniResponse.failure(e.message ?: "获取竞赛信息失败", retryable = true)
        }
    }

    /** 解析获奖项目表格 (table id 包含 "gvHj") */
    private fun parseAwardProjects(doc: Document): List<AwardProject> {
        val awards = mutableListOf<AwardProject>()
        // 按 Flutter 逻辑：先找 id 包含 gvHj 的表格，再找 caption 包含特定文字的
        val table = doc.selectFirst("table[id*=gvHj]")
            ?: doc.select("table").firstOrNull { it.selectFirst("caption")?.text()?.contains("已经进行获奖申报") == true }
            ?: return awards

        val rows = table.select("tr")
        for (i in 1 until rows.size) { // skip header
            val cells = rows[i].select("td")
            if (cells.size < 12) continue

            // 跳过分页行
            val firstText = cells[0].text().trim()
            if (firstText.contains("当前第") || firstText.contains("页/共")) continue
            // 跳过非数字 projectId（表头等）
            val projectId = firstText
            if (projectId.toIntOrNull() == null) continue

            awards.add(AwardProject(
                projectId = projectId,
                projectName = cells[1].text().trim(),
                level = cells[2].text().trim(),
                grade = cells[3].text().trim(),
                awardDate = cells[4].text().trim(),
                applicantId = cells[5].text().trim(),
                applicantName = cells[6].text().trim(),
                order = cells[7].text().trim().toIntOrNull() ?: 0,
                credits = cells[8].text().trim().toDoubleOrNull() ?: 0.0,
                bonus = cells[9].text().trim().toDoubleOrNull() ?: 0.0,
                status = cells[10].text().trim(),
                verificationStatus = cells[11].text().trim(),
            ))
        }
        return awards
    }

    /** 从特定 span id 解析学分汇总 */
    private fun parseCreditsSummary(doc: Document): CreditsSummary? {
        return try {
            CreditsSummary(
                disciplineCompetitionCredits = parseCredit(doc, "ContentPlaceHolder1_ContentPlaceHolder2_lblXkjsxf"),
                scientificResearchCredits = parseCredit(doc, "ContentPlaceHolder1_ContentPlaceHolder2_lblKyxf"),
                transferableCompetitionCredits = parseCredit(doc, "ContentPlaceHolder1_ContentPlaceHolder2_lblKzjslxf"),
                innovationPracticeCredits = parseCredit(doc, "ContentPlaceHolder1_ContentPlaceHolder2_lblCxcyxf"),
                abilityCertificationCredits = parseCredit(doc, "ContentPlaceHolder1_ContentPlaceHolder2_lblNlzgxf"),
                otherProjectCredits = parseCredit(doc, "ContentPlaceHolder1_ContentPlaceHolder2_lblQtxf"),
            )
        } catch (_: Exception) { null }
    }

    private fun parseCredit(doc: Document, spanId: String): Double? {
        val text = doc.selectFirst("span[id=$spanId]")?.text()?.trim() ?: return null
        if (text.isEmpty() || text == "无") return null
        return text.toDoubleOrNull()
    }

    /** 解析总页数 */
    private fun parseTotalPages(doc: Document): Int {
        val span = doc.selectFirst("span[id*=gvHj_LabelPageCount]")
        return span?.text()?.trim()?.toIntOrNull() ?: 1
    }

    /** 提取翻页所需的表单数据 */
    private fun extractNextPageFormData(doc: Document, targetPage: Int): Map<String, String> {
        val viewState = doc.selectFirst("input[name=__VIEWSTATE]")?.attr("value") ?: ""
        val viewStateGen = doc.selectFirst("input[name=__VIEWSTATEGENERATOR]")?.attr("value") ?: ""
        val eventValidation = doc.selectFirst("input[name=__EVENTVALIDATION]")?.attr("value") ?: ""

        // 查找"下一页"链接的 __doPostBack target
        var eventTarget = ""
        val nextPageLink = doc.selectFirst("a[id*=LinkButtonNextPage]")
        if (nextPageLink != null) {
            val href = nextPageLink.attr("href")
            val match = Regex("__doPostBack\\('([^']+)'").find(href)
            if (match != null) eventTarget = match.groupValues[1]
        }
        // 备选：GO 按钮
        if (eventTarget.isEmpty()) {
            val goLink = doc.selectFirst("a[id*=btnGo]")
            if (goLink != null) {
                val href = goLink.attr("href")
                val match = Regex("__doPostBack\\('([^']+)'").find(href)
                if (match != null) eventTarget = match.groupValues[1]
            }
        }

        val formData = mutableMapOf(
            "__VIEWSTATE" to viewState,
            "__VIEWSTATEGENERATOR" to viewStateGen,
            "__EVENTVALIDATION" to eventValidation,
            "__EVENTTARGET" to eventTarget,
            "__EVENTARGUMENT" to "",
            "__LASTFOCUS" to "",
        )

        // 页码输入框
        val pageInput = doc.selectFirst("input[id*=txtNewPageIndex]")
        if (pageInput != null) {
            val name = pageInput.attr("name")
            if (name.isNotEmpty()) formData[name] = targetPage.toString()
        }

        return formData
    }

    companion object {
        private const val TAG = "CompetitionService"
        const val BASE_URL = "http://211-86-241-245.vpn2.aufe.edu.cn:8118"
    }
}
