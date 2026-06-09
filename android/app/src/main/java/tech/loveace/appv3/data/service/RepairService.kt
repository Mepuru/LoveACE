package tech.loveace.appv3.data.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import tech.loveace.appv3.data.model.*
import tech.loveace.appv3.data.network.AUFEConnection

/**
 * 零星维修抢单平台服务
 * baseUrl: http://wxqd-aufe-edu-cn.vpn2.aufe.edu.cn:8118/
 * 登录后 cookie 自动携带，无需额外认证
 */
class RepairService(private val connection: AUFEConnection) {

    private var sessionInitialized = false

    /** 初始化维修平台会话：访问首页触发 CAS 认证，建立 PHPSESSID */
    private suspend fun ensureSession() {
        if (sessionInitialized) return
        withContext(Dispatchers.IO) {
            try {
                // 访问用户页面触发 CAS 重定向，建立 PHP session
                val response = connection.client.get("$BASE_URL/index.php/index/user")
                val html = response.body?.string() ?: ""
                Log.d(TAG, "ensureSession: response length=${html.length}, contains '零星维修'=${html.contains("零星维修")}")
                // 只要拿到了有效页面就算成功
                if (html.contains("weui_tabbar") || html.contains("零星维修") || html.contains("我的")) {
                    sessionInitialized = true
                    Log.d(TAG, "✅ Session initialized successfully")
                } else {
                    Log.w(TAG, "⚠️ Session init response may not be valid, html snippet: ${html.take(300)}")
                    // 仍然标记为已初始化，避免重复请求
                    sessionInitialized = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "ensureSession failed", e)
                // 不抛异常，让后续请求自行处理
            }
        }
    }

    // ==================== 我的工单列表 ====================

    /** 获取待完成工单（待接单 staue=0 + 待完工 staue=1） */
    suspend fun getPendingOrders(): UniResponse<List<RepairOrder>> = withContext(Dispatchers.IO) {
        try {
            ensureSession()
            val pending = async { fetchOrderList(0) }
            val inProgress = async { fetchOrderList(1) }
            val all = pending.await() + inProgress.await()
            UniResponse.success(all)
        } catch (e: Exception) {
            Log.e(TAG, "getPendingOrders failed", e)
            UniResponse.failure(e.message ?: "获取待完成工单失败", retryable = true)
        }
    }

    /** 获取已完成工单（待评价 staue=2 + 已完成 staue=3） */
    suspend fun getCompletedOrders(): UniResponse<List<RepairOrder>> = withContext(Dispatchers.IO) {
        try {
            ensureSession()
            val toRate = async { fetchOrderList(2) }
            val done = async { fetchOrderList(3) }
            val all = toRate.await() + done.await()
            UniResponse.success(all)
        } catch (e: Exception) {
            Log.e(TAG, "getCompletedOrders failed", e)
            UniResponse.failure(e.message ?: "获取已完成工单失败", retryable = true)
        }
    }

    /** 获取全部工单 */
    suspend fun getAllOrders(): UniResponse<RepairOrderSummary> = withContext(Dispatchers.IO) {
        try {
            ensureSession()
            val s0 = async { fetchOrderList(0) }
            val s1 = async { fetchOrderList(1) }
            val s2 = async { fetchOrderList(2) }
            val s3 = async { fetchOrderList(3) }
            val pending = s0.await() + s1.await()
            val completed = s2.await() + s3.await()
            UniResponse.success(RepairOrderSummary(pending, completed))
        } catch (e: Exception) {
            Log.e(TAG, "getAllOrders failed", e)
            UniResponse.failure(e.message ?: "获取工单列表失败", retryable = true)
        }
    }

    private fun fetchOrderList(status: Int): List<RepairOrder> {
        val url = "$BASE_URL/index.php/index/myorder/staue/$status"
        val response = connection.client.get(url)
        val html = response.body?.string() ?: return emptyList()
        Log.d(TAG, "fetchOrderList(status=$status): html length=${html.length}")
        return parseOrderList(html, status)
    }

