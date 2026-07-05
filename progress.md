# ShadowsocksR-Android 改造记录

> 记录日期:2026-07-05
> 目标:将项目适配到最新版 Android(Android 15 / API 35),并修复运行时问题、UI 适配、功能增强。

---

## 一、构建工具链升级

将整个构建工具链从旧版升级到最新,适配 Android 15。

| 组件 | 升级前 | 升级后 |
|------|--------|--------|
| Gradle | 7.5 | **9.5.1** |
| AGP (Android Gradle Plugin) | 7.4.2 | **9.2.1** |
| Kotlin | 1.7.10(独立 plugin) | **2.3.21(AGP 9 内置,不再单独声明)** |
| Java | 1.8 | **21** |
| compileSdk / targetSdk | 33 | **35** |
| minSdk | 21 | 21(保持) |
| versionCode / versionName | 6 / 3.8.3 | 7 / 3.8.4 |

### 改动文件

**`gradle/wrapper/gradle-wrapper.properties`**
- `gradle-7.5-all.zip` → `gradle-9.5.1-all.zip`

**`settings.gradle`**
- 新增 `pluginManagement {}`(声明插件仓库)
- 新增 `dependencyResolutionManagement {}`(集中声明依赖仓库,AGP 9 要求)
- 仓库:google / mavenCentral / gradlePluginPortal / 阿里云镜像 / jitpack

**`build.gradle`(根目录)**
- `javaVersion`: `JavaVersion.VERSION_21` → `JavaVersion.toVersion('21')`(用方法调用兼容各 Gradle 版本,避免旧 Gradle 无 `VERSION_21` 枚举)
- 改用 `plugins {}` DSL 声明 AGP,移除 `buildscript` 里的 classpath
- 移除 `allprojects {}`(仓库已迁移到 settings.gradle)
- `task clean` → `tasks.register('clean', Delete)`

**`app/build.gradle`**
- 移除 `apply plugin: 'kotlin-android'`(AGP 9 内置 Kotlin,再加会报错)
- `compileSdkVersion` → `compileSdk`,`minSdkVersion` → `minSdk`,`targetSdkVersion` → `targetSdk`
- `kotlinOptions {}` → `kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_21 } }`(AGP 9 移除了 kotlinOptions)
- 新增 `buildFeatures { buildConfig = true; aidl = true }`(AGP 9 默认关闭,需显式启用)
- `packagingOptions` → `packaging`
- `proguard-android.txt` → `proguard-android-optimize.txt`(AGP 9 不再支持前者)
- 新增 `java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }`(显式指定 JDK 21,避免 AS 配置低版本 JDK 报错)
- 移除 `repositories { mavenCentral() }`(已集中到 settings.gradle)

**`gradle.properties`**
- 保留 `android.useAndroidX=true`
- 保留 `android.enableJetifier=true`(有库依赖旧 support 库,必须保留)
- 简化 jvmargs

### 踩坑记录

1. **AGP 9.2.1 要求最低 Gradle 9.4.1**(初选 9.3.1 失败,改 9.5.1)
2. **AGP 9.0 内置 Kotlin,禁止再加 `org.jetbrains.kotlin.android` plugin**(否则报错)
3. **`kotlinOptions` 在 AGP 9 被移除**,必须改用 `kotlin { compilerOptions {} }`
4. **`proguard-android.txt` 在 AGP 9 不再支持**,必须用 `proguard-android-optimize.txt`
5. **`buildFeatures.aidl` 需显式启用**(AGP 9 默认关闭,否则 AIDL 类不生成,编译报 Unresolved reference)
6. **Jetifier 必须保留**(android-job 等老库依赖 `com.android.support`,与 androidx 冲突)

### 依赖库升级

| 依赖 | 升级前 | 升级后 |
|------|--------|--------|
| androidx.appcompat | 1.4.0 | 1.7.0 |
| androidx.recyclerview | 1.2.1 | 1.3.2 |
| androidx.work:work-runtime-ktx | 2.7.1 | 2.10.0 |
| com.google.android.material | 1.4.0 | 1.12.0 |
| com.squareup.okhttp3:okhttp | 4.10.0 | 4.12.0 |
| dnsjava | 3.5.2 | 3.6.3 |

---

## 二、Android 15 (API 35) 行为变更适配

### 2.1 前台服务类型(Android 14+ 强制)

**`app/src/main/AndroidManifest.xml`**
- `ShadowsocksVpnService` 新增 `android:foregroundServiceType="specialUse"` + `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="vpn" />`
- 新增权限 `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />`

**`app/src/main/java/com/bige0/shadowsocksr/ShadowsocksNotification.kt`**
- `startForeground(1, builder.build())` → 按版本分支:API 34+ 用 `startForeground(1, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)`,低版本保持原调用
- 新增 `import android.content.pm.ServiceInfo`

