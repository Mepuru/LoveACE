# 自动教师评价功能迁移说明

这份文档记录 iOS 版新增的教师评价实现，方便在安卓项目复刻同一套接口流程。对应代码主要在：

- `loveaceios/Service/TeacherEvaluationService.swift`
- `loveaceios/ViewModel/TeacherEvaluationViewModel.swift`
- `loveaceios/Models/TeacherEvaluationModels.swift`

## 可复制给安卓项目 Agent 的提示词

```text
我有一个 Android 项目，需要参考本文件实现“自动教师评价”功能。请先理解代码和需求，不要直接开改。

工作流程要求：
1. 先阅读项目里登录、网络请求、Cookie/session、教务系统相关代码，理解现有架构和入口。
2. 阅读本文件，提炼需要迁移到 Android 的接口、HTML 解析、表单构造、并发节流和提交验证逻辑。
3. 制定一个短任务清单，说明会新增/修改哪些文件、如何接入现有登录态、如何验证。
4. 先和我确认方案；我可能会在过程中反复介入修改方向，你需要暂停、更新计划，再继续做最小正确改动。
5. 实现时只改和教师评价相关的代码，不要顺手重构无关模块。
6. 完成后运行项目可用的 build/test/lint；如果能装模拟器或真机，启动并手动检查入口和关闭态/课程列表状态。

功能目标：
- 复用现有教务登录态和 CookieJar，不重新实现登录。
- 按本文件接口拉取评教课程，能区分“评价暂未开启”、待评和已评。
- 用 Jsoup 解析评价页 HTML，生成和 iOS 版一致的提交表单。
- 单选题按“高分为主、少量随机”的策略选择答案。
- 文本题根据题目类型生成中文评价文本，并做基础合法性校验。
- 执行策略保持：每 6 秒启动一个任务，每门课程等待 140 秒后提交，提交后重新拉课程列表验证是否已评。
- UI 需要明确提示用户保持 App 前台，并提供取消/停止能力；已提交评价无法撤回。

请把实现细节、接口字段名和注意事项优先对齐本文，不要自行纠正接口里的历史拼写，例如 coureSequenceNumber。
```

## 前置条件

- 需要已经完成校园网关 + UAAP/教务登录，并复用同一个 Cookie 会话。
- 所有 POST 都是 `application/x-www-form-urlencoded`。
- 当前 iOS 请求使用移动 Safari UA：
  `Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1`
- 教务评教 base URL：
  `http://jwcxk2-aufe-edu-cn.vpn2.aufe.edu.cn:8118`

## 总流程

1. GET 评价首页，检测评教是否开放，并解析 `tokenValue`。
2. POST 课程列表接口，拿到待评/已评课程。
3. 对每门待评课程访问评价页，拿问卷 HTML。
4. 解析单选题、文本题和页面隐藏字段。
5. 生成提交表单。
6. 每个任务等待 140 秒后提交。
7. 提交后重新拉课程列表验证该课程是否变成“已评”。

## 1. 评价首页与关闭态

请求：

```http
GET /student/teachingEvaluation/evaluation/index
```

用途：

- 获取页面级 `tokenValue`。
- 判断评教是否未开放。

`tokenValue` 解析优先级：

1. `input#tokenValue` 的 `value`
2. `input[name=tokenValue]` 的 `value`
3. 正则兜底：`(?:id|name)=["']tokenValue["'][^>]*value=["']([^"']+)["']`

关闭态不要用全局 `html.contains("评估开关已关闭")`，因为脚本或隐藏模板里也可能包含这段文字。当前只检查页面内容区 alert：

- `#page-content-template .alert`
- `.page-content .alert`
- `.main-content .alert`

如果这些 alert 文本包含 `评估开关已关闭`，认为评教未开放，UI 显示“评价暂未开启”。

## 2. 课程列表

请求前先执行一次“评价首页与关闭态”流程，确保 token 和开放状态有效。

请求：

```http
POST /student/teachingEvaluation/teachingEvaluation/search?sf_request_type=ajax
Content-Type: application/x-www-form-urlencoded

optType=1&pagesize=50
```

响应是 JSON，课程数组在 `data`。

