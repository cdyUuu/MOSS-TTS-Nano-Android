# MOSS TTS Nano Android

基于 [OpenMOSS/MOSS-TTS-Nano](https://github.com/OpenMOSS/MOSS-TTS-Nano) 的 Android 端侧语音合成应用。采用 ONNX Runtime 在设备本地进行推理，无需联网即可完成语音合成和声音克隆。

## 功能特性

- 完全离线运行，保护隐私
- 内置多种预设音色
- 支持声音克隆（录制/导入参考音频）
- 支持多个克隆音色管理与自定义命名
- 流式合成，实时播放
- 支持模型导入导出
- 多镜像源下载，国内加速
- 深色模式适配
- Material 3 设计

## 系统要求

- Android 8.0 (API 26) 及以上
- 至少 2GB RAM
- 推荐 4GB 以上存储空间（用于模型文件）
- arm64-v8a 或 armeabi-v7a 架构

## 下载安装

从 Releases 页面下载最新 APK。

首次启动后，在「模型管理」页面下载模型文件（约 400MB）。

## 构建

### 环境要求

- Android Studio Hedgehog 或更高版本
- JDK 17
- Android SDK 34

### 构建步骤

```bash
# 克隆仓库
git clone https://github.com/cdy123456/MOSS-TTS-Nano-Android.git
cd MOSS-TTS-Nano-Android

# 构建 Debug 版本
./gradlew assembleDebug

# 构建 Release 版本
./gradlew assembleRelease
```

构建产物位于 `app/build/outputs/apk/`。

## 项目结构

```
app/src/main/java/com/mosstts/app/
├── MainActivity.kt          # 主Activity
├── data/
│   ├── ModelManager.kt      # 模型管理
│   ├── ModelDownloader.kt   # 模型下载
│   ├── ClonedVoiceStore.kt  # 克隆音色存储
│   └── PreferencesManager.kt
├── engine/
│   ├── MossTTSEngine.kt     # TTS推理引擎
│   └── StreamingAudioPlayer.kt
├── ui/screens/
│   ├── HomeScreen.kt        # 语音合成
│   ├── VoiceCloneScreen.kt  # 音色克隆
│   ├── ModelsScreen.kt      # 模型管理
│   └── SettingsScreen.kt    # 设置
├── viewmodel/
│   ├── TTSViewModel.kt
│   └── ModelViewModel.kt
└── util/
    ├── AppLogger.kt
    └── ModelExporter.kt
```

## 技术栈

- Kotlin
- Jetpack Compose
- Material 3
- ONNX Runtime
- kotlinx.coroutines
- OkHttp

## 开源协议

本项目基于 [Apache License 2.0](LICENSE) 开源。

MOSS-TTS-Nano 模型版权归 OpenMOSS 团队所有。

## 致谢

- [OpenMOSS](https://github.com/OpenMOSS) - MOSS-TTS-Nano 模型
- [ONNX Runtime](https://github.com/microsoft/onnxruntime) - 推理引擎

## 免责声明

本项目仅供学习和研究使用。使用本项目生成的音频内容需遵守相关法律法规，不得用于违法用途。