    private fun parseOrderList(html: String, status: Int): List<RepairOrder> {
        val doc = Jsoup.parse(html)
        val orders = mutableListOf<RepairOrder>()
        for (item in doc.select("a.weui_media_box.weui_media_appmsg")) {
            val href = item.attr("href")
            // 兼容 VPN 代理可能重写的 URL 格式
            val taskId = Regex("taskid/(\\d+)").find(href)?.groupValues?.get(1)
                ?: Regex("taskid%2F(\\d+)").find(href)?.groupValues?.get(1)
                ?: continue
            val title = item.selectFirst("h4.weui_media_title")?.text()?.trim() ?: ""
            val descs = item.select("p.weui_media_desc")

            var workHours = ""
            var orderNumber = ""
            var reporter = ""
            var location = ""
            var createTime = ""

            for (desc in descs) {
                val text = desc.text().trim()
                if (text.contains("工时")) {
                    workHours = Regex("工时[：:]\\s*(\\S+)").find(text)?.groupValues?.get(1) ?: ""
                    orderNumber = Regex("\\d{12,}").find(text)?.value ?: ""
                }
                if (text.contains("校区") || text.contains("公寓") || text.contains("楼")) {
                    // 提取报修人和地点
                    val userIcon = desc.selectFirst(".fa-user")
                    val buildingIcon = desc.selectFirst(".fa-building-o")
                    if (userIcon != null) {
                        // 格式: "用户名 地点"
                        val cleanText = text.replace(Regex("[\\s]+"), " ").trim()
                        val parts = cleanText.split(" ").filter { it.isNotEmpty() }
                        if (parts.isNotEmpty()) reporter = parts[0]
                        if (parts.size > 1) location = parts.drop(1).joinToString(" ")
                    }
                }
                if (desc.selectFirst(".fa-pencil-square-o") != null) {
                    createTime = Regex("\\d{1,2}-\\d{1,2}\\s+\\d{1,2}:\\d{2}").find(text)?.value
                        ?: Regex("\\d{4}-\\d{1,2}-\\d{1,2}").find(text)?.value
                        ?: text.replace(Regex("[^\\d\\s:-]"), "").trim()
                }
            }

            val statusText = when (status) {
                0 -> "待接单"
                1 -> "待完工"
                2 -> "待评价"
                3 -> "已完成"
                else -> "未知"
            }

            orders.add(
                RepairOrder(
                    taskId = taskId,
                    title = title,
                    orderNumber = orderNumber,
                    workHours = workHours,
                    reporter = reporter,
                    location = location,
                    createTime = createTime,
                    status = status,
                    statusText = statusText,
                )
            )
        }
        Log.d(TAG, "parseOrderList(status=$status): found ${orders.size} orders")
        return orders
    }

    // ==================== 工单详情 ====================

    suspend fun getOrderDetail(taskId: String): UniResponse<RepairOrderDetail> = withContext(Dispatchers.IO) {
        try {
            ensureSession()
            val url = "$BASE_URL/index.php/index/showrepair/taskid/$taskId"
            val response = connection.client.get(url)
            val html = response.body?.string() ?: throw Exception("响应为空")
            val detail = parseOrderDetail(html, taskId)
            UniResponse.success(detail)
        } catch (e: Exception) {
            Log.e(TAG, "getOrderDetail failed", e)
            UniResponse.failure(e.message ?: "获取工单详情失败", retryable = true)
        }
    }

    private fun parseOrderDetail(html: String, taskId: String): RepairOrderDetail {
        val doc = Jsoup.parse(html)
        val inputs = doc.select("input.weui_input")
        var faultArea = ""
        var repairProject = ""
        var phone = ""
        var faultAddress = ""
        for (input in inputs) {
            when (input.attr("id")) {
                "Area_Name" -> faultArea = input.attr("value")
                "Project_Name" -> repairProject = input.attr("value")
                "telphone" -> phone = input.attr("value")
                "Address" -> faultAddress = input.attr("value")
            }
        }
        val description = doc.selectFirst("#WordDesc")?.text()?.trim() ?: ""

        // 解析维修进度
        val progressItems = mutableListOf<RepairProgress>()
        val progressCon = doc.selectFirst(".progressCon")
        if (progressCon != null) {
            val leftItems = progressCon.select(".conLeft li")
            val rightItems = progressCon.select(".conRight li")
            for (i in leftItems.indices) {
                val stage = leftItems[i].text().trim()
                val rightLi = rightItems.getOrNull(i)
                val time = rightLi?.selectFirst("span")?.text()?.trim() ?: ""
                val desc = rightLi?.selectFirst("p")?.text()?.trim() ?: ""
                progressItems.add(RepairProgress(stage, time, desc))
            }
        }

        // 解析决算单
        val settlements = mutableListOf<RepairSettlement>()
        val table = doc.selectFirst("table.wxcl")
        if (table != null) {
            for (row in table.select("tbody tr")) {
                val cells = row.select("td")
                if (cells.size >= 3) {
                    settlements.add(
                        RepairSettlement(
                            serviceName = cells[0].text().trim(),
                            material = cells[1].text().trim(),
                            workPoints = cells[2].text().trim(),
                        )
                    )
                }
            }
        }

        return RepairOrderDetail(
            taskId = taskId,
            faultArea = faultArea,
            repairProject = repairProject,
            phone = phone,
            faultAddress = faultAddress,
            description = description,
            progress = progressItems,
            settlements = settlements,
        )
    }