### 2.2 通知权限 POST_NOTIFICATIONS(Android 13+)

**`app/src/main/AndroidManifest.xml`**
- 新增 `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />`

**`app/src/main/java/com/bige0/shadowsocksr/Shadowsocks.kt`**
- `prepareStartService()` 中在启动 VPN 前检查 `POST_NOTIFICATIONS` 权限(API 33+),未授予则 `ActivityCompat.requestPermissions` 请求
- 抽取 `startVpn()` 方法
- 新增 `onRequestPermissionsResult`:无论用户是否授予通知权限都继续启动 VPN(权限被拒仅影响通知显示,不阻断连接)
- 新增 imports:`android.Manifest`、`androidx.core.app.ActivityCompat`、`android.content.pm.PackageManager`

### 2.3 registerReceiver flag(Android 14+ 强制)

动态注册广播需指定 `RECEIVER_EXPORTED` / `RECEIVER_NOT_EXPORTED`,否则 Android 14+ 崩溃。

**`BaseService.kt`** / **`BaseVpnService.kt`** / **`ShadowsocksRunnerActivity.kt`** / **`ShadowsocksNotification.kt`**
- `registerReceiver(receiver, filter)` → `ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)`
- 各文件新增 `import androidx.core.content.ContextCompat`

### 2.4 stopForeground 新 API

**`app/src/main/java/com/bige0/shadowsocksr/ShadowsocksNotification.kt`**
- `service.stopForeground(true)` → `service.stopForeground(Service.STOP_FOREGROUND_REMOVE)`

---

## 三、compileSdk 35 空安全 + API 移除修复

compileSdk 35 下多处 API 标注为 nullable,Kotlin 2.3 更严格需显式处理。

**`app/src/main/java/com/bige0/shadowsocksr/AppManager.kt`**
- `p.requestedPermissions` / `p.applicationInfo` 变 nullable,加 `!!`(已在前面做 null 检查)

**`app/src/main/java/com/bige0/shadowsocksr/utils/Utils.kt`**
- `PackageInfo.signingInfo` / `PackageInfo.signatures` 变 nullable,加 `!!`

**`app/src/main/java/com/bige0/shadowsocksr/ShadowsocksSettings.kt`**
- `onSharedPreferenceChanged` 参数 `key: String` → `key: String?`

**`app/src/main/java/com/bige0/shadowsocksr/utils/Parser.kt`**
- `String.toLowerCase(Locale)` → `lowercase()`(Kotlin 2.3 升级为 error 级 deprecation)

### NFC Android Beam API 移除(Android 14 移除)

**`app/src/main/java/com/bige0/shadowsocksr/ProfileManagerActivity.kt`**
- `NfcAdapter.isNdefPushEnabled` / `setNdefPushMessageCallback` 在 compileSdk 35 被移除
- 新增 `isNdefPushEnabledReflect()` / `setNdefPushMessageCallbackReflect()` 用反射封装(API 34+ 自动跳过,旧设备保留功能)
- 4 个调用点改用反射方法

### selectableItemBackgroundBorderless attr

**`app/src/main/java/com/bige0/shadowsocksr/Shadowsocks.kt`**
- `R.attr.selectableItemBackgroundBorderless` → `android.R.attr.selectableItemBackgroundBorderless`(系统 attr,API 21+ 自带)

---

## 四、移除已废弃的 Google Analytics + Tag Manager

### 问题
`play-services-analytics:18.0.1`(Google Analytics)是已废弃库(Google 2023 年下线 Universal Analytics),其内部注册广播没加 Android 14+ 要求的 flag,在 Android 14+/15+/16 上抛 `SecurityException: One of RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED should be specified`,导致运行时崩溃。

### 修复(彻底移除 GA/GTM)

**`app/src/main/java/com/bige0/shadowsocksr/ShadowsocksApplication.kt`**
- 移除 imports:`com.google.android.gms.analytics.*`、`com.google.android.gms.common.api.*`、`com.google.android.gms.tagmanager.*`
- 移除 `containerHolder: ContainerHolder`、`tracker: Tracker` 字段
- `initVariable()`:移除 `GoogleAnalytics.getInstance` / `newTracker`
- `track(category, action)` / `track(t: Throwable)`:改为空操作(保留方法签名兼容所有调用方)
- `onCreate()`:移除 `TagManager.getInstance` / `loadContainerPreferNonDefault` / `registerFunctionCallMacroCallback` 整段
- `refreshContainerHolder()`:改为空操作

**`app/src/main/java/com/bige0/shadowsocksr/BaseService.kt`**
- `connect()`:移除依赖 GTM `containerHolder` 的自动代理分支(原功能依赖已下线的 GTM 服务)