课程字段映射：

| App 字段 | JSON 来源 | 说明 |
| --- | --- | --- |
| `name` | `evaluationContent` | 课程/评价内容名称 |
| `teacher` | `evaluatedPeople` | 被评教师名 |
| `evaluatedPeople` | `evaluatedPeople` | 访问评价页时原样提交 |
| `evaluatedPeopleNumber` | `id.evaluatedPeople` | 被评人编号；字段名看起来像名字但实际当编号用 |
| `coureSequenceNumber` | `id.coureSequenceNumber` | 注意接口字段拼写是 `coure`，不要改成 `course` |
| `evaluationContentNumber` | `id.evaluationContentNumber` | 评价内容编号 |
| `questionnaireCode` | `questionnaire.questionnaireNumber` | 问卷编号 |
| `questionnaireName` | `questionnaire.questionnaireName` | 问卷名称 |
| `isEvaluated` | `isEvaluated == "是"` | 是否已评 |

课程稳定匹配 ID：

```text
evaluatedPeopleNumber + "_" + coureSequenceNumber + "_" + evaluationContentNumber + "_" + questionnaireCode
```

如果稳定 ID 不可用，验证时 fallback 到：`legacyId == evaluatedPeopleNumber && evaluationContentNumber 相同`。

## 3. 访问评价页

请求：

```http
POST /student/teachingEvaluation/teachingEvaluation/evaluationPage
Content-Type: application/x-www-form-urlencoded

count=<本批待评课程总数>
evaluatedPeople=<课程.evaluatedPeople>
evaluatedPeopleNumber=<课程.evaluatedPeopleNumber>
questionnaireCode=<课程.questionnaireCode>
questionnaireName=<课程.questionnaireName>
coureSequenceNumber=<课程.coureSequenceNumber>
evaluationContentNumber=<课程.evaluationContentNumber>
evaluationContentContent=
tokenValue=<首页解析到的 tokenValue>
```

响应是问卷 HTML。HTTP 200 才继续解析。

## 4. 问卷 HTML 解析

### 页面元数据

从 HTML 里提取：

| 字段 | 来源 |
| --- | --- |
| `title` | `div.title`，否则 `h1`，否则 `h2` |
| `tokenValue` | `input[name=tokenValue]` 或 `input#tokenValue` |
| `questionnaireCode` | `input[name=wjdm]` |
| `evaluatedPeopleNumber` | `input[name=bprdm]` |
| `evaluationContent` | `input[name=pgnr]` |
| `evaluatedPerson` | `td` 文本包含“被评人”或“教师”时，取下一个 sibling 的文本 |

最终提交时优先用课程列表里的编号；如果课程字段为空，再用这些隐藏字段兜底。

### 单选题

选择所有 `input[type=radio]`，按 `name` 分组；每个不同的 `name` 是一道题。

单选项字段：

- `key`：radio 的 `name`
- `value`：radio 的 `value`
- `score` / `weight`：把 `value` 按 `_` 分割，取前两段。例如 `100_1` 表示 score=100、weight=1.0。
- `label`：优先 `label[for=<input id>]`，其次父级 `label`，最后取所在 `td` 文本。

题干/类别提取：

- 找 radio 所在的祖先 `tr`。
- 类别优先取该行 `td[rowspan]` 文本。
- 题干取该行第一个“不含 radio、文本长度 > 5”的 `td` 文本。
- 如果本行没有题干，向前找 previous `tr`，取第一个文本长度 > 5 的 `td`。

### 文本题

选择所有 `textarea`，每个 textarea 是一道文本题。

- `key`：textarea 的 `name`
- 题干：优先 textarea 所在 `td` 的前一个 sibling 文本；否则本 `td` 文本；否则上一行第一个长度 > 3 的 `td` 文本。
- 必填：`name == "zgpj"` 或包含 `zgpj`。
- 类型判断：
  - `zgpj`：overall
  - 题干包含 `启发` 或 `启示`：inspiration
  - 题干包含 `建议`、`意见` 或 `改进`：suggestion
  - 其他：general

## 5. 生成提交表单

基础表单：