    // ==================== 报修前置信息 ====================

    suspend fun getRepairFormData(): UniResponse<RepairFormData> = withContext(Dispatchers.IO) {
        try {
            ensureSession()
            // 获取区域列表
            val areaUrl = "$BASE_URL/index.php/index/arealist"
            val areaResp = connection.client.get(areaUrl)
            val areaHtml = areaResp.body?.string() ?: throw Exception("获取区域列表失败")
            Log.d(TAG, "arealist html length=${areaHtml.length}")
            val areas = parseAreaList(areaHtml)

            // 获取维修项目列表
            val projectUrl = "$BASE_URL/index.php/index/projectlist"
            val projectResp = connection.client.get(projectUrl)
            val projectHtml = projectResp.body?.string() ?: throw Exception("获取维修项目列表失败")
            Log.d(TAG, "projectlist html length=${projectHtml.length}")
            val projects = parseProjectList(projectHtml)

            if (areas.isEmpty()) Log.w(TAG, "⚠️ areas is empty! arealist html snippet: ${areaHtml.take(500)}")
            if (projects.isEmpty()) Log.w(TAG, "⚠️ projects is empty! projectlist html snippet: ${projectHtml.take(500)}")

            UniResponse.success(RepairFormData(areas, projects))
        } catch (e: Exception) {
            Log.e(TAG, "getRepairFormData failed", e)
            UniResponse.failure(e.message ?: "获取报修信息失败", retryable = true)
        }
    }

    private fun parseAreaList(html: String): List<RepairAreaGroup> {
        val doc = Jsoup.parse(html)
        val groups = mutableListOf<RepairAreaGroup>()

        // 方案1: 通过 tab 和 content div 的对应关系解析
        val tabs = doc.select("#segmentedControls a.mui-control-item")
        val contentDivs = doc.select("#segmentedControlContents > div.mui-control-content")

        if (tabs.size == contentDivs.size && tabs.isNotEmpty()) {
            for (i in tabs.indices) {
                val groupName = tabs[i].text().trim()
                val items = contentDivs[i].select("li.mui-table-view-cell").map { li ->
                    RepairAreaItem(id = li.attr("data-val"), name = li.text().trim())
                }.filter { it.id.isNotEmpty() && it.name.isNotEmpty() }
                if (items.isNotEmpty()) groups.add(RepairAreaGroup(groupName, items))
            }
        }

        // 方案2: 如果方案1失败，尝试通过 href 匹配
        if (groups.isEmpty()) {
            for (tab in tabs) {
                val groupName = tab.text().trim()
                val href = tab.attr("href").removePrefix("#")
                if (href.isEmpty()) continue
                val content = doc.selectFirst("#$href") ?: continue
                val items = content.select("li.mui-table-view-cell").map { li ->
                    RepairAreaItem(id = li.attr("data-val"), name = li.text().trim())
                }.filter { it.id.isNotEmpty() && it.name.isNotEmpty() }
                if (items.isNotEmpty()) groups.add(RepairAreaGroup(groupName, items))
            }
        }

        // 方案3: 如果仍然失败，直接按 content div 解析
        if (groups.isEmpty()) {
            for ((index, div) in contentDivs.withIndex()) {
                val items = div.select("li.mui-table-view-cell").map { li ->
                    RepairAreaItem(id = li.attr("data-val"), name = li.text().trim())
                }.filter { it.id.isNotEmpty() && it.name.isNotEmpty() }
                if (items.isNotEmpty()) groups.add(RepairAreaGroup("区域${index + 1}", items))
            }
        }

        Log.d(TAG, "parseAreaList: ${groups.size} groups, total ${groups.sumOf { it.items.size }} items")
        return groups
    }

