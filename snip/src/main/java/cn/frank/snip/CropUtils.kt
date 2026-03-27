package cn.frank.snip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlin.math.ceil
import kotlin.math.max

/**
 * 裁剪工具
 *
 * @author shangmingchao
 */

/**
 * 从 Uri 加载 Bitmap，如果图片尺寸超过 GL 允许的最大纹理尺寸，则自动采样至该限制以内。
 *
 * @param uri 图片的 Uri（支持 content:// 或 file://）
 * @param context 上下文，用于获取 ContentResolver
 * @return 加载后的 Bitmap 或者 null
 */
fun loadBitmap(uri: Uri, context: Context): Bitmap? {
    val maxTextureSize = 4096

    // 第一步：仅解码图片边界，获取原始宽高
    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    context.contentResolver.openInputStream(uri)?.use { inputStream ->
        BitmapFactory.decodeStream(inputStream, null, options)
    }

    val originalWidth = options.outWidth
    val originalHeight = options.outHeight

    // 第二步：计算采样率
    var sampleSize = 1
    if (originalWidth > maxTextureSize || originalHeight > maxTextureSize) {
        val widthScale = originalWidth.toDouble() / maxTextureSize
        val heightScale = originalHeight.toDouble() / maxTextureSize
        val scale = max(widthScale, heightScale)
        sampleSize = ceil(scale).toInt()
        // 推荐使用 2 的幂作为采样率，性能更优
        sampleSize = sampleSize.takeHighestOneBit()
    }

    // 第三步：正式解码，应用采样率
    options.inJustDecodeBounds = false
    options.inSampleSize = sampleSize
    // 使用 RGB_565 可以节省内存，若需透明度可改为 ARGB_8888
    options.inPreferredConfig = Bitmap.Config.RGB_565

    return context.contentResolver.openInputStream(uri)?.use { inputStream ->
        BitmapFactory.decodeStream(inputStream, null, options)
    }
}
