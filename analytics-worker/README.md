# LoveACE 遥测 Worker

这是 LoveACE 客户端使用的隐私友好遥测入口，部署在 Cloudflare Workers，并使用 D1 存储事件数据。

## 接口

- `GET /healthz`
- `POST /v1/events`

请求 `/v1/events` 时必须带上以下鉴权头：

- `Authorization: Bearer <ANALYTICS_API_KEY>`
- `X-LoveACE-Timestamp`
- `X-LoveACE-Nonce`
- `X-LoveACE-Signature`

签名内容：

```text
HMAC_SHA256(ANALYTICS_SIGNING_SECRET, timestamp + "." + nonce + "." + sha256(raw_body))
```

服务端只保存 `grade_prefix` 和加盐后的 `student_hash`，不会保存完整明文学号。

## 数据边界

- 不接收密码、完整学号或业务接口原始返回。
- 不接收成绩、课表、一卡通消费、门禁、评教等具体业务内容。
- 遥测不可用时客户端会静默忽略，不影响正常使用。

## 统计卡片

仓库根目录的 `assets/analytics-stats.svg` 由 `.github/workflows/update-analytics-stats.yml` 生成。该工作流会定时或手动读取 D1 的聚合统计，只提交 SVG 汇总卡片，不输出原始事件数据。
