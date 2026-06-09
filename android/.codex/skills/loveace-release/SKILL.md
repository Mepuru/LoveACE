---
name: loveace-release
description: >-
  LoveACE Android 应用的版本发布流程。当用户要求 bump version、编译发版、
  发布到渠道、编译测试版 debug APK 时使用此技能。
---

# LoveACE 发版流程

## 环境说明

- **本地无 Android 编译环境**，必须通过 SSH 到远程机器编译
- 远程机器：`linnian@100.113.5.7`
- 远程机器 GitHub 访问需要代理：`export https_proxy=http://127.0.0.1:7890 http_proxy=http://127.0.0.1:7890`
- 代码仓库（私有）：`https://github.com/Sibuxiangx/loveace-m3e.git`，remote 名称 `m3e`
- 远程项目路径：`~/LoveACE`
- 签名密钥：`app/loveace-release.jks`（已在 `.gitignore` 中，需通过 SCP 单独传输）
- SDK 路径已配置在远程 `local.properties`：`sdk.dir=/home/linnian/Android/Sdk`

## 版本号规则

文件：`app/build.gradle.kts`

```kotlin
versionCode = 10107    // 1_01_07 → 主版本_次版本_修订号
versionName = "1.1.7"
```

- `versionCode` 格式：`主*10000 + 次*100 + 修订`
- 每次发版 `versionCode` 和 `versionName` 同步递增

## 完整发版流程

### 1. 修改版本号

编辑 `app/build.gradle.kts`，递增 `versionCode` 和 `versionName`。

### 2. 提交并推送

```bash
git add -A
git commit -m "chore: bump version to X.Y.Z"
git push  # 推送到 m3e remote
```

### 3. 远程拉取代码（需代理）

```bash
ssh linnian@100.113.5.7 "export https_proxy=http://127.0.0.1:7890 http_proxy=http://127.0.0.1:7890 && cd ~/LoveACE && git pull"
```

### 4. 编译

**Release 版本**（用于正式发布）：

```bash
ssh linnian@100.113.5.7 "cd ~/LoveACE && ./gradlew assembleRelease 2>&1 | tail -15"
```

**Debug 版本**（用于日常测试）：

```bash
ssh linnian@100.113.5.7 "cd ~/LoveACE && ./gradlew assembleDebug 2>&1 | tail -15"
```

编译耗时：首次约 5 分钟，增量约 5-30 秒。设置 `block_until_ms` 至少 600000。

### 5. 下载 APK 到本地

```bash
# Release
scp linnian@100.113.5.7:~/LoveACE/app/build/outputs/apk/release/app-release.apk ~/Downloads/LoveACE-release-X.Y.Z.apk

# Debug
scp linnian@100.113.5.7:~/LoveACE/app/build/outputs/apk/debug/app-debug.apk ~/Downloads/LoveACE-debug-X.Y.Z.apk
```

### 6. 使用发布工具发布到渠道

```bash
cd tools/publish
uv run python cli.py release \
  --version X.Y.Z \
  --platform android \
  --file ~/Downloads/LoveACE-release-X.Y.Z.apk \
  --changelog "更新内容描述"
```

发布工具其他命令：

| 命令 | 用途 |
|------|------|
| `status` | 查看当前发布状态 |
| `announce --title "标题" --content "内容"` | 发布公告 |
| `clear-announce` | 清除公告 |
| `set-force --platform android --force true` | 设置强制更新 |

## OTA 测试流程

应用内置 OTA 功能，启动时和手动检查更新时会拉取 `manifest.json` 比对版本。

测试步骤：
1. 将版本号降低（如改为旧版号），编译 **debug** APK 给用户安装
2. 恢复正确版本号，编译 **release** 并通过发布工具发布
3. 用户打开旧版 App，即触发 OTA 弹窗

## 注意事项

- 远程 `git pull` 偶尔因网络问题失败，**加代理重试**即可
- 签名密钥 `*.jks` 不在 Git 中，首次需 SCP 传输：
  `scp app/loveace-release.jks linnian@100.113.5.7:~/LoveACE/app/`
- SSH 命令超时设置要充足，编译命令至少 `block_until_ms: 600000`
- SCP 下载 APK 约需 30-50 秒，`block_until_ms: 120000`
