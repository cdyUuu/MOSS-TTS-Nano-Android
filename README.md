# MOSS TTS Nano Android

基于 [OpenMOSS/MOSS-TTS-Nano](https://github.com/OpenMOSS/MOSS-TTS-Nano) 的 Android 端侧语音合成应用。采用 ONNX Runtime 在设备本地进行推理，无需联网即可完成语音合成和声音克隆。

## 功能特性

- 完全离线运行，保护隐私
- 内置多种预设音色
- 支持声音克隆（录制/导入参考音频）
- 支持多个克隆音色管理与自定义命名
- 合成音频保存与历史记录
- 支持模型导入导出
- 多镜像源下载
- 深色模式适配
- Material 3 设计

## 系统要求

- Android 8.0 (API 26) 及以上
- 至少 2GB RAM
- 推荐 4GB 以上存储空间（用于模型文件）

## 下载安装

从 [Releases](https://github.com/cdyUuu/MOSS-TTS-Nano-Android/releases) 页面下载最新 APK。

首次启动后，在「模型管理」页面下载模型文件（约 700MB）。

## 构建

```bash
git clone https://github.com/cdyUuu/MOSS-TTS-Nano-Android.git
cd MOSS-TTS-Nano-Android
./gradlew assembleRelease
```

构建产物位于 `app/build/outputs/apk/release/`。

## 技术栈

- Kotlin + Jetpack Compose + Material 3
- ONNX Runtime 端侧推理
- kotlinx.coroutines + OkHttp

## 开源协议

本项目基于 [Apache License 2.0](LICENSE) 开源。

MOSS-TTS-Nano 模型版权归 OpenMOSS 团队所有。

## 致谢

- [OpenMOSS](https://github.com/OpenMOSS) - MOSS-TTS-Nano 模型
- [ONNX Runtime](https://github.com/microsoft/onnxruntime) - 推理引擎
