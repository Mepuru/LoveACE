package tech.loveace.appv3.data.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import tech.loveace.appv3.data.model.*
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 宿舍门卡服务 — 直连 spoyn.cn，不走 EasyConnect VPN
 */
class DoorCardService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    // ==================== 登录 ====================

    suspend fun login(
        userno: String,
        username: String,
        rawPassword: String,
    ): UniResponse<DoorCardUserInfo> = withContext(Dispatchers.IO) {
        try {
            val md5Password = md5(rawPassword).uppercase()
            val url = "$BASE_URL/ble/loginOn?openid=&username=$username&userno=$userno&password=$md5Password"
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("响应为空")
            Log.d(TAG, "login response: $body")

            val obj = json.parseToJsonElement(body).jsonObject
            val code = obj["code"]?.jsonPrimitive?.intOrNull ?: -1
            if (code != 200) {
                return@withContext UniResponse.failure("用户名、密码或姓名错误")
            }
            val data = obj["data"]?.jsonObject ?: throw Exception("返回数据为空")
            val userInfo = DoorCardUserInfo(
                personId = data["PersonID"]?.jsonPrimitive?.contentOrNull ?: "",
                personName = data["PersonName"]?.jsonPrimitive?.contentOrNull ?: "",
                cardId = data["CardID"]?.jsonPrimitive?.contentOrNull ?: "",
                personKind = data["PersonKind"]?.jsonPrimitive?.intOrNull ?: 0,
            )
            UniResponse.success(userInfo)
        } catch (e: Exception) {
            Log.e(TAG, "login failed", e)
            UniResponse.failure(e.message ?: "登录失败", retryable = true)
        }
    }

    // ==================== 获取房间列表 ====================

    suspend fun getRoomList(personId: String): UniResponse<List<DoorCardRoom>> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/ble/getRoomList?personid=$personId"
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("响应为空")
            Log.d(TAG, "getRoomList response: $body")

            val obj = json.parseToJsonElement(body).jsonObject
            val dataArr = obj["data"]?.jsonArray
            if (dataArr == null || dataArr.isEmpty()) {
                return@withContext UniResponse.success(emptyList())
            }
            val rooms = dataArr.map { item ->
                val r = item.jsonObject
                DoorCardRoom(
                    roomId = r["RoomID"]?.jsonPrimitive?.contentOrNull ?: "",
                    roomName = r["RoomName"]?.jsonPrimitive?.contentOrNull ?: "",
                    buildName = r["BuildName"]?.jsonPrimitive?.contentOrNull ?: "",
                    btMac = r["BtMac"]?.jsonPrimitive?.contentOrNull ?: "",
                    sKey = r["sKey"]?.jsonPrimitive?.contentOrNull ?: "",
                    sn = r["SN"]?.jsonPrimitive?.intOrNull ?: 0,
                    power = (r["Power"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtMost(100),
                    endDateTime = r["EndDateTime"]?.jsonPrimitive?.contentOrNull ?: "",
                    personId = r["PersonID"]?.jsonPrimitive?.contentOrNull ?: "",
                    schoolId = r["SchoolID"]?.jsonPrimitive?.contentOrNull ?: "",
                )
            }
            UniResponse.success(rooms)
        } catch (e: Exception) {
            Log.e(TAG, "getRoomList failed", e)
            UniResponse.failure(e.message ?: "获取房间列表失败", retryable = true)
        }
    }

    // ==================== 操作日志上报 ====================

    suspend fun reportOperationLog(
        cardId: String,
        roomId: String,
        personId: String,
        schoolId: String,
        openType: Int,
        detail: String,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/ble/operateLog?cardid=$cardId&roomid=$roomId&personid=$personId&schoolid=$schoolId&opentype=$openType&detail=$detail"
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext false
            val obj = json.parseToJsonElement(body).jsonObject
            obj["code"]?.jsonPrimitive?.intOrNull == 200
        } catch (e: Exception) {
            Log.e(TAG, "reportOperationLog failed", e)
            false
        }
    }

    // ==================== 更新电量 ====================

    suspend fun updatePower(roomId: String, power: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/ble/updatePower?roomid=$roomId&power=$power"
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext false
            val obj = json.parseToJsonElement(body).jsonObject
            obj["code"]?.jsonPrimitive?.intOrNull == 200
        } catch (e: Exception) {
            Log.e(TAG, "updatePower failed", e)
            false
        }
    }

    // ==================== MD5 ====================

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "DoorCardService"
        const val BASE_URL = "https://www.spoyn.cn"
    }
}
