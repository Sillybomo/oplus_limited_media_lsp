# 照片有限访问全开（OplusLimitedMedia）— ColorOS 全应用「允许有限访问」模块

> 🔥 让 targetSdk<34 的老应用（美团 / 夸克等）在 ColorOS 上也能用 AOSP 的「允许有限访问」三态媒体权限——只给应用你亲手选的那几张照片，对齐 vivo 与 QQ 的三选项体验
>
> @author bomo

> [!IMPORTANT]
> **目前仅在 一加 PLZ110（ColorOS 17 · CP2A.260605.016 · Android 16 基座 · LSPosed）上完整实测可用。**
> 模块钩的是 ColorOS/AOSP 框架的混淆类（PermissionController / SecurityPermission / MediaProvider），
> 不同 ColorOS 构建的混淆名可能不同——不匹配时钩子**静默跳过**（不生效，但绝不影响开机与系统稳定）。
> 适配反馈请附机型 + `grep OplusMediaLimited /data/adb/lspd/log/verbose_*.log` 输出，随缘更新。

[![Version](https://img.shields.io/badge/version-v0.31-blue)](../../releases)
[![Platform](https://img.shields.io/badge/platform-ColorOS%2017%20%2F%20Android%2016-green)]()
[![Requires](https://img.shields.io/badge/需要-root%20%2B%20LSPosed-orange)]()
[![License](https://img.shields.io/badge/license-MIT-lightgrey)](LICENSE)

---

## 这是什么

ColorOS 17 上，只有 `targetSdk>=34` 的应用（如 QQ）在「照片和视频」权限里才有三态可选：
**始终全部允许 / 允许有限访问 / 不允许**。而 targetSdk 较低的老应用（美团、夸克等 targetSdk=30）
只有「全部允许 / 不允许」两态——想只给它看几张图？没这个选项。

这不是「安卓版本不够」：ColorOS 17 与 vivo OriginOS 16 同为 Android 16 基座，
差异纯粹是 **OEM 主动保留了 AOSP 的 `targetSdk>=34` 门槛**。vivo 把它拆了，OPPO 照抄原生。

装上本模块后，**所有应用**（不分 targetSdk）在照片/视频权限里都出现「允许有限访问」，
选择后应用只能读到你亲手勾选的那几张图/视频，其余一律不可见。

**实测（一加 PLZ110，真实操作验证）**：

| 应用（targetSdk） | 装模块前 | 装模块后 |
| --- | --- | --- |
| QQ（34） | 三态，有限访问可用 | 三态，原生路径零改动、不受影响 ✅ |
| 夸克（30） | **只有两态** | **三态，选图后相册只显示所选** ✅ |
| 美团（30） | **只有两态** | **三态，限制成功** ✅ |

## 工作原理

ColorOS 把「有限访问」锁在四道关卡后，任缺一道都会失败（看不到三态 / 点了不生效 / 误伤正常应用）。
模块用单一 `Module.java` 打通四层（实机反编译 PermissionController / SecurityPermission / MediaProvider 定位）：

```
① UI 门槛     hook 设置页三态显隐判断 → 强制照片组永远出三态
② 弹窗决策    hook 弹窗类型决策 → legacy 发图只走三态照片选择窗，
                              两态存储/音频窗静默跳过（不进弹窗列表）
③ 有限访问判据 「授 VISUAL + 撤 IMAGES/VIDEO」组合动作 → 写持久标记，
                              区分「真·有限访问」与「系统 grandfather 补发」
④ 执行层      hook MediaProvider → 标记应用强制走 AOSP media_grants 过滤分支
                              → 应用只见用户所选
```

关键钩点（CP2A.260605.016 混淆名，ROM 更新可能变化，不匹配时静默跳过）：

| 层 | 进程 | 钩点 | 作用 |
| --- | --- | --- | --- |
| ① | securitypermission | `f0.U(Context, da.c)` | 列表页三态显隐门槛 |
| ① | permissioncontroller | `AppPermissionViewModel.d0(z3.b)` | 详情页三态门槛 |
| ② | permissioncontroller | `m4.g.k / m` | 弹窗 partial 门槛（VISUAL 组放行） |
| ② | permissioncontroller | `m4.g.c(...)` → `Prompt.B` | 两态窗返回 NO_UI_REJECT_THIS_GROUP，不进弹窗列表 |
| ③ | permissioncontroller | `updatePermissionFlags` / `setPermissionFgs` | 组合动作判据写 `Settings.Global bomo_ml_<pkg>` 标记 |
| ④ | mediaprovider | `checkCallingPermissionUserSelected` | 标记应用强制 true，绕开 targetSdk<=32 短路 |
| ④ | mediaprovider | `AccessChecker.hasAccessToCollection` | 标记应用图片/视频集合强制 false → 走 media_grants 过滤 |

**已证伪的假路径**（省你二次踩坑的时间）：

| 候选路线 | 证伪原因 |
| --- | --- |
| system_server 钩 `checkPermission(String,int,int)` | A16 上 3 参签名不存在，钩子 not found |
| 直接把 legacy 弹窗 Prompt 强改 SELECT_PHOTOS | 三选项渲染出来但**点不动**，合并超窗授权处理拿不到 VISUAL 组对象 |
| 用 VISUAL 权限位当有限访问判据 | ColorOS 给历史授权老应用 **grandfather 补发** VISUAL，会误伤正常全量应用 |
| 3s 扫描器回填/清 policy 位 | 制造扫描风暴污染 BasePermission，导致设置行置灰；最终方案不需要它 |

## 安装

1. [Releases](../../releases) 下载 `module.apk` 安装（或自行构建，见下）
2. LSPosed 管理器 → 模块 → 启用「照片有限访问全开」
   （作用域已静态声明为 `android` / `com.oplus.securitypermission` /
   `com.android.permissioncontroller` / `com.android.providers.media.module`，自动勾选）
3. **重启手机**（推荐——装机 dex2oat 可能触发 LSPosed daemon 崩溃导致不注入，重启最稳）

生效后：设置 → 应用 → 任意应用 → 权限 → 照片和视频，即可看到三态。

回滚：LSPosed 禁用模块 → 重启，零残留（模块只写 `Settings.Global bomo_ml_<pkg>` 标记，
不写任何分区/系统文件；卸载前可 `settings delete global bomo_ml_<pkg>` 清标记）。

## 兼容性

| 项目 | 状态 | 说明 |
| --- | --- | --- |
| 一加 PLZ110 · ColorOS 17 · Android 16 | ✅ **唯一实测** | 美团/夸克两态→三态，选图限制成功 |
| 其他 ColorOS 17 机型 | ⚠️ 自测 | 同构建混淆名大概率一致，欢迎反馈 |
| 更早 ColorOS 版本 | ⚠️ 未验证 | 混淆类/方法名可能不同；不匹配时静默跳过，无害 |
| 非 OPPO/一加设备 | ❌ | 钩点为 ColorOS 定制类，加载即跳过，不影响系统 |

要求：root（KernelSU/Magisk 均可）+ LSPosed（xposedminversion 93）。

## 已知行为与 FAQ

**Q: QQ 之类本来就有三态的应用会受影响吗？**
A: 不会。QQ（targetSdk=34）走系统原生路径，模块零改动，行为不变。

**Q: 为什么要「组合动作」判据，不能直接看 VISUAL 权限位？**
A: ColorOS 会给所有历史授权过媒体权限的老应用**自动补发 VISUAL**（grandfather），
单看权限位会把企业微信、饿了么等正常全量应用误判成「有限访问」。所以必须用
「授 VISUAL 且 IMAGES/VIDEO 未授予」这类组合动作来区分是用户真选了有限访问，还是系统补发的。

**Q: 改对了代码却像没生效、还是弹两态窗？**
A: 大概率是装机（dex2oat）触发了 LSPosed daemon 的 SQLite I/O 崩溃，导致模块整体不注入。
**重启手机**即可恢复。这是本项目最大的坑，装机后请务必重启验证。

**Q: 系统 OTA 后失效？**
A: 有可能。ColorOS 改了混淆类名/方法名时钩子静默跳过（不卡系统），
看 `grep OplusMediaLimited /data/adb/lspd/log/verbose_*.log` 有无 `hook installed` 即可判断，欢迎带日志提 issue。

## 项目结构

```
├── module/
│   ├── src/com/bomo/oplusmedialimited/
│   │   ├── Module.java          四层钩子核心（UI门槛/弹窗决策/有限访问判据/MediaProvider执行层）
│   │   └── StatusActivity.java  启动器页（LSPosed Manager 识别所需）
│   ├── src/stubs/               Xposed API 编译期桩（只进 -cp，不进 dex）
│   ├── res/values/arrays.xml    静态作用域 xposed_scope 四项
│   ├── assets/xposed_init       入口类声明: com.bomo.oplusmedialimited.Module
│   ├── AndroidManifest.xml      versionName 0.31 / xposedminversion 93
│   └── build_install.sh         无 Gradle 构建脚本（keystore 不入库）
└── LICENSE
```

构建（无 Gradle，Git Bash 跑）：
`javac(-bootclasspath android.jar) → d8 → aapt2 compile+link → 注入 classes.dex + xposed_init → zipalign → apksigner → adb install -r`。
SDK 路径与 keystore 口令均通过环境变量传入（`ANDROID_SDK` / `KS_PASS` / `SERIAL`），release 签名 keystore 不入库（走 `.gitignore` 脱敏）。

## 免责声明

强制放开「有限访问」门槛属于对 ColorOS 私有权限逻辑的非官方修改，系统 OTA 后行为不保证。
钩子静默跳过设计保证不匹配时不影响系统稳定，但由此产生的任何权限/兼容性问题与作者无关。风险自知，介意勿装。

## 致谢

- [LSPosed](https://github.com/LSPosed/LSPosed) 与 libxposed
- 参照实现：vivo 的 `video_and_image_extension` 全局开关旁路 + `ImageAccessHelper` 执行链路（用于反推 ColorOS 缺什么）

## License

MIT © bomo
