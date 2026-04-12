# APK 下载指南

## 方法 1：通过 GitHub 网页下载（推荐）

### 步骤：

1. **打开 GitHub Actions 页面**
   - 访问：https://github.com/jenaswift4-byte/project-fusion/actions
   - 你会看到最近的编译任务列表

2. **选择编译任务**
   - 找到状态为绿色 ✓ 的任务（表示成功）
   - 点击任务名称进入详情页

3. **下载 APK**
   - 滚动到页面**最底部**
   - 找到 "Artifacts" 区域
   - 点击以下任一文件下载：
     - `app-debug` - 调试版本（推荐用于测试）
     - `app-release` - 发布版本（优化版）

4. **解压文件**
   - 下载的是 ZIP 压缩包
   - 解压后得到 `.apk` 文件

---

## 方法 2：使用 PowerShell 脚本下载

### 前提条件：
- 已安装 PowerShell 7+
- 已登录 GitHub CLI（可选，用于认证）

### 使用脚本：

```powershell
# 在项目根目录执行
.\download-apk.ps1
```

### 如果提示认证错误：

1. **创建 GitHub Personal Access Token**
   - 访问：https://github.com/settings/tokens
   - 点击 "Generate new token (classic)"
   - 勾选 `repo` 权限
   - 生成并复制 Token

2. **设置环境变量**
   ```powershell
   $env:GITHUB_TOKEN = "你的 token"
   ```

3. **重新运行脚本**
   ```powershell
   .\download-apk.ps1
   ```

---

## 方法 3：使用 GitHub CLI 下载

### 安装 GitHub CLI：

```powershell
# Windows (使用 winget)
winget install GitHub.cli

# 或使用 Chocolatey
choco install gh
```

### 使用步骤：

```powershell
# 1. 登录 GitHub
gh auth login

# 2. 查看最近的编译任务
gh run list --repo jenaswift4-byte/project-fusion --limit 5

# 3. 下载最新成功的编译产物
gh run download --repo jenaswift4-byte/project-fusion --name app-debug

# 或下载发布版
gh run download --repo jenaswift4-byte/project-fusion --name app-release
```

---

## 方法 4：手动下载（如果 Actions 还未完成）

### 检查编译状态：

1. 访问：https://github.com/jenaswift4-byte/project-fusion/actions
2. 查看是否有正在运行的任务（黄色图标 ⏳）
3. 等待任务完成（变成绿色 ✓）

### 编译时间：
- 首次编译：约 8-12 分钟
- 后续编译：约 5-8 分钟

---

## 安装 APK 到手机

### 方法 A：使用 ADB（推荐）

```powershell
# 1. 确保手机已开启 USB 调试
# 2. 连接手机到电脑
# 3. 执行：
adb install "C:\Users\wang\Desktop\万物互联\apk-downloads\app-debug\app-debug.apk"

# 安装到所有 4 台手机：
adb -s 设备序列号 1 install app-debug.apk
adb -s 设备序列号 2 install app-debug.apk
adb -s 设备序列号 3 install app-debug.apk
adb -s 设备序列号 4 install app-debug.apk
```

### 方法 B：直接传输到手机

1. 将 APK 文件复制到手机
2. 在手机上打开文件管理器
3. 点击 APK 文件安装

---

## 常见问题

### Q: Actions 页面显示 "No runs found"
**A:** 说明还没有触发编译。请推送一个空提交来触发：
```bash
git commit --allow-empty -m "trigger build"
git push
```

### Q: 下载链接 404
**A:** 可能的原因：
- 编译还未完成（请等待）
- Artifact 已过期（超过 30 天）
- 需要登录 GitHub

### Q: 安装失败
**A:** 确保：
- 手机已开启 "未知来源" 安装权限
- 手机有足够的存储空间
- APK 文件完整未损坏

### Q: 应用闪退
**A:** 查看日志：
```bash
adb logcat | findstr "com.fusion.companion"
```

---

## 当前编译状态

查看最新编译状态，请访问：
https://github.com/jenaswift4-byte/project-fusion/actions

### 预期的编译产物：

- **Debug 版本**: ~50-80 MB
  - 包含所有调试功能
  - 未混淆
  - 可用于开发测试

- **Release 版本**: ~30-50 MB
  - 经过混淆优化
  - 体积更小
  - 可用于生产环境

---

**提示**: 首次下载可能需要一些时间，请耐心等待编译完成。
