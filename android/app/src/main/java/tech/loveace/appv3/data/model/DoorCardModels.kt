package tech.loveace.appv3.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ==================== Door Card Models ====================

/** 门卡系统用户信息（登录后返回） */
@Serializable
data class DoorCardUserInfo(
    @SerialName("PersonID") val personId: String = "",
    @SerialName("PersonName") val personName: String = "",
    @SerialName("CardID") val cardId: String = "",
    @SerialName("PersonKind") val personKind: Int = 0, // 0=学生, 1=管理员
)

/** 房间/门锁信息 */
@Serializable
data class DoorCardRoom(
    @SerialName("RoomID") val roomId: String = "",
    @SerialName("RoomName") val roomName: String = "",
    @SerialName("BuildName") val buildName: String = "",
    @SerialName("BtMac") val btMac: String = "",
    @SerialName("sKey") val sKey: String = "",
    @SerialName("SN") val sn: Int = 0,
    @SerialName("Power") val power: Int = 0,
    @SerialName("EndDateTime") val endDateTime: String = "",
    @SerialName("PersonID") val personId: String = "",
    @SerialName("SchoolID") val schoolId: String = "",
)

/** 门卡绑定凭证 */
@Serializable
data class DoorCardCredentials(
    val username: String = "",  // 姓名
    val userno: String = "",    // 学号
    val password: String = "",  // MD5 密码
)

/** BLE 连接状态 */
enum class BleConnectionState {
    Disconnected,
    Scanning,
    Connecting,
    Connected,
    Error,
}

/** 门锁操作类型 */
enum class DoorOperation(val label: String, val icon: String) {
    OpenDoor("手机开门", "🔓"),
    AddCard("发卡", "💳"),
    FreezeCard("冻结卡", "🧊"),
    CheckTime("校时", "⏰"),
    AlwaysOpen("常开设置", "🔓"),
    AlwaysOff("常闭设置", "🔒"),
    CheckDaily("考勤", "📋"),
}
