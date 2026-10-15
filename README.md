# AR 测距 (复刻 com.qh.measure「测距」功能)

一个可直接编译运行的 ARCore 测距 Android 工程,复刻「扫描测量全能王」(`com.qh.measure` v1.7.8)
里的【测距】功能:点两个点得到 3D 空间直线距离,支持折线连测、多单位、清空重测。

---

## 一、对原 APK 的逆向结论(证据均在 `base.apk` 静态包内)

| # | 结论 | 证据 |
|---|------|------|
| 1 | 包名 `com.qh.measure`(360 出品「扫描测量全能王」),版本 1.7.8.1001 | AndroidManifest `package=`、`assets/build.properties` |
| 2 | 360 加固:`classes.dex` 里只有壳类 `erusaem.hq.moc.StubApp`(名字反转),全部 native 调用指向 `libjgcqm*.so`,真实 dex 运行时解密 | jadx 反编译 `StubApp.java`(含 `DexFile`、`AssetManager` 相关 native 方法),`classes.dex` 无 `Lcom/qh/measure/...` 字符串 |
| 3 | 架构 = 壳 App + 动态插件,插件名就叫 **`measure`**(测距)| `assets/qbuild_cfg.dat`:`QBUILD_TASK_NAME=plugin_measure`、`QBUILD_PROJECT_NANE=扫描测量全能王-Android`;`bttets/plugins/measure.jar` 为 1 字节运行时占位 |
| 4 | 测距功能是 **AR 路线**,做了三引擎适配:Google ARCore / EasyAR / 华为 AREngine | `lib/arm64-v8a/` 内 `libarcore_sdk_c.so`、`libEasyAR.so` (12MB)、`libhuawei_arengine_ndk.so` |
| 5 | 交互文案(需装"面向 AR 的 Google Play 服务")出现在 `res/values-zh`,来自 ARCore SDK 本体 | `resources.arsc` 解码后的 `values-zh/strings.xml` |

**测距功能的还原交互** :`ARCore → 平面/特征点命中检测(frame.hitTest) → 锚点落点 → 相邻锚点间距离(withIdes vidéo 细长立方体 + 浮动文字标签)`。

> 由于 360 加固导致业务 dex 运行时才解密,插件代码无法纯静态恢复;
> 本工程的生命价值:1) 复现可用的功能;2) 结构对齐结论的完整推导已备忘在上述证据里。

---

## 二、工程结构

```
ARMeasure/
├── app/
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml          # CAMERA + com.google.ar.core(required)
│       ├── java/com/qh/armeasure/
│       │   ├── MainActivity.java        # 权限 + ARCore 可用性检查
│       │   └── ArMeasureActivity.java   # AR 测距核心逻辑
│       └── res/                         # 布局/文案/主题/图标
├── .github/workflows/android.yml        # CI 云端出 APK
├── build.gradle, settings.gradle, gradle.properties
└── README.md
```

## 三、怎么跑

### 本地构建
1. 用 Android Studio 打开本目录(会自动 sync);
2. 手机需要:Android 7.0+,且支持 ARCore(去应用市场装好「面向 AR 的 Google Play 服务」);
3. 点 Run 直接装到手机(工程未带 gradle wrapper,命令行请用 Android Studio 内置 Gradle 或自己 `gradle assembleDebug`)。

### 云端构建(本机没有 Android SDK 时)
1. 把本目录推到一个 GitHub 仓库并触发 `.github/workflows/android.yml`;
2. 下载 artifact `ARMeasure-debug-apk` 即得 APK。

## 四、怎么定界(.net 低版本兼容)
- minSdk 24(ARCore 要求);compileSdk 34;Java 8。
- 已知 Sceneform 1.17.1 为 2020 年版本但仍在 Google Maven,无需迁移。

## 五、与原版对齐点 / 差异
- ✅ 复刻:AR 测距、多点连续测量、单位切换、清空、中央白色方框准星 + 实时预览尺条。
- ⛟ 差异:EasyAR/华为 AREngine 备用引擎未实现(如需,可参考 ARCore 的等价 API 迁移)。
- ⛞ 未复刻:原版的扫描/文档识别、身高测量、内置扫码、广告 SDK 等功能(不在本需求)。

## 五·v4 交互重做(2026-10)
对 v3 的六处硬伤修复,手感对齐原版:
1. **IPP 即时落点最高优先** —— 不再等平面识别/晃手机,点哪测哪(原 v3 平面优先导致"等白色网格")。
2. **落点即焊死** —— 命中瞬间 `createAnchor()`,数字不再随 IPP 精化漂移(原 v3 数字一直跳)。
3. **实时预览** —— 落第 1 点后,白框指哪,预览尺条 + 距离实时跟随,按下前就见数。
4. **标签固定世界尺寸**(10cm 宽)+ 沿尺条法线偏移 + 朝向相机 —— 远近一致、垂直面不飘。
5. **材质预渲染 + HUD 节流 150ms** —— 修卡顿。
6. **准星改白色方框**(原版样式),文案删掉"等白色网格/晃手机"。

## 六、遗留说明(逆向备案)
- 1 字节占位说明插件由服务端动态下发,研究黑盒行为需要真机脱壳(Frida on 已 root 设备 + libjgcqm 解密时机),超出本任务的静态范围。
- 索文件痕迹:`bttets/plugins/{measure,scanner,webview,accounts,mspay,operatex}.jar` 均为运行时拉取的插件占位符。
