package tech.loveace.appv3.data.ble

import java.util.Calendar

/**
 * BLE 门锁指令构建工具 — 移植自小程序 utilsStr.js / ble.js
 */
object BleCommandHelper {

    // BLE 服务和特征 UUID
    const val SERVICE_UUID = "0000FFE0-0000-1000-8000-00805F9B34FB"
    const val CHARACTERISTIC_UUID = "0000FFE1-0000-1000-8000-00805F9B34FB"

    /** BCC 校验 */
    fun bcc(hex: String): String {
        if (hex.isEmpty()) return "00"
        val len = hex.length / 2
        var result = 0
        for (i in 0 until len) {
            result = result xor hex.substring(i * 2, i * 2 + 2).toInt(16)
        }
        return result.toString(16).uppercase().padStart(2, '0')
    }

    /** 开门指令 */
    fun buildOpenDoorCommand(sKey: String, schoolId: String): ByteArray {
        val opCode = if (schoolId == "340301") "05FF" else "0503"
        val cmd = opCode + sKey
        val full = cmd + bcc(cmd)
        return hexToBytes(full)
    }

    /** 发卡指令 */
    fun buildAddCardCommand(cardId: String, sn: Int, sKey: String, endDateTime: String): ByteArray {
        val snHex = sn.toString().padStart(4, '0')
        val cmd = "1007$cardId$snHex$sKey$endDateTime"
        val full = cmd + bcc(cmd)
        return hexToBytes(full)
    }

    /** 冻结卡指令 */
    fun buildFreezeCardCommand(sn: Int, sKey: String, endDateTime: String): ByteArray {
        val snHex = sn.toString().padStart(4, '0')
        val cmd = "100700000000${snHex}${sKey}$endDateTime"
        val full = cmd + bcc(cmd)
        return hexToBytes(full)
    }

    /** 校时指令 */
    fun buildCheckTimeCommand(): ByteArray {
        val bcdTime = bleTimeBCD()
        val cmd = "0810$bcdTime"
        val full = cmd + bcc(cmd)
        return hexToBytes(full)
    }

    /** 常开指令 */
    fun buildAlwaysOpenCommand(sKey: String, schoolId: String): ByteArray {
        val opCode = if (schoolId == "340301") "05FF" else "0511"
        val cmd = opCode + sKey
        val full = cmd + bcc(cmd)
        return hexToBytes(full)
    }

    /** 常闭指令 */
    fun buildAlwaysOffCommand(sKey: String, schoolId: String): ByteArray {
        val opCode = if (schoolId == "340301") "05FF" else "0512"
        val cmd = opCode + sKey
        val full = cmd + bcc(cmd)
        return hexToBytes(full)
    }

    /** BCD 时间编码 */
    private fun bleTimeBCD(): String {
        val cal = Calendar.getInstance()
        val y = (cal.get(Calendar.YEAR) - 2000).coerceAtLeast(0)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val min = cal.get(Calendar.MINUTE)
        val s = cal.get(Calendar.SECOND)
        return "%02d%02d%02d%02d%02d%02d".format(y, m, d, h, min, s)
    }

    /** Hex 字符串转 ByteArray */
    fun hexToBytes(hex: String): ByteArray {
        val len = hex.length / 2
        val bytes = ByteArray(len)
        for (i in 0 until len) {
            bytes[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return bytes
    }

    /** ByteArray 转 Hex 字符串 */
    fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** 解析开门响应：返回 (recordNum, power) 或 null */
    fun parseOpenDoorResponse(hex: String): Pair<Int, Int>? {
        if (hex.contains("0703", ignoreCase = true)) {
            val idx = hex.lowercase().indexOf("0703")
            if (idx + 14 <= hex.length) {
                val numHex = hex.substring(idx + 4, idx + 8)
                val powerHex = hex.substring(idx + 8, idx + 12)
                val num = numHex.toIntOrNull(16) ?: return null
                val power = powerHex.toIntOrNull(16) ?: return null
                return Pair(num, power)
            }
        }
        return null
    }
}