**`app/src/main/java/com/bige0/shadowsocksr/BaseVpnService.kt`**
- `connect()`:同上移除 GTM 自动代理分支
- `blackList` getter:直接用默认值 `R.string.black_list`,移除从 GTM 获取 `black_list_lite` 的逻辑
- `onCreate()`:移除 `refreshContainerHolder()` 调用

**`app/build.gradle`**
- 移除 `implementation 'com.google.android.gms:play-services-analytics:18.0.1'`

**`app/src/main/AndroidManifest.xml`**
- 移除 `<meta-data android:name="com.google.android.gms.version" .../>`

---

## 五、状态栏重叠修复(Android 15 edge-to-edge)

### 问题
Android 15 (targetSdk 35) 强制启用 edge-to-edge,内容绘制到状态栏区域后面。原布局 Toolbar 没处理 window insets,被顶到屏幕最顶端,与系统状态栏重叠,时间等看不清。

### 修复

**`app/src/main/res/layout/toolbar_light_dark.xml`**
- `layout_height="?attr/actionBarSize"` → `wrap_content` + `minHeight="?attr/actionBarSize"`(加了 padding 后高度自适应)
- 新增 `android:fitsSystemWindows="true"`

**`app/src/main/java/com/bige0/shadowsocksr/Shadowsocks.kt`**
- `onCreate` 中 `setContentView` 后,用 `ViewCompat.setOnApplyWindowInsetsListener` 监听 Toolbar 的 window insets,动态把状态栏高度设为顶部 padding
- 同步设置 `minimumHeight = statusBarHeight + actionBarHeight`,使 Toolbar 整体加高,内容垂直居中不贴下边缘
- 调用 `ViewCompat.requestApplyInsets` 触发分发
- 新增 imports:`androidx.core.view.ViewCompat`、`androidx.core.view.WindowInsetsCompat`

```kotlin
val actionBarHeight = toolbar.resources.getDimensionPixelSize(
    androidx.appcompat.R.dimen.abc_action_bar_default_height_material)
ViewCompat.setOnApplyWindowInsetsListener(toolbar) { v, insets ->
    val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
    v.setPadding(v.paddingLeft, statusBarHeight, v.paddingRight, v.paddingBottom)
    v.minimumHeight = statusBarHeight + actionBarHeight
    insets
}
ViewCompat.requestApplyInsets(toolbar)
```

---

## 六、首页添加配置快捷菜单

### 需求
原添加配置功能只在"配置文件页面",需跳转。改为在首页直接添加。

### 实现
在首页添加一个"添加"浮动菜单(clans FloatingActionMenu),点击各子项跳转到配置页并带上对应 action,由配置页执行具体添加逻辑(复用已有实现,不重复代码)。

**`app/src/main/java/com/bige0/shadowsocksr/utils/Constants.kt`**
- 新增 action 常量:`MANUAL_ADD`、`SUB_ADD`、`IMPORT_ADD`、`NFC_ADD`

**`app/src/main/AndroidManifest.xml`**
- ProfileManagerActivity 新增 4 个 intent-filter(MANUAL_ADD / SUB_ADD / IMPORT_ADD / NFC_ADD)

**`app/src/main/java/com/bige0/shadowsocksr/ProfileManagerActivity.kt`**
- `onCreate` 的 `when(intent.action)` 扩展:处理 MANUAL_ADD(创建并切换 profile)、SUB_ADD(打开订阅对话框)、IMPORT_ADD(剪贴板导入)、NFC_ADD(NFC 添加)
- `updateNfcState()` 提前到 `when` 之前调用,确保 NFC 状态已初始化

**`app/src/main/res/layout/layout_main.xml`**
- 新增 `FloatingActionMenu`(id: home_add_menu),`layout_alignParentBottom` + `elevation="8dp"`
- 4 个子按钮:手动设置 / 扫描二维码 / SSR 订阅 / 剪贴板导入

**`app/src/main/java/com/bige0/shadowsocksr/Shadowsocks.kt`**
- 新增 `initAddMenu()`:初始化菜单按钮图标和点击事件,各子项 `startActivity(Intent(...).setAction(Constants.Action.XXX))`
- 用全限定名 `com.github.clans.fab.FloatingActionMenu` / `FloatingActionButton`(避免与 material 的 FloatingActionButton 同名冲突)
- `onCreate` 中调用 `initAddMenu()`

### 踩坑记录
- clans `FloatingActionMenu` 用 `layout_above` 定位会跑到屏幕顶部(被 Toolbar 遮挡),改用 `layout_alignParentBottom` 解决
- `FloatingActionButton` 类名冲突:`com.google.android.material.floatingactionbutton.*` 和 `com.github.clans.fab.FloatingActionButton` 同名,Kotlin 解析优先匹配 material 的,导致 ClassCastException。解决:移除 clans import,代码用全限定名
- `menu_labelsTextColor` 属性在 clans 库中不存在,移除

