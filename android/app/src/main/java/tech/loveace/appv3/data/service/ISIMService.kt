package tech.loveace.appv3.data.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import org.jsoup.Jsoup
import tech.loveace.appv3.data.model.*
import tech.loveace.appv3.data.network.AUFEConnection
import tech.loveace.appv3.data.network.HttpClient

/**
 * ISIM 宿舍电费服务
 */
class ISIMService(private val connection: AUFEConnection) {

    private val isimClient = HttpClient(baseUrl = BASE_URL, timeoutMs = 30_000, followRedirects = true)
    private var jsessionId: String? = null
    private var sessionInitialized = false

    private suspend fun ensureSession() {
        if (sessionInitialized && jsessionId != null) return
        withContext(Dispatchers.IO) {
            isimClient.copyCookiesFrom(connection.client)
            val url = "$BASE_URL/go?openid=${connection.userId}&sn=sn"
            isimClient.get(url)
            jsessionId = isimClient.cookieJar.getCookie("JSESSIONID")
            if (jsessionId != null) sessionInitialized = true
            else throw Exception("无法获取 JSESSIONID")
        }
    }

    suspend fun getBuildings(): UniResponse<List<Map<String, String>>> = withContext(Dispatchers.IO) {
        try {
            ensureSession()
            val twfId = connection.twfId ?: ""
            val response = isimClient.get("$BASE_URL/about", headers = mapOf(
                "Cookie" to "JSESSIONID=$jsessionId; TWFID=$twfId",
            ))
            val html = response.body?.string() ?: throw Exception("响应为空")
            val doc = Jsoup.parse(html)
            val buildings = mutableListOf<Map<String, String>>()
            for (script in doc.select("script")) {
                val content = script.data()
                if (content.contains("pickerBuilding")) {
                    val valuesMatch = Regex("values:\\s*\\[(.*?)]").find(content)
                    val displayMatch = Regex("displayValues:\\s*\\[(.*?)]").find(content)
                    if (valuesMatch != null && displayMatch != null) {
                        val values = valuesMatch.groupValues[1].split(",").map { it.trim().replace("\"", "").replace("'", "") }.filter { it.isNotEmpty() }
                        val displays = displayMatch.groupValues[1].split(",").map { it.trim().replace("\"", "").replace("'", "") }.filter { it.isNotEmpty() && it != "请选择" }
                        for (i in 0 until minOf(values.size, displays.size)) {
                            if (values[i].isNotEmpty()) buildings.add(mapOf("code" to values[i], "name" to displays[i]))
                        }
                    }
                    break
                }
            }
            UniResponse.success(buildings)
        } catch (e: Exception) {
            Log.e(TAG, "getBuildings failed", e)
            UniResponse.failure(e.message ?: "获取楼栋列表失败")
        }
    }

    suspend fun getFloors(buildingCode: String): UniResponse<List<Map<String, String>>> = withContext(Dispatchers.IO) {
        try {
            ensureSession()
            val twfId = connection.twfId ?: ""
            val response = isimClient.get("$BASE_URL/about/floors/$buildingCode", headers = mapOf(
                "Cookie" to "JSESSIONID=$jsessionId; TWFID=$twfId",
                "X-Requested-With" to "XMLHttpRequest",
            ))
            val body = response.body?.string() ?: "[]"
            val parsed = parseFloorRoomJson(body, "floordm", "floorname")
            UniResponse.success(parsed)
        } catch (e: Exception) {
            Log.e(TAG, "getFloors failed", e)
            UniResponse.failure(e.message ?: "获取楼层列表失败")
        }
    }

    suspend fun getRooms(floorCode: String): UniResponse<List<Map<String, String>>> = withContext(Dispatchers.IO) {
        try {
            ensureSession()
            val twfId = connection.twfId ?: ""
            val response = isimClient.get("$BASE_URL/about/rooms/$floorCode", headers = mapOf(
                "Cookie" to "JSESSIONID=$jsessionId; TWFID=$twfId",
                "X-Requested-With" to "XMLHttpRequest",
            ))
            val body = response.body?.string() ?: "[]"
            val parsed = parseFloorRoomJson(body, "roomdm", "roomname")
            UniResponse.success(parsed)
        } catch (e: Exception) {
            Log.e(TAG, "getRooms failed", e)
            UniResponse.failure(e.message ?: "获取房间列表失败")
        }
    }

