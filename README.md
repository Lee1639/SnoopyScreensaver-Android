# 在安卓上运行的史努比屏保

项目移植自apple tv上的史努比屏保，用grok build将其做成了一个安卓APP，直接在安卓手机上运行app即可显示屏保画面。

APP运行时屏幕会保持常亮，返回键退出。

源码在这个仓库里。安装包在 [v1.0](https://github.com/Lee1639/SnoopyScreensaver/releases/tag/v1.0)。`SnoopyScreensaver.apk` 是正式版，桌面显示史努比屏保，包名 `com.snoopysaver.android`，版本 1.0。`SnoopyScreensaver-weather.apk` 是天气版，桌面显示史努比屏保·天气，包名 `com.snoopysaver.weather`，版本 1.1-weather。天气版额外增加了天气模块，理论上可以根据用户设置的地区读取天气状态，优先展示带有对应天气的屏保片段。两个可以一起装，播放进度各记各的。包是调试签名，手机要允许未知来源。

## 素材放在手机里

画面大约 7.2GB，由于素材包比较大，安装包里没有自带，放到了夸克网盘: https://pan.quark.cn/s/76e94aad2ae1?pwd=myYJ ，提取码 `myYJ`。

解压出来的文件夹叫 `SnoopyAssets`，放到内部存储的根目录。应用还会到下载、电影、文档下面找这个名字。第一次如果读不到，会让你自己选文件夹，并要求打开允许访问所有文件选项。

## 天气版

正式版只负责播。天气版在同样的播放上面，按当前城市的天气挑画面。城市交给 [Open-Meteo](https://open-meteo.com/) 查，不用申请密钥，查到的结果留 1 小时。接口没有返回时，就接着播普通内容。

设置方法为进入app后长按，会弹出天气设置界面，可以自行设置城市以及是否启用。


## 自己编译

要用 Android Studio。编译 SDK 是 36，最低能装到 Android 9，也就是 API 28。`SnoopyScreensaver` 和 `SnoopyScreensaver-weather` 各是一个工程，用 Android Studio 打开其中一个，等同步完，点 Run。

工程里带了 Gradle Wrapper，版本 9.1.0。也可以在工程目录里执行

```bat
gradlew.bat :app:assembleDebug
```

安装包会出现在 `app\build\outputs\apk\debug\app-debug.apk`。两个版本要各编一次，各装一次。

`local.properties` 只有本机的 SDK 路径，没有提交。Android Studio 打开工程时会按这台电脑重新写一份。

## 代码放在哪

`SnoopyScreensaver` 是正式版，`SnoopyScreensaver-weather` 是天气版。播放节奏写在 `Director.kt`，画面层和第一次找素材写在 `SnoopySession.kt`。`StageView.kt` 把房间、角色和遮罩画出来。`Heic.kt` 认文件、做解码。素材目录和播放规则在 `Engine.kt`。天气版另有 `Weather.kt`。

`app/src/main/assets/asset-index.json` 是一份清单，记录每个画面有哪些文件。


## 版权与免责声明
Snoopy、Peanuts、Apple TV 及相关名称、角色、商标和媒体资源归各自权利人所有。

本项目是非官方的安卓播放实现，与 Apple、Peanuts Worldwide 或其他相关权利人不存在授权、赞助、认可或合作关系。

第三方媒体素材不属于本项目作者。下载、使用和保存这些素材由使用者自行负责，并应遵守适用的法律法规及相关权利人的要求。

素材包由 [dingdangnao/Snoopy-Screensaver](https://github.com/dingdangnao/Snoopy-Screensaver) 整理。
