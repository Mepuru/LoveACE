package tech.loveace.appv3.data.model

// ==================== Repair Models ====================

/** 维修工单（列表项） */
data class RepairOrder(
    val taskId: String = "",
    val title: String = "",
    val orderNumber: String = "",
    val workHours: String = "",
    val reporter: String = "",
    val location: String = "",
    val createTime: String = "",
    val status: Int = 0,        // 0=待接单, 1=待完工, 2=待评价, 3=已完成
    val statusText: String = "",
) {
    val isPending get() = status == 0 || status == 1
    val isCompleted get() = status == 2 || status == 3
}

/** 工单汇总 */
data class RepairOrderSummary(
    val pending: List<RepairOrder> = emptyList(),
    val completed: List<RepairOrder> = emptyList(),
) {
    val totalCount get() = pending.size + completed.size
}

/** 维修工单详情 */
data class RepairOrderDetail(
    val taskId: String = "",
    val faultArea: String = "",
    val repairProject: String = "",
    val phone: String = "",
    val faultAddress: String = "",
    val description: String = "",
    val progress: List<RepairProgress> = emptyList(),
    val settlements: List<RepairSettlement> = emptyList(),
)

/** 维修进度条目 */
data class RepairProgress(
    val stage: String = "",
    val time: String = "",
    val description: String = "",
)

/** 决算单条目 */
data class RepairSettlement(
    val serviceName: String = "",
    val material: String = "",
    val workPoints: String = "",
)

/** 报修表单前置数据 */
data class RepairFormData(
    val areas: List<RepairAreaGroup> = emptyList(),
    val projects: List<RepairProjectGroup> = emptyList(),
)

/** 区域分组（西校区/东校区） */
data class RepairAreaGroup(
    val groupName: String = "",
    val items: List<RepairAreaItem> = emptyList(),
)

/** 区域项 */
data class RepairAreaItem(
    val id: String = "",
    val name: String = "",
)

/** 维修项目分组 */
data class RepairProjectGroup(
    val groupName: String = "",
    val items: List<RepairProjectItem> = emptyList(),
)

/** 维修项目项 */
data class RepairProjectItem(
    val id: String = "",
    val name: String = "",
)

/** 报修提交请求 */
data class RepairSubmitRequest(
    val areaId: String,
    val areaName: String,
    val projectId: String,
    val projectName: String,
    val phone: String,
    val address: String,
    val description: String,
    val picUrls: String? = null,
)
