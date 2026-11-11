# 让大肥鱼写的水印相机 Watermark Camera

一个开源、纯净的安卓水印相机。拍照时自动把**时间、日期、地点、备注**烧录进照片，生成的照片直接进系统相册，不带任何广告、不需要登录、不上传云端。

最近被经常用的水印相机恶心到了，所以打算自己弄一个出来。逐步添加功能中。

## 功能

- 📷 CameraX 实时预览，前后摄像头一键切换，闪光灯三档：关 / 闪光 / 常亮
- 🖼️ 画幅切换：4:3 / 全屏，预览与成片比例一致
- 📐 取景参考线：九宫格、十字线循环切换
- 🧩 边拍边拼：照片先暂存不落相册，拍够后按 1~4 列一键拼成长图，支持撤销与清空（取景窗左侧有快捷键）
- 🕒 实时时间/日期水印，所见即所得
- 📍 自动定位并把坐标反解成可读地名，粒度可选：省 / 市 / 区 / 街道 / 地点，无地名时回退显示经纬度
- 🌤️ 天气水印：按坐标取实时气温与天气，PM2.5 偏高时显示「轻度雾霾」等
- ✍️ 备注水印：内置常用快捷项，也能自己输入并保存成快捷项（长按删除）
- 👆 点一下水印区即可打开水印设置，点面板外或右上角 × 关闭
- 💾 水印**烧录进像素**后保存到 `相册/WatermarkCamera`，任何 App 打开都能看到
- 🌏 照片全本地处理不上传；仅查天气时联网（open-meteo 免费接口，无需 key）

## 截图

> 拍照界面：左侧黄色竖条 + 大号时间 + 日期 + 地点

## 版本号规则

- 安装包名：`WatermarkCamera-<版本号>.apk`，例如 `WatermarkCamera-0.0.4-RC3.apk`
- 版本号从首版 `0.0.1` 起，默认**只递增最后一位**（`0.0.1 → 0.0.2 → 0.0.3 …`）
- 主版本 / 次版本号（第一位、第二位）需要人工确认才能动
- 测试版按顺序加 RC 后缀：`RC1`、`RC2`、`RC3`……

## 下载 APK

到 [Releases](https://github.com/Teresian/WatermarkCamera/releases/latest) 页面下载最新版的 `WatermarkCamera-<版本号>.apk`，传到手机上安装即可（Android 7.0 / API 24 及以上）。

> 该 APK 使用 debug 签名，适合自用安装。若要上架应用商店，请用下面的环境变量配置自己的 keystore 重新打包。

## 自己构建

```bash
# 需要 JDK 17 + Android SDK（platform 34 / build-tools 34.0.0）
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/WatermarkCamera-<版本号>.apk
```

Windows 下可以用 `gradlew.bat`。首次构建需要联网拉依赖。

### 发布签名（可选）

设置以下环境变量后，`assembleRelease` 会用你自己的 keystore 签名：

| 变量 | 说明 |
| --- | --- |
| `WM_SIGN_STORE` | keystore 文件路径 |
| `WM_SIGN_STORE_PASSWORD` | keystore 密码 |
| `WM_SIGN_KEY_ALIAS` | 密钥别名 |
| `WM_SIGN_KEY_PASSWORD` | 密钥密码 |

不设置时 release 会回退到 debug 签名，方便本地出包测试。

## 项目结构

```
app/src/main/java/com/xiaojing/watermarkcamera/
├── MainActivity.kt            # 入口，Material3 深色主题
├── ui/
│   ├── CameraScreen.kt        # 相机界面、参考线、水印预览叠层、设置与拼图面板
│   └── CameraViewModel.kt     # 拍摄后处理流水线（解码 → 旋转校正 → 烧水印 → 存相册/暂存拼图）
├── watermark/
│   ├── WatermarkConfig.kt     # 水印开关与内容
│   └── WatermarkRenderer.kt   # Canvas 烧录水印（渐变遮罩 + 强调竖线 + 自动省略）
└── util/
    ├── LocationHelper.kt      # 定位 + Geocoder 反解地名（支持省/市/区/街道/地点粒度）
    ├── WeatherHelper.kt       # open-meteo 查气温与 PM2.5，映射成中文天气
    ├── ImageSaver.kt          # MediaStore / 文件两种保存路径
    └── PhotoStitcher.kt       # 多图拼长图（1~4 列，最短列优先分配）
```

## 权限说明

| 权限 | 用途 |
| --- | --- |
| `CAMERA` | 拍照 |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | 取地名写进水印；拒绝也不影响拍照 |
| `INTERNET` / `ACCESS_NETWORK_STATE` | 仅用于查询天气，不上传照片 |
| `WRITE_EXTERNAL_STORAGE`（仅 Android 9 及以下） | 老系统写相册 |

## License

MIT
