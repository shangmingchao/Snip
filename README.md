# Snip

[![Snip](./screenshot/snap.gif)

**Snip** 是一个轻量级、流畅的 Android 图片裁剪库，采用 `Matrix` 实现，提供丰富的交互体验。支持固定/自由比例裁剪、手势拖拽缩放、旋转、边界回弹等特性，帮助您快速集成图片裁剪功能。

---

## ✨ 特性

- 🖼️ **多种裁剪比例**：支持 1:1、16:9、9:16、4:3、3:4、自由比例（Free）以及任意自定义比例
- 👆 **单指拖拽**：拖动图片进行位置调整
- ✌️ **双指缩放**：缩放图片，流畅平滑
- 🔁 **双击缩放**：支持多级缩放，最多 5 次
- 📦 **固定裁剪框**：默认占据视图 80%，居中显示，可自定义大小
- 🖌️ **调整裁剪区域**：拖拽裁剪框的角或边缘，松手后图片自动缩放适配裁剪框
- 📐 **固定比例模式**：调整裁剪框时保持指定比例
- 🎞️ **Matrix 动画**：所有变换均基于 Matrix，动画流畅无卡顿
- 🧩 **边界回弹**：图片超出边界时自动回弹
- 📏 **三分线网格**：触摸时显示辅助网格，便于构图
- 🔄 **图片旋转**：支持逆时针/顺时针 90° 旋转，带平滑动画

---

## 📦 开始使用

### 1. 添加依赖

在模块的 `build.gradle` 中添加依赖：

```groovy
implementation 'io.github.shangmingchao:snip:1.0.0'
```

若使用 Gradle version catalog（`libs.versions.toml`）：

```toml
[versions]
snip = "1.0.0"

[libraries]
snip = { group = "io.github.shangmingchao", name = "snip", version.ref = "snip" }
```

```kts
implementation(libs.snip)
```

### 2. 在布局中使用

```xml
<cn.frank.snip.CropImageView
    android:id="@+id/cropImageView"
    android:layout_width="0dp"
    android:layout_height="0dp"
    app:layout_constraintBottom_toBottomOf="parent"
    app:layout_constraintEnd_toEndOf="parent"
    app:layout_constraintStart_toStartOf="parent"
    app:layout_constraintTop_toTopOf="parent" />
```

### 3. 加载图片与设置裁剪比例

```kotlin
// 设置图片（Bitmap）
cropImageView.setImageBitmap(bitmap)

// 设置裁剪比例（可选）
cropImageView.setCropRatio(1f)

// 旋转图片（顺时针）
cropImageView.rotateClockwise()

// 旋转图片（逆时针）
cropImageView.rotate()
```

### 4. 获取裁剪结果

```kotlin
val croppedBitmap = cropImageView.getCroppedImage()
```
