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

## 六·v5 精度优先改造

本版撤回了 v4 中“IPP 最高优先”和“落点即焊死就能稳定准确”的错误承诺：ARCore 锚点只把点关联到 AR 世界坐标，并不能让初始估距变成真实测量值。

- **默认精度优先**：先用普通 ARCore hit test 选择已跟踪的平面、深度点或视觉特征点；仅当常规命中没有结果时，才用 Instant Placement 作为后备。后备估距按你给的约 2 m 场景设置；落点需在准星下保持稳定约 0.3 秒，仍处于屏幕空间估距时不允许当作正式点提交。
- **快速估算可选**：底部模式键可切换到快速估算；仅有估算落点时允许继续，但落点标签和最终数值都会带 `≈`。
- **按设备能力启用深度**：运行时查询 ARCore Depth API 支持情况，支持时启用；不假定 vivo S19 或其他机型一定有深度能力。
- **操作统一**：屏幕中心准星是唯一瞄准点，按“落点”提交；不再同时接受任意位置的屏幕点击。
- **反馈更诚实**：界面显示平面/深度/特征/估算命中类型；遇到低置信度估算点会阻止精度模式提交。
- **真机校验仍不可省**：普通相机 AR 的误差会受机型、光线、纹理、距离和持机移动影响；界面不虚构误差范围。建议用卷尺分别校验 0.5 m、1 m、2 m，并在同一位置重复测量后再判断是否满足用途。

> “精度优先”是落点策略，不是 ±1 cm 的保证。若 ARCore 不能提供可靠命中，正确做法是提示重扫/换角度，而不是输出一个看似精确的数字。

## 七、遗留说明(逆向备案)
- 1 字节占位说明插件由服务端动态下发,研究黑盒行为需要真机脱壳(Frida on 已 root 设备 + libjgcqm 解密时机),超出本任务的静态范围。
- 索文件痕迹:`bttets/plugins/{measure,scanner,webview,accounts,mspay,operatex}.jar` 均为运行时拉取的插件占位符。
