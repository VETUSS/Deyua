# Notices

Deyva 建立在两个开放作品之上。以下是署名、许可，以及各自要求随每份副本分发的内容。

## 一、引擎与应用程序

**SpektraLab** —— spektrafilm 胶片过程模型的原生 C++/Metal 移植
<https://github.com/JamesQiu2005/SpektraLab>
许可：**GPL-3.0-or-later**

Deyva 是 SpektraLab 的 Android 移植与二次开发，同样以 GPL-3.0-or-later 分发。

`upstream/engine/` 相对 SpektraLab 提交 `fd891c6` 有 Android 移植改动，主要涉及
GPU 后端（Vulkan）、RAW 解码、构建脚本与管线接口；差异可直接用 `diff -r` 对照上游
查看。许可证全文见 [LICENSE](LICENSE)。

## 二、胶片与相纸 profile、打印 LUT

**spektrafilm** —— <https://github.com/andreavolpato/spektrafilm>
作者：Andrea Volpato
许可：**CC BY-SA 4.0**

随包分发的 profile 中，28 个与上游逐字节一致，未经修改。spektrafilm 项目另行补充
的实测黑白 profile 由 Hanze Qiu 提供。此外 Deyva 还收录了若干上游资源目录中未包含
的 profile（Cinestill 50D / 800T 等），同属该数据集。

按 CC BY-SA 4.0 的要求：使用与再分发须署名原作者、保留本许可、衍生作品以相同许可
分发。这些 profile 与 LUT 是开放数据，请勿对其加设技术限制。许可证全文见
[android/app/src/main/assets/notices/SPEKTRAFILM-CC-BY-SA-4.0.txt](android/app/src/main/assets/notices/SPEKTRAFILM-CC-BY-SA-4.0.txt)。

## 三、其他依赖

| 组件 | 许可 | 用途 |
|---|---|---|
| LibRaw 0.22.2 | LGPL-2.1（LibRaw 为 LGPL-2.1 / CDDL-1.0 双许可，本项目选 LGPL-2.1 分支） | RAW 解码 |
| metal-cpp | Apache-2.0 | Metal 后端（上游） |
| androidx / Jetpack | Apache-2.0 | 界面框架 |
| Vulkan-Headers | Apache-2.0 | GPU 后端 |

LibRaw 的许可说明：Deyva 整体以 GPL-3.0-or-later 分发。LGPL-2.1 第 3 条允许将受
该许可约束的库改为按 GNU GPL 第 2 版或更高版本分发，因此 LibRaw 的义务由本仓库
GPL-3.0 的源码提供义务一并履行。这也是 LibRaw 以静态方式链接进 `libjni_raw.so`
仍然合规的原因。全文见
[android/app/src/main/assets/notices/LibRaw-LGPL-2.1.txt](android/app/src/main/assets/notices/LibRaw-LGPL-2.1.txt)。

## 四、关于签名校验

Deyva 会读出自身 APK 的签名证书指纹与安装来源，用来区分官方构建与二次打包。
校验不通过只作提示，不限制任何功能——GPL-3.0 第 3 条禁止用技术手段否认用户运行
修改版的权利。若你修改了 Deyva，请一并更换包名或签名，以免与官方构建混同。