---

## 七、替换扫码库(更现代的 UI)

### 需求
原扫码用 `me.dm7.barcodescanner:zxing:1.9.13`(已停维、UI 丑),替换为更好的开源库。

### 替换为 journeyapps:zxing-android-embedded:4.3.0
- 5.9k stars,Apache 2.0,无需 Google Play Services(适合 SSR 工具)
- 内置现代扫码 UI(取景框/提示文字/闪光灯),自动处理相机权限
- 用 `ScanContract` 声明式集成

**`app/build.gradle`**
- `me.dm7.barcodescanner:zxing:1.9.13` → `com.journeyapps:zxing-android-embedded:4.3.0`

**`app/src/main/java/com/bige0/shadowsocksr/ScannerActivity.kt`**(完全重写)
- 移除 `ZXingScannerView.ResultHandler`、手动相机管理、手动权限请求、setContentView
- 改用 `registerForActivityResult(ScanContract())` 启动库内置扫码界面
- `onCreate` 直接 `barcodeLauncher.launch(options)`(QR_CODE 格式、提示语、蜂鸣、自由旋转)
- 结果回调解析 ss/ssr 链接并创建 profile(逻辑不变)

### 扫码界面竖屏修复
journeyapps `CaptureActivity` 默认横屏。

**`app/src/main/AndroidManifest.xml`**
- 新增 `CaptureActivity` 声明,`screenOrientation="portrait"` + `tools:replace="screenOrientation"` 覆盖库默认

---

## 八、JDK 21 编译问题修复

### 问题
Android Studio 报 `错误: 无效的源发行版：21` —— AS 的 Gradle JDK 配置低于 21,不认识 `--release 21`。

### 修复
**`app/build.gradle`**
- 新增 `java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }`,显式指定用 JDK 21 编译,不依赖 AS 配置的 Gradle JDK

### 还需用户在 Android Studio 设置
File → Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK 选择 jbr-21(AS 自带)或系统 JDK 21。

---

## 改动文件汇总(共 21 个)

### 构建配置(6 个)
1. `gradle/wrapper/gradle-wrapper.properties`
2. `settings.gradle`
3. `build.gradle`
4. `app/build.gradle`
5. `gradle.properties`
6. `app/src/main/AndroidManifest.xml`

### Kotlin 源码(13 个)
7. `app/src/main/java/com/bige0/shadowsocksr/Shadowsocks.kt`
8. `app/src/main/java/com/bige0/shadowsocksr/ShadowsocksApplication.kt`
9. `app/src/main/java/com/bige0/shadowsocksr/ShadowsocksNotification.kt`
10. `app/src/main/java/com/bige0/shadowsocksr/BaseService.kt`
11. `app/src/main/java/com/bige0/shadowsocksr/BaseVpnService.kt`
12. `app/src/main/java/com/bige0/shadowsocksr/ShadowsocksRunnerActivity.kt`
13. `app/src/main/java/com/bige0/shadowsocksr/ShadowsocksSettings.kt`
14. `app/src/main/java/com/bige0/shadowsocksr/ProfileManagerActivity.kt`
15. `app/src/main/java/com/bige0/shadowsocksr/ScannerActivity.kt`
16. `app/src/main/java/com/bige0/shadowsocksr/AppManager.kt`
17. `app/src/main/java/com/bige0/shadowsocksr/utils/Constants.kt`
18. `app/src/main/java/com/bige0/shadowsocksr/utils/Utils.kt`
19. `app/src/main/java/com/bige0/shadowsocksr/utils/Parser.kt`

### 布局资源(2 个)
20. `app/src/main/res/layout/layout_main.xml`
21. `app/src/main/res/layout/toolbar_light_dark.xml`

---

## 验证结果

- ✅ `./gradlew clean :app:assembleDebug` 编译通过(Gradle 9.5.1 + AGP 9.2.1 + Kotlin 2.3.21 + Java 21 + compileSdk 35)
- ✅ APK 安装到 Android 16 (API 36) 真机,应用正常启动运行
- ✅ 运行时崩溃全部解决(GA SecurityException、ClassCastException 等)
- ✅ 状态栏不再重叠,Toolbar 内容垂直居中
- ✅ 首页添加菜单功能正常
- ✅ 扫码界面现代 UI + 竖屏

### 遗留非阻塞警告
- `llvm-strip` 对预编译 `libproxychains4.so` 的 section header 警告(native 库历史遗留,不影响运行)
- 若干 deprecated warnings(`Handler()`、`ProgressDialog`、`adapterPosition` 等,可在后续优化中替换)