    private fun parseProjectList(html: String): List<RepairProjectGroup> {
        val doc = Jsoup.parse(html)
        val groups = mutableListOf<RepairProjectGroup>()

        val tabs = doc.select("#segmentedControls a.mui-control-item")
        val contentDivs = doc.select("#segmentedControlContents > div.mui-control-content")

        if (tabs.size == contentDivs.size && tabs.isNotEmpty()) {
            for (i in tabs.indices) {
                val groupName = tabs[i].text().trim()
                val items = contentDivs[i].select("li.mui-table-view-cell").map { li ->
                    RepairProjectItem(id = li.attr("data-val"), name = li.text().trim())
                }.filter { it.id.isNotEmpty() && it.name.isNotEmpty() }
                if (items.isNotEmpty()) groups.add(RepairProjectGroup(groupName, items))
            }
        }

        if (groups.isEmpty()) {
            for (tab in tabs) {
                val groupName = tab.text().trim()
                val href = tab.attr("href").removePrefix("#")
                if (href.isEmpty()) continue
                val content = doc.selectFirst("#$href") ?: continue
                val items = content.select("li.mui-table-view-cell").map { li ->
                    RepairProjectItem(id = li.attr("data-val"), name = li.text().trim())
                }.filter { it.id.isNotEmpty() && it.name.isNotEmpty() }
                if (items.isNotEmpty()) groups.add(RepairProjectGroup(groupName, items))
            }
        }

        if (groups.isEmpty()) {
            for ((index, div) in contentDivs.withIndex()) {
                val items = div.select("li.mui-table-view-cell").map { li ->
                    RepairProjectItem(id = li.attr("data-val"), name = li.text().trim())
                }.filter { it.id.isNotEmpty() && it.name.isNotEmpty() }
                if (items.isNotEmpty()) groups.add(RepairProjectGroup("项目${index + 1}", items))
            }
        }

        Log.d(TAG, "parseProjectList: ${groups.size} groups, total ${groups.sumOf { it.items.size }} items")
        return groups
    }

    // ==================== 图片上传 ====================

    suspend fun uploadImage(base64Data: String): UniResponse<String> = withContext(Dispatchers.IO) {
        try {
            ensureSession()
            val url = "$BASE_URL/index.php/index/uploadpic?sf_request_type=ajax"
            val response = connection.client.post(
                url,
                formData = mapOf("data" to base64Data),
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "$BASE_URL/index.php/index/repair",
                ),
            )
            val body = response.body?.string() ?: throw Exception("响应为空")
            val urlMatch = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(body)
            val imageUrl = urlMatch?.groupValues?.get(1) ?: throw Exception("上传失败，未获取到图片地址")
            UniResponse.success(imageUrl)
        } catch (e: Exception) {
            Log.e(TAG, "uploadImage failed", e)
            UniResponse.failure(e.message ?: "图片上传失败")
        }
    }

    // ==================== 提交报修 ====================

    suspend fun submitRepair(request: RepairSubmitRequest): UniResponse<Boolean> = withContext(Dispatchers.IO) {
        try {
            ensureSession()
            val url = "$BASE_URL/index.php/index/repair?sf_request_type=ajax"
            val response = connection.client.post(
                url,
                formData = mapOf(
                    "areaid" to request.areaId,
                    "areaname" to request.areaName,
                    "projectid" to request.projectId,
                    "projectname" to request.projectName,
                    "telphone" to request.phone,
                    "address" to request.address,
                    "worddesc" to request.description,
                    "pic" to (request.picUrls ?: ""),
                    "voiceid" to "",
                    "taskType" to "1",
                ),
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "$BASE_URL/index.php/index/repair",
                ),
            )
            val body = response.body?.string() ?: throw Exception("响应为空")
            // 尝试解析 JSON 响应
            if (body.contains("\"isok\"") && body.contains("true")) {
                UniResponse.success(true, "报修提交成功")
            } else {
                val msg = Regex("\"msg\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: "提交失败"
                UniResponse.failure(msg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "submitRepair failed", e)
            UniResponse.failure(e.message ?: "提交报修失败", retryable = true)
        }
    }

    companion object {
        private const val TAG = "RepairService"
        const val BASE_URL = "http://wxqd-aufe-edu-cn.vpn2.aufe.edu.cn:8118"
    }
}
