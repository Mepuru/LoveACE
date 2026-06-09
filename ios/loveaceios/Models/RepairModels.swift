import Foundation

struct RepairOrder: Identifiable {
    var id: String { taskId }
    let taskId: String
    let title: String
    let orderNumber: String
    let workHours: String
    let reporter: String
    let location: String
    let createTime: String
    let status: Int
    let statusText: String

    var isPending: Bool { status == 0 || status == 1 }
    var isCompleted: Bool { status == 2 || status == 3 }

    init(taskId: String = "", title: String = "", orderNumber: String = "",
         workHours: String = "", reporter: String = "", location: String = "",
         createTime: String = "", status: Int = 0, statusText: String = "") {
        self.taskId = taskId; self.title = title; self.orderNumber = orderNumber
        self.workHours = workHours; self.reporter = reporter; self.location = location
        self.createTime = createTime; self.status = status; self.statusText = statusText
    }
}

struct RepairOrderSummary {
    let pending: [RepairOrder]
    let completed: [RepairOrder]
    var totalCount: Int { pending.count + completed.count }

    init(pending: [RepairOrder] = [], completed: [RepairOrder] = []) {
        self.pending = pending; self.completed = completed
    }
}

struct RepairOrderDetail {
    let taskId: String
    let faultArea: String
    let repairProject: String
    let phone: String
    let faultAddress: String
    let description: String
    let progress: [RepairProgress]
    let settlements: [RepairSettlement]

    init(taskId: String = "", faultArea: String = "", repairProject: String = "",
         phone: String = "", faultAddress: String = "", description: String = "",
         progress: [RepairProgress] = [], settlements: [RepairSettlement] = []) {
        self.taskId = taskId; self.faultArea = faultArea; self.repairProject = repairProject
        self.phone = phone; self.faultAddress = faultAddress; self.description = description
        self.progress = progress; self.settlements = settlements
    }
}

struct RepairProgress: Identifiable {
    var id: String { "\(stage)_\(time)" }
    let stage: String
    let time: String
    let description: String

    init(stage: String = "", time: String = "", description: String = "") {
        self.stage = stage; self.time = time; self.description = description
    }
}

struct RepairSettlement: Identifiable {
    var id: String { "\(serviceName)_\(material)" }
    let serviceName: String
    let material: String
    let workPoints: String

    init(serviceName: String = "", material: String = "", workPoints: String = "") {
        self.serviceName = serviceName; self.material = material; self.workPoints = workPoints
    }
}

struct RepairFormData {
    let areas: [RepairAreaGroup]
    let projects: [RepairProjectGroup]

    init(areas: [RepairAreaGroup] = [], projects: [RepairProjectGroup] = []) {
        self.areas = areas; self.projects = projects
    }
}

struct RepairAreaGroup: Identifiable {
    var id: String { groupName }
    let groupName: String
    let items: [RepairAreaItem]

    init(groupName: String = "", items: [RepairAreaItem] = []) {
        self.groupName = groupName; self.items = items
    }
}

struct RepairAreaItem: Identifiable, Hashable {
    var id: String { itemId }
    let itemId: String
    let name: String

    init(itemId: String = "", name: String = "") {
        self.itemId = itemId; self.name = name
    }
}

struct RepairProjectGroup: Identifiable {
    var id: String { groupName }
    let groupName: String
    let items: [RepairProjectItem]

    init(groupName: String = "", items: [RepairProjectItem] = []) {
        self.groupName = groupName; self.items = items
    }
}

struct RepairProjectItem: Identifiable, Hashable {
    var id: String { itemId }
    let itemId: String
    let name: String

    init(itemId: String = "", name: String = "") {
        self.itemId = itemId; self.name = name
    }
}

struct RepairSubmitRequest {
    let areaId: String
    let areaName: String
    let projectId: String
    let projectName: String
    let phone: String
    let address: String
    let description: String
    let picUrls: String?
}
