# GitHub Actions 编译指南

## 自动编译 APK

本项目已配置 GitHub Actions 自动编译流程，每次推送到 main 分支时会自动触发编译。

### 触发条件

- 推送到 `main` 分支
- 创建 Pull Request 到 `main` 分支
- 创建 Tag（自动发布 Release）

### 编译产物

GitHub Actions 会自动生成以下文件：

1. **Debug APK**: `app-debug.apk`
   - 包含完整调试功能
   - 可用于开发和测试
   - 保留 30 天

2. **Release APK**: `app-release.apk`
   - 经过混淆和优化的版本
   - 可用于生产环境
   - 保留 30 天

### 下载编译产物

#### 方法 1：从 Actions 下载

1. 打开 GitHub 仓库：https://github.com/jenaswift4-byte/project-fusion
2. 点击 "Actions" 标签
3. 选择最新的编译任务
4. 在页面底部的 "Artifacts" 区域下载 APK 文件

#### 方法 2：从 Release 下载（创建 Tag 时）

1. 创建并推送 Tag：
   ```bash
   git tag v2.1
   git push origin v2.1
   ```

2. GitHub Actions 会自动创建 Release 并上传 APK
3. 访问仓库的 "Releases" 页面下载

### 手动触发编译

如果需要手动触发编译，可以创建一个空推送：

```bash
# 在本地仓库执行
git commit --allow-empty -m "trigger build"
git push
```

### 编译配置

- **JDK 版本**: 17
- **Android SDK**: 34
- **Build Tools**: 34.0.0
- **编译环境**: Ubuntu latest

### 本地编译

如果你想在本地编译，可以使用以下命令：

#### Windows

```powershell
cd android-companion
.\gradlew.bat assembleDebug
```

#### Linux/Mac

```bash
cd android-companion
chmod +x gradlew
./gradlew assembleDebug
```

编译产物位置：
- Debug: `android-companion/app/build/outputs/apk/debug/app-debug.apk`
- Release: `android-companion/app/build/outputs/apk/release/app-release.apk`

### 故障排除

#### 编译失败

如果 GitHub Actions 编译失败，可以：

1. 点击 Actions 中的失败任务
2. 查看完整的编译日志
3. 根据错误信息修复代码

#### 下载失败

如果 Artifact 下载失败：

1. 确保你已登录 GitHub
2. 检查 Artifact 是否还在保留期内（30 天）
3. 尝试重新编译

### 自动化发布

要启用自动发布功能，需要创建 Tag：

```bash
# 创建新版本
git tag -a v2.1.0 -m "Release version 2.1.0"
git push origin v2.1.0
```

GitHub Actions 会自动：
1. 编译 Debug 和 Release APK
2. 创建 GitHub Release
3. 上传 APK 到 Release

### 相关工作流文件

- `.github/workflows/build-apk.yml`: 编译配置

---

**注意**: 首次推送可能需要几分钟才能触发 GitHub Actions，请耐心等待。
