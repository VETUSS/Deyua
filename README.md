# Deyva

Android 端的胶片模拟冲洗应用。导入一张照片，选择胶片与相纸，由物理引擎依次模拟
负片曝光、显影、印制与相纸显影，而不是套一层滤镜。

引擎是 [SpektraLab](https://github.com/JamesQiu2005/SpektraLab) 的 Android 移植；
胶片过程模型、28 个实测色彩 profile 与打印 LUT 来自
[spektrafilm](https://github.com/andreavolpato/spektrafilm)（Andrea Volpato）。

## 下载

安装包发布在 [Releases](https://github.com/VETUSS/Deyua/releases) 页面。最新版本：

**[Deyva v0.8.93-r32](https://github.com/VETUSS/Deyua/releases/download/v0.8.93-r32/Deyva-v0.8.93-r32.apk)** ·
24,945,069 字节 · Android 7.0+ · arm64-v8a · `com.spektra.android`

侧载安装，需允许「安装未知来源应用」。下载后可核对完整性：

```
SHA-256  e1f392f29982a4fe81c8afab854423bfca71c3b375f4d53d112d4fb4fffce958
```

官方构建的签名证书 SHA-256 为
`bd5b1581425b953da52ab9550bf5149c52c30277a36d8358443cb0fa358c8236`，
应用内「关于 Deyva」会显示当前包是官方构建还是第三方重签版本。

## 许可

| 部分 | 许可 |
|---|---|
| 应用与引擎 | **GPL-3.0-or-later** |
| 胶片与相纸 profile、打印 LUT | **CC BY-SA 4.0** |

本仓库即 GPL-3.0 第 6 条所称的对应源码：任何经官方渠道分发的安装包，其完整
可构建源码都在这里。许可证全文见 [LICENSE](LICENSE) 与
[android/app/src/main/assets/notices/](android/app/src/main/assets/notices/)。

profile 与 LUT 是 CC BY-SA 4.0 授权的开放数据：使用与再分发须署名原作者、
保留相同许可。它们是开放内容，不应对其施加技术限制。详见
[NOTICE.md](NOTICE.md)。

## 构建

### 环境

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 17 | Gradle 与 AGP 8.x 要求 |
| Android SDK | platform 34、build-tools 34.0.0 | `compileSdk = 34` |
| Android NDK | 27.2.12479018 | `app/build.gradle.kts` 中已固定此版本 |
| CMake | 3.22.1 | 由 SDK Manager 安装 |
| Gradle | 8.7 | 仓库未附 wrapper，需自行安装 |

`minSdk = 24`，构建 ABI 为 `arm64-v8a` 与 `x86_64`。

### 步骤

```bash
git clone https://github.com/VETUSS/Deyua.git
cd Deyva

# 1. 获取 LibRaw 0.22.2 源码（约 20 MB，需联网；落点 upstream/build/deps）
bash upstream/engine/setup-libraw.sh
# Windows: powershell -ExecutionPolicy Bypass -File upstream\engine\setup-libraw.ps1

# 2. 指向本机工具链
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME=/path/to/android-ndk-r27c

# 3. 构建
gradle -p android :app:assembleDebug
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`

首次构建会编译 LibRaw 与引擎，耗时数分钟；增量构建约 1 分钟。

### 关于签名

`assembleDebug` 使用 AOSP 的公开 debug 密钥签名，它没有保密性——**任何人都能签出
同指纹的包**，只能用于自测，不可对外分发。正式分发请自建密钥：

```bash
keytool -genkeypair -keystore my-release.keystore -alias deyva \
  -keyalg RSA -keysize 4096 -validity 10000
```

`tools/pack.sh --release` 展示了完整的对齐、签名与指纹校验流程。注意应用内的
`BuildIntegrity.OFFICIAL_CERT_SHA256` 需与你的证书 SHA-256 一致，否则「关于」
页会把官方包也标成「非官方构建」。

## 目录结构

```
android/                      Android 应用
  app/src/main/java/…          Kotlin + Jetpack Compose 界面与导出链路
  app/src/main/cpp/            JNI 桥接（jni_bridge）与 RAW 解码（jni_raw）
  app/src/main/assets/engine/  运行时资源：SPIR-V、profile、常数表
  app/libs/heifwriter.jar      androidx.heifwriter（本地 jar，避免 AAR 的 minSdk 污染）
upstream/engine/              引擎源码（含 Android 移植改动）
  src/                        管线、色彩、GPU 后端
  resources/                  profile 与常数表原始数据
  cmake/                      依赖构建定义
tools/                        打包脚本
```

引擎以预编译 `spektrafilm_engine.so` 的形式放在
`android/app/src/main/jniLibs/<abi>/`，由 `CMakeLists.txt` 作为 imported target 链接。
若修改了 `upstream/engine/src`，需要按 `upstream/engine/build-*.sh` 重新构建该
`.so` 并替换。

## 导出与元数据

导出支持 JPEG / PNG / 16 位 TIFF / HEIF / Ultra HDR JPEG。成片会写入拍摄参数与
冲洗配方（EXIF `ImageDescription`，PNG 为 `tEXt` 块），并继承源片的相机、镜头、
曝光与 GPS 信息。

应用读取自身 APK 的签名证书指纹与安装来源，在「关于」页区分官方构建与二次打包。
**校验不通过只作提示，不限制任何功能**——GPL-3.0 第 3 条禁止用技术手段否认用户
运行修改版的权利。若你修改了本应用，请一并更换包名或签名，以免与官方构建混同。

## 第三方

| 组件 | 许可 | 用途 |
|---|---|---|
| SpektraLab | GPL-3.0-or-later | 引擎 |
| spektrafilm profiles / LUTs | CC BY-SA 4.0 | 胶片与相纸数据 |
| LibRaw 0.22.2 | LGPL-2.1（本项目选此分支） | RAW 解码 |
| androidx / Jetpack Compose | Apache-2.0 | 界面框架 |
| Vulkan-Headers | Apache-2.0 | GPU 后端 |

LibRaw 以静态方式链接进 `libjni_raw.so`。LGPL-2.1 第 3 条允许将受该许可约束的库
改为按 GNU GPL 第 2 版或更高版本分发，因此其义务由本仓库 GPL-3.0 的源码提供义务
一并履行。许可证全文见 `android/app/src/main/assets/notices/LibRaw-LGPL-2.1.txt`。

## 致谢

- [Andrea Volpato](https://github.com/andreavolpato) —— spektrafilm 的胶片过程模型与实测色彩数据
- Hanze Qiu —— 补充的实测黑白 profile
- Dave Coffin —— dcraw，LibRaw 的基础