    suspend fun getElectricityInfo(roomCode: String, displayText: String? = null): UniResponse<ElectricityInfo> =
        withContext(Dispatchers.IO) {
            try {
                ensureSession()
                val twfId = connection.twfId ?: ""
                // Bind room
                isimClient.post("$BASE_URL/about/rebinding", formData = mapOf(
                    "roomdm" to roomCode, "room" to (displayText ?: roomCode),
                    "openid" to connection.userId, "sn" to "sn", "mode" to "u",
                ), headers = mapOf("Cookie" to "JSESSIONID=$jsessionId;TWFID=$twfId"))

                val headers = mapOf("Cookie" to "JSESSIONID=$jsessionId;TWFID=$twfId")
                val usageDeferred = async { isimClient.get("$BASE_URL/use/record", headers = headers) }
                val paymentDeferred = async { isimClient.get("$BASE_URL/pay/record", headers = headers) }
                val usageHtml = usageDeferred.await().body?.string() ?: ""
                val paymentHtml = paymentDeferred.await().body?.string() ?: ""

                val balance = parseBalance(usageHtml)
                val usageRecords = parseUsageRecords(usageHtml)
                val paymentRecords = parsePaymentRecords(paymentHtml)
                UniResponse.success(ElectricityInfo(balance, usageRecords, paymentRecords))
            } catch (e: Exception) {
                Log.e(TAG, "getElectricityInfo failed", e)
                UniResponse.failure(e.message ?: "获取电费信息失败", retryable = true)
            }
        }

    private fun parseFloorRoomJson(body: String, codeKey: String, nameKey: String): List<Map<String, String>> {
        val result = mutableListOf<Map<String, String>>()
        try {
            val jsonStr = body.replace(Regex("([a-zA-Z_][a-zA-Z0-9_]*)\\s*:"), "\"$1\":")
            val json = kotlinx.serialization.json.Json.parseToJsonElement(jsonStr)
            val arr = json.jsonArray
            if (arr.isNotEmpty()) {
                val obj = arr[0].jsonObject
                val codes = obj[codeKey]?.jsonArray ?: return result
                val names = obj[nameKey]?.jsonArray ?: return result
                for (i in 1 until minOf(codes.size, names.size)) {
                    val code = codes[i].jsonPrimitive.contentOrNull ?: continue
                    val name = names[i].jsonPrimitive.contentOrNull ?: continue
                    if (code.isNotEmpty() && name != "请选择") result.add(mapOf("code" to code, "name" to name))
                }
            }
        } catch (e: Exception) { Log.w(TAG, "parseFloorRoomJson failed", e) }
        return result
    }

    private fun parseBalance(html: String): ElectricityBalance {
        var purchased = 0.0; var subsidy = 0.0
        val doc = Jsoup.parse(html)
        for (item in doc.select("li.item-content, li.item, .item-content")) {
            val title = item.selectFirst(".item-title, .title, dt, .label")?.text()?.trim() ?: continue
            val value = item.selectFirst(".item-after, .value, dd, .amount")?.text()?.trim() ?: continue
            val amount = Regex("([\\d.]+)").find(value)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            when {
                title.contains("购电") -> purchased = amount
                title.contains("补助") -> subsidy = amount
            }
        }
        return ElectricityBalance(purchased, subsidy)
    }

    private fun parseUsageRecords(html: String): List<ElectricityUsageRecord> {
        val records = mutableListOf<ElectricityUsageRecord>()
        val doc = Jsoup.parse(html)
        for (item in doc.select("#divRecord ul li")) {
            val time = item.selectFirst(".item-title")?.text()?.trim() ?: continue
            val usageText = item.selectFirst(".item-after")?.text()?.trim() ?: continue
            val meterText = item.selectFirst(".item-subtitle")?.text()?.trim() ?: ""
            val usage = Regex("([\\d.]+)度").find(usageText)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            val meter = Regex("电表:\\s*(.+)").find(meterText)?.groupValues?.get(1)?.trim() ?: meterText
            records.add(ElectricityUsageRecord(time, usage, meter))
        }
        return records
    }

    private fun parsePaymentRecords(html: String): List<PaymentRecord> {
        val records = mutableListOf<PaymentRecord>()
        val doc = Jsoup.parse(html)
        for (item in doc.select("#divRecord ul li")) {
            val time = item.selectFirst(".item-title")?.text()?.trim() ?: continue
            val amountText = item.selectFirst(".item-after")?.text()?.trim() ?: continue
            val typeText = item.selectFirst(".item-subtitle")?.text()?.trim() ?: ""
            val amount = Regex("(-?[\\d.]+)元").find(amountText)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            val type = Regex("类型:\\s*(.+)").find(typeText)?.groupValues?.get(1)?.trim() ?: typeText
            records.add(PaymentRecord(time, amount, type))
        }
        return records
    }

    companion object {
        private const val TAG = "ISIMService"
        const val BASE_URL = "http://hqkd-aufe-edu-cn.vpn2.aufe.edu.cn"
    }
}

private val kotlinx.serialization.json.JsonElement.jsonArray get() = this as kotlinx.serialization.json.JsonArray
private val kotlinx.serialization.json.JsonElement.jsonObject get() = this as kotlinx.serialization.json.JsonObject
private val kotlinx.serialization.json.JsonElement.jsonPrimitive get() = this as kotlinx.serialization.json.JsonPrimitive
private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String? get() = if (isString) content else try { content } catch (_: Exception) { null }