```text
optType=submit
tokenValue=<评价页 HTML 里的 tokenValue>
questionnaireCode=<课程.questionnaireCode 或隐藏字段 wjdm>
evaluationContent=<课程.evaluationContentNumber 或隐藏字段 pgnr>
evaluatedPeopleNumber=<课程.evaluatedPeopleNumber 或隐藏字段 bprdm>
count=<本批待评课程总数>
```

然后追加：

- 每道单选题：`<radio.name>=<选中的 radio.value>`
- 每道文本题：`<textarea.name>=<生成的中文评价文本>`

注意：提交用的 `tokenValue` 是评价页 HTML 里的 token，不是首页 token。

## 6. 单选项选择策略

当前策略是“高分为主，少量随机”：

1. 按 `weight` 从高到低排序。
2. 如果存在 `weight == 1.0` 的满权重选项，80% 概率从满权重选项里随机选一个。
3. 剩下 20% 概率，如果存在第二高权重组，就从第二高权重组选一个。
4. 如果没有第二高权重组，选最高权重项。

这样可以避免所有答案完全一致，同时整体保持较高评价。

## 7. 文本答案生成

根据文本题类型从固定文案池随机取一句：

- inspiration：偏“启发/收获”。
- suggestion：偏“意见建议/无明显建议”。
- overall/general：偏“总体评价”。

当前校验规则：

- 至少 4 个字符。
- 不包含空格；提交前会移除空格。
- 不能出现连续 3 个相同字符。
- 最多重试随机 3 次，仍不满足就提交最后一次结果。

可复用示例文案：

```text
老师授课有条理有重点，教会我做事要分清主次、抓住关键的思维方法
老师讲课很好，很认真负责，我没有什么建议，希望老师继续保持现有的教学方式
老师讲课认真负责，课程内容充实丰富，理论与实践结合得很好，让我收获颇丰，对专业知识有了更深入的理解
```

## 8. 提交评价

请求：

```http
POST /student/teachingEvaluation/teachingEvaluation/assessment?sf_request_type=ajax
Content-Type: application/x-www-form-urlencoded

<第 5 步生成的完整表单>
```

响应 JSON：

```json
{
  "result": "success",
  "msg": "..."
}
```

成功条件：`result == "success"`。否则把 `msg` 作为失败原因。

## 9. 提交后验证

每门课提交成功后，重新调用课程列表接口：

1. 用稳定 ID 匹配原课程。
2. 找到对应课程后检查 `isEvaluated == "是"`。
3. 如果服务器没有确认已评，任务标记失败：`评教未生效，服务器未确认`。

整批任务结束后再刷新一次课程列表。

## 10. 并发/节流策略

iOS 版首版使用以下执行策略：

- 只对 `isEvaluated == false` 的课程创建任务。
- 每 6 秒启动一个新任务。
- 每个任务流程：访问评价页 -> 解析问卷 -> 生成答案 -> 等待 140 秒 -> 提交 -> 重新拉列表验证。
- 任务是交错并发的，不是完全串行：后一个任务会在前一个任务还在 140 秒等待时启动。
- UI 提醒用户保持 App 前台；取消时，已提交的不会撤回，未完成任务标记为取消/失败。

伪代码：

```kotlin
val pending = courses.filter { !it.isEvaluated }
for ((index, course) in pending.withIndex()) {
    if (index > 0) delay(6_000)
    launch {
        val form = prepareEvaluation(course, pending.size)
        delay(140_000)
        submitEvaluation(form)
        verifyCourseEvaluated(course)
    }
}
```

## 安卓实现建议

- 用一个 `TeacherEvaluationService` 管接口和 HTML 解析；用 ViewModel 管任务队列、倒计时、日志和取消。
- HTML 解析建议用 Jsoup；选择器和字段名按本文保持一致。
- HTTP 客户端要和登录模块共享 CookieJar。
- 表单字段名不要自行“纠正”拼写，尤其是 `coureSequenceNumber`。
- 关闭态检测只看内容区 alert，不要全 HTML 字符串搜索。
- 每次 Test/调试优先先跑“只加载课程列表”和“只访问评价页解析表单”，确认字段齐全后再开放提交按钮。
