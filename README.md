# 🍌Pro Enable

一个基于 **Xposed API 102** 的 Gemini 安卓模块，将 AI 回复菜单中的 **“使用 Pro 重做”** 入口显示在所有回复上。

正常情况下，这个入口只会出现在使用非 Pro 的 Nano Banana 模型生成图片的回复中。模块解除这层前端显示限制，让普通文字回复、未成功出图的回复，以及返回拒绝提示的回复，也能调用客户端原有的 Pro 重做动作。

根据实测，某些原本被拦截的图片请求，经这个入口重做后能够出图；生成结果也没有右下角的可见水印。这些是特定环境下观察到的效果，是否重复执行提示词审核、为何水印表现不同，目前没有确认其服务端机制。

模块实际修改的只有菜单入口的可见性，点击后仍由 Google 原有逻辑处理。它没有修改审核逻辑、模型选择、账号权限或网络请求，也没有处理图片或移除水印。因此，不能把它理解为保证绕过审核、保证使用 Pro 模型或保证无水印的工具；最终能否出图、使用哪个模型以及水印表现，以实际返回结果为准。

## 使用方法

需要 Android 10 或以上、支持 **libxposed API 102** 的 Xposed 框架，以及能够正常使用的 Google 和 Gemini 应用。

1. 安装模块 APK，在 Xposed 框架管理器中启用 **🍌Pro Enable**。
2. 将作用域设为 **Google**（`com.google.android.googlequicksearchbox`）。
3. 重启 Google 和 Gemini 应用，使模块生效。
4. 打开任意 AI 回复下方的“更多”菜单，点击 **“使用 Pro 重做”**。

作用域选 Google，是因为已分析的 Gemini 安卓客户端（`com.google.android.apps.bard`）通过 Google 应用承载聊天界面。模块没有独立的启动页面或设置页面。

## 定位方式与兼容性

模块使用 DEX 语义定位，不写死混淆类名、字段名、构造参数位置、数值资源 ID 或 Google 版本白名单：

- 聊天页：从 `ActionRowState` 的 `showImageRegeneration` 描述追踪对应布尔字段，再解析控制它的构造参数。
- 浮窗：通过 Pro 重做的标题资源名追踪菜单项，仅改变匹配该标题的项目。
- 缓存：按已安装 APK 和 DEX 的指纹保存定位结果，应用更新后重新解析，并在安装 hook 前校验运行时类型。

在语义特征保持一致的前提下，这种方式可以适应混淆改名和参数重排。若 Google 改变状态结构或语义特征，仍需要更新定位规则，不能提前保证任意未来版本都兼容。定位不明确时不会猜测目标；可选浮窗定位失败不会阻止聊天页 hook。

## 本地构建

使用 JDK 25 和 Android SDK 37：

```sh
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

如需运行提供的 Google APK 离线回归测试：

```sh
GEMINI_HOST_APK="$PWD/gemini_workspace/Google_17.60.15.ve.arm64_apks.apk" \
  ./gradlew :app:testDebugUnitTest
```

签名构建通过环境变量配置 `GEMINI_HOOK_KEYSTORE`、`GEMINI_HOOK_STORE_PASSWORD`、`GEMINI_HOOK_KEY_ALIAS` 和 `GEMINI_HOOK_KEY_PASSWORD`。未提供签名配置时，release APK 不签名；