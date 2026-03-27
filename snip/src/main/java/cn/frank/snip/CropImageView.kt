package cn.frank.snip

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Region
import android.os.Build
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * 图片裁剪 View
 *
 * 功能特性：
 * - 多种裁剪比例：1:1、16:9、9:16、4:3、3:4、自由比例
 * - 单指拖动图片
 * - 双指缩放图片
 * - 双击放大（最多5次）
 * - 固定裁剪框大小（占视图区域80%，居中）
 * - 拖动裁剪框边缘调整裁剪区域，松手后还原
 * - 固定比例模式下保持裁剪框比例
 * - Matrix 实现，流畅动画
 * - 边界回弹动画
 * - 三分线网格（触摸时显示）
 * - 图片旋转功能（逆时针/顺时针 90 度，带动画）
 *
 * @author shangmingchao
 */
class CropImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ==================== 边缘/角类型 ====================
    private enum class EdgeType {
        NONE,
        TOP, BOTTOM, LEFT, RIGHT,
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
    }

    // ==================== 常量配置 ====================
    companion object {
        private const val CROP_BOX_SIZE_RATIO = 0.8f
        private const val MAX_SCALE_LEVELS = 5
        private const val SCALE_FACTOR = 1.5f
        private const val MAX_SCALE = 10f
        private const val EDGE_TOUCH_THRESHOLD = 40f
        private const val GUIDE_LINE_STROKE_WIDTH = 1.5f
        private const val CROP_BOX_STROKE_WIDTH = 3f
        private const val CORNER_LINE_LENGTH = 30f
        private const val CORNER_LINE_STROKE_WIDTH = 6f
        private const val MIN_CROP_SIZE = 100f
        private const val MIN_POINTER_SPAN = 20f
    }

    // ==================== 核心状态 ====================
    private var sourceBitmap: Bitmap? = null
    private val imageMatrix = Matrix()
    private val savedMatrix = Matrix()
    private val matrixValues = FloatArray(9)

    // 图片边界
    private val imageRect = RectF()

    // 裁剪框
    private val cropRect = RectF()
    private val tempCropRect = RectF()
    private val originalCropRect = RectF()

    // 当前裁剪比例
    var currentRatio: Float = 1f
        private set

    // ==================== 手势相关 ====================
    private val gestureDetector: GestureDetector
    private var activePointerId = -1
    private val lastTouch = PointF()

    // 当前缩放级别
    private var currentScaleLevel = 0

    // 缩放时锁定的焦点（避免缩放过程中焦点变化导致跳动）
    private val lockedFocus = PointF()

    // 自定义缩放手势检测
    private var isScaling = false
    private var lastSpan = 0f

    // ==================== 边缘拖动相关 ====================
    private var activeEdge = EdgeType.NONE
    private var isDraggingEdge = false
    private var isShowingGuideLines = false
    private val edgeTouchPoint = PointF()

    // ==================== 旋转相关 ====================
    private var currentRotation = 0 // 当前旋转角度 (0, 90, 180, 270)

    // ==================== 动画相关 ====================
    private var bounceAnimator: ValueAnimator? = null
    private var restoreAnimator: ValueAnimator? = null
    private var rotateAnimator: ValueAnimator? = null
    private val decelerateInterpolator = DecelerateInterpolator()
    private val overshootInterpolator = OvershootInterpolator(1.5f)

    // ==================== 绘制相关 ====================
    private val guideLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = GUIDE_LINE_STROKE_WIDTH
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    private val cropBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = CROP_BOX_STROKE_WIDTH
        style = Paint.Style.STROKE
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = CORNER_LINE_STROKE_WIDTH
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC000000.toInt()
        style = Paint.Style.FILL
    }

    private val clipPath = Path()

    init {
        gestureDetector = GestureDetector(context, GestureListener())
    }

    /**
     * 设置图片
     */
    fun setImageBitmap(bitmap: Bitmap?) {
        this.sourceBitmap = bitmap
        bitmap?.let {
            resetMatrix()
            calculateCropRect()
            fitImageToCrop()
        }
        invalidate()
    }

    /**
     * 设置裁剪比例
     */
    fun setCropRatio(ratio: Float) {
        if (this.currentRatio != ratio) {
            this.currentRatio = ratio
            calculateCropRect()
            fitImageToCrop()
            invalidate()
        }
    }

    // ==================== 旋转功能 ====================

    /**
     * 逆时针旋转图片 90 度，带动画
     */
    fun rotate() {
        if (sourceBitmap == null) return
        animateRotate(-90f)
    }

    /**
     * 顺时针旋转图片 90 度，带动画
     */
    fun rotateClockwise() {
        if (sourceBitmap == null) return
        animateRotate(90f)
    }

    /**
     * 执行旋转动画
     * 以裁剪框中心为锚点旋转，保持当前缩放比例
     */
    private fun animateRotate(degrees: Float) {
        cancelAnimations()

        // 更新旋转角度
        currentRotation = (currentRotation + degrees.toInt() + 360) % 360

        // 以裁剪框中心为旋转锚点
        val centerX = cropRect.centerX()
        val centerY = cropRect.centerY()

        // 保存当前矩阵状态用于动画
        savedMatrix.set(imageMatrix)

        rotateAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = decelerateInterpolator

            addUpdateListener { animation ->
                val fraction = animation.animatedFraction
                val currentDegrees = degrees * fraction

                imageMatrix.set(savedMatrix)
                imageMatrix.postRotate(currentDegrees, centerX, centerY)
                postInvalidateOnAnimation()
            }

            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // 确保裁剪框不超出图片边界
                    ensureCropWithinBounds()
                    invalidate()
                }
            })
        }.also { it.start() }
    }

    /**
     * 确保裁剪框在图片边界内
     */
    private fun ensureCropWithinBounds() {
        val bitmap = sourceBitmap ?: return
        calculateImageRect()

        // 获取旋转后的图片尺寸
        val (rotatedWidth, rotatedHeight) = getRotatedBitmapSize(bitmap)

        // 检查缩放是否足够
        val currentScale = getCurrentScale()
        val minScale = maxOf(
            cropRect.width() / rotatedWidth,
            cropRect.height() / rotatedHeight
        )

        if (currentScale < minScale) {
            // 需要放大
            val scale = minScale / currentScale
            imageMatrix.postScale(scale, scale, cropRect.centerX(), cropRect.centerY())
            calculateImageRect()
        }

        // 修正边界
        fixTranslateBounds()
        calculateImageRect()
    }

    // ==================== 核心计算方法 ====================

    private fun resetMatrix() {
        imageMatrix.reset()
        currentScaleLevel = 0
        currentRotation = 0
    }

    private fun calculateCropRect() {
        val viewWidth = width
        val viewHeight = height
        if (viewWidth <= 0 || viewHeight <= 0) return

        val maxCropWidth = viewWidth * CROP_BOX_SIZE_RATIO
        val maxCropHeight = viewHeight * CROP_BOX_SIZE_RATIO

        val (cropWidth, cropHeight) = if (currentRatio > 0) {
            // 固定比例模式
            if (currentRatio >= 1f) {
                var ch = maxCropWidth / currentRatio
                if (ch > maxCropHeight) {
                    ch = maxCropHeight
                    maxCropWidth * ch / (maxCropWidth / currentRatio) to ch
                } else {
                    maxCropWidth to ch
                }
            } else {
                var cw = maxCropHeight * currentRatio
                if (cw > maxCropWidth) {
                    cw = maxCropWidth
                    cw to cw / currentRatio
                } else {
                    cw to maxCropHeight
                }
            }
        } else {
            // 自由比例模式：裁剪框大小为图片大小（旋转后）
            sourceBitmap?.let { bitmap ->
                val (bmpWidth, bmpHeight) = getRotatedBitmapSize(bitmap)
                // 限制在视图范围内
                val scale = minOf(maxCropWidth / bmpWidth, maxCropHeight / bmpHeight, 1f)
                bmpWidth * scale to bmpHeight * scale
            } ?: run {
                // 没有图片时使用正方形
                val size = maxCropWidth.coerceAtMost(maxCropHeight)
                size to size
            }
        }

        val left = (viewWidth - cropWidth) / 2f
        val top = (viewHeight - cropHeight) / 2f

        cropRect.set(left, top, left + cropWidth, top + cropHeight)
        originalCropRect.set(cropRect)
    }

    private fun calculateImageRect() {
        sourceBitmap?.let { bitmap ->
            // 始终使用原始图片尺寸，让矩阵处理所有变换
            imageRect.set(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
            imageMatrix.mapRect(imageRect)
        }
    }

    /**
     * 获取旋转后的图片尺寸（仅用于计算缩放比例）
     */
    private fun getRotatedBitmapSize(bitmap: Bitmap): Pair<Float, Float> {
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        return when (currentRotation) {
            90, 270 -> height to width  // 旋转90度或270度时，宽高互换
            else -> width to height
        }
    }

    private fun fitImageToCrop() {
        val bitmap = sourceBitmap ?: return
        if (width <= 0 || height <= 0) return

        // 根据旋转角度确定图片尺寸
        val (bmpWidth, bmpHeight) = getRotatedBitmapSize(bitmap)

        val cropWidth = cropRect.width()
        val cropHeight = cropRect.height()

        val scaleX = cropWidth / bmpWidth
        val scaleY = cropHeight / bmpHeight
        val scale = maxOf(scaleX, scaleY)

        imageMatrix.reset()

        // 1. 先缩放
        imageMatrix.postScale(scale, scale)

        // 2. 如果有旋转，应用旋转（以缩放后的图片中心为旋转中心）
        if (currentRotation != 0) {
            val scaledWidth = bitmap.width * scale
            val scaledHeight = bitmap.height * scale
            imageMatrix.postRotate(currentRotation.toFloat(), scaledWidth / 2, scaledHeight / 2)
        }

        // 3. 计算需要的平移
        calculateImageRect()
        val dx = cropRect.centerX() - imageRect.centerX()
        val dy = cropRect.centerY() - imageRect.centerY()
        imageMatrix.postTranslate(dx, dy)

        currentScaleLevel = 0
        calculateImageRect()
    }

    private fun getCurrentScale(): Float {
        imageMatrix.getValues(matrixValues)
        // 当矩阵包含旋转时，需要计算实际的缩放比例
        // scale = sqrt(MSCALE_X^2 + MSKEW_Y^2)
        val scaleX = matrixValues[Matrix.MSCALE_X]
        val skewY = matrixValues[Matrix.MSKEW_Y]
        return kotlin.math.sqrt(scaleX * scaleX + skewY * skewY)
    }

    private fun getCurrentTranslate(): PointF {
        imageMatrix.getValues(matrixValues)
        return PointF(matrixValues[Matrix.MTRANS_X], matrixValues[Matrix.MTRANS_Y])
    }

    // ==================== 手势监听器 ====================

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (sourceBitmap == null || isDraggingEdge) return false
            doubleTapZoom(e.x, e.y)
            return true
        }

        override fun onDown(e: MotionEvent): Boolean = true
    }

    private fun doubleTapZoom(focusX: Float, focusY: Float) {
        currentScaleLevel++
        if (currentScaleLevel > MAX_SCALE_LEVELS) {
            currentScaleLevel = 0
            animateFitToCrop()
            return
        }

        val currentScale = getCurrentScale()
        var targetScale = currentScale * SCALE_FACTOR
        targetScale = targetScale.coerceAtMost(MAX_SCALE)

        animateZoom(focusX, focusY, targetScale)
    }

    private fun animateZoom(focusX: Float, focusY: Float, targetScale: Float) {
        val startScale = getCurrentScale()
        savedMatrix.set(imageMatrix)

        cancelAnimations()

        bounceAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = overshootInterpolator

            addUpdateListener { animation ->
                val fraction = animation.animatedFraction
                val currentScaleValue = startScale + (targetScale - startScale) * fraction

                imageMatrix.set(savedMatrix)
                val scaleStep = currentScaleValue / startScale
                imageMatrix.postScale(scaleStep, scaleStep, focusX, focusY)

                calculateImageRect()
                checkAndFixBounds()
                postInvalidateOnAnimation()
            }

            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    calculateImageRect()
                    checkAndFixBounds()
                }
            })
        }.also { it.start() }
    }

    private fun animateFitToCrop() {
        val bitmap = sourceBitmap ?: return
        savedMatrix.set(imageMatrix)

        val targetMatrix = Matrix()
        // 使用旋转后的图片尺寸
        val (bmpWidth, bmpHeight) = getRotatedBitmapSize(bitmap)
        val cropWidth = cropRect.width()
        val cropHeight = cropRect.height()

        val scaleX = cropWidth / bmpWidth
        val scaleY = cropHeight / bmpHeight
        val scale = maxOf(scaleX, scaleY)

        // 1. 先缩放
        targetMatrix.postScale(scale, scale)

        // 2. 如果有旋转，应用旋转（以缩放后的图片中心为旋转中心）
        if (currentRotation != 0) {
            val scaledWidth = bitmap.width * scale
            val scaledHeight = bitmap.height * scale
            targetMatrix.postRotate(currentRotation.toFloat(), scaledWidth / 2, scaledHeight / 2)
        }

        // 3. 计算平移
        val tempRect = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        targetMatrix.mapRect(tempRect)

        val dx = cropRect.centerX() - tempRect.centerX()
        val dy = cropRect.centerY() - tempRect.centerY()
        targetMatrix.postTranslate(dx, dy)

        cancelAnimations()

        val startValues = FloatArray(9)
        val endValues = FloatArray(9)
        savedMatrix.getValues(startValues)
        targetMatrix.getValues(endValues)

        bounceAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 400
            interpolator = decelerateInterpolator

            addUpdateListener { animation ->
                val fraction = animation.animatedFraction
                for (i in 0..8) {
                    matrixValues[i] = startValues[i] + (endValues[i] - startValues[i]) * fraction
                }
                imageMatrix.setValues(matrixValues)
                calculateImageRect()
                postInvalidateOnAnimation()
            }

            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    currentScaleLevel = 0
                    calculateImageRect()
                }
            })
        }.also { it.start() }
    }

    private fun cancelAnimations() {
        bounceAnimator?.cancel()
        restoreAnimator?.cancel()
        rotateAnimator?.cancel()
    }

    // ==================== 触摸事件处理 ====================

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (sourceBitmap == null) return false

        // 先让手势检测器处理双击事件
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleActionDown(event)
            MotionEvent.ACTION_POINTER_DOWN -> handleActionPointerDown(event)
            MotionEvent.ACTION_MOVE -> handleActionMove(event)
            MotionEvent.ACTION_POINTER_UP -> handleActionPointerUp(event)
            MotionEvent.ACTION_UP -> handleActionUp()
            MotionEvent.ACTION_CANCEL -> handleActionCancel()
        }

        return true
    }

    private fun handleActionDown(event: MotionEvent) {
        activePointerId = event.getPointerId(0)
        lastTouch.set(event.x, event.y)

        // 只要有手指触摸屏幕就展示网格线
        isShowingGuideLines = true

        activeEdge = detectEdgeTouch(event.x, event.y)

        if (activeEdge != EdgeType.NONE) {
            isDraggingEdge = true
            tempCropRect.set(cropRect)
            originalCropRect.set(cropRect)
        } else {
            isDraggingEdge = false
        }

        edgeTouchPoint.set(event.x, event.y)
        savedMatrix.set(imageMatrix)
    }

    private fun handleActionPointerDown(event: MotionEvent) {
        // 多指触摸时也保持网格线显示
        if (isDraggingEdge) {
            isDraggingEdge = false
        }
        isShowingGuideLines = true
        // 开始自定义缩放检测
        if (event.pointerCount == 2) {
            isScaling = true
            lastSpan = calculateSpan(event)
            // 锁定焦点为两指中点
            lockedFocus.set(
                (event.getX(0) + event.getX(1)) / 2f,
                (event.getY(0) + event.getY(1)) / 2f
            )
        }
    }

    private fun handleActionMove(event: MotionEvent) {
        // 自定义缩放检测
        if (isScaling && event.pointerCount >= 2) {
            handleCustomScale(event)
            return
        }

        if (isDraggingEdge) {
            handleEdgeDrag(event)
        } else {
            handleImageDrag(event)
        }
    }

    /**
     * 自定义缩放处理
     */
    private fun handleCustomScale(event: MotionEvent) {
        val currentSpan = calculateSpan(event)

        // 手指间距太小，跳过缩放
        if (currentSpan < MIN_POINTER_SPAN || lastSpan < MIN_POINTER_SPAN) {
            lastSpan = currentSpan
            return
        }

        // 计算缩放比例
        val scaleFactor = currentSpan / lastSpan
        lastSpan = currentSpan

        // 更新锁定焦点位置
        if (event.pointerCount >= 2) {
            lockedFocus.set(
                (event.getX(0) + event.getX(1)) / 2f,
                (event.getY(0) + event.getY(1)) / 2f
            )
        }

        // 应用缩放
        imageMatrix.postScale(scaleFactor, scaleFactor, lockedFocus.x, lockedFocus.y)
        calculateImageRect()
        postInvalidateOnAnimation()
    }

    /**
     * 计算两指间距
     */
    private fun calculateSpan(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun handleEdgeDrag(event: MotionEvent) {
        val dx = event.x - edgeTouchPoint.x
        val dy = event.y - edgeTouchPoint.y

        tempCropRect.set(originalCropRect)

        when (activeEdge) {
            EdgeType.TOP -> adjustTop(dy)
            EdgeType.BOTTOM -> adjustBottom(dy)
            EdgeType.LEFT -> adjustLeft(dx)
            EdgeType.RIGHT -> adjustRight(dx)
            EdgeType.TOP_LEFT -> adjustTopLeft(dx, dy)
            EdgeType.TOP_RIGHT -> adjustTopRight(dx, dy)
            EdgeType.BOTTOM_LEFT -> adjustBottomLeft(dx, dy)
            EdgeType.BOTTOM_RIGHT -> adjustBottomRight(dx, dy)
            else -> {}
        }

        cropRect.set(tempCropRect)
        postInvalidateOnAnimation()
    }

    /**
     * 计算固定比例下的最小宽度和最小高度
     * 确保宽高都至少为 MIN_CROP_SIZE
     */
    private fun getMinSizeForRatio(): Pair<Float, Float> {
        return if (currentRatio > 0) {
            if (currentRatio >= 1f) {
                // 宽度大于高度，以高度为基准
                MIN_CROP_SIZE to MIN_CROP_SIZE * currentRatio
            } else {
                // 高度大于宽度，以宽度为基准
                MIN_CROP_SIZE / currentRatio to MIN_CROP_SIZE
            }
        } else {
            MIN_CROP_SIZE to MIN_CROP_SIZE
        }
    }

    /**
     * 调整顶部边 - 以底边为锚点进行等比例缩放
     */
    private fun adjustTop(dy: Float) {
        if (currentRatio > 0) {
            // 固定比例：以底边为锚点，根据 dy 计算新的高度和宽度

            // 先保存原来的中心X坐标
            val centerX = tempCropRect.centerX()
            val bottom = tempCropRect.bottom

            // 计算新的高度（向上拖动 dy 为负值，高度增加）
            var newHeight = bottom - (tempCropRect.top + dy)
            var newWidth = newHeight * currentRatio

            // 检查最小尺寸
            val (minWidth, minHeight) = getMinSizeForRatio()
            if (newWidth < minWidth || newHeight < minHeight) {
                newWidth = minWidth.coerceAtLeast(minHeight * currentRatio)
                newHeight = newWidth / currentRatio
            }

            // 以底边为锚点，调整顶边和左右边
            tempCropRect.bottom = bottom
            tempCropRect.top = bottom - newHeight
            tempCropRect.left = centerX - newWidth / 2
            tempCropRect.right = centerX + newWidth / 2
        } else {
            // 自由比例：只调整顶部
            var newTop = tempCropRect.top + dy
            // 检查最小高度
            if (tempCropRect.bottom - newTop < MIN_CROP_SIZE) {
                newTop = tempCropRect.bottom - MIN_CROP_SIZE
            }
            tempCropRect.top = newTop
        }
    }

    /**
     * 调整底部边 - 以顶边为锚点进行等比例缩放
     */
    private fun adjustBottom(dy: Float) {
        if (currentRatio > 0) {
            // 固定比例：以顶边为锚点，根据 dy 计算新的高度和宽度

            // 先保存原来的中心X坐标
            val centerX = tempCropRect.centerX()
            val top = tempCropRect.top

            // 计算新的高度
            var newHeight = (tempCropRect.bottom + dy) - top
            var newWidth = newHeight * currentRatio

            // 检查最小尺寸
            val (minWidth, minHeight) = getMinSizeForRatio()
            if (newWidth < minWidth || newHeight < minHeight) {
                newWidth = minWidth.coerceAtLeast(minHeight * currentRatio)
                newHeight = newWidth / currentRatio
            }

            // 以顶边为锚点，调整底边和左右边
            tempCropRect.top = top
            tempCropRect.bottom = top + newHeight
            tempCropRect.left = centerX - newWidth / 2
            tempCropRect.right = centerX + newWidth / 2
        } else {
            // 自由比例：只调整底部
            var newBottom = tempCropRect.bottom + dy
            // 检查最小高度
            if (newBottom - tempCropRect.top < MIN_CROP_SIZE) {
                newBottom = tempCropRect.top + MIN_CROP_SIZE
            }
            tempCropRect.bottom = newBottom
        }
    }

    /**
     * 调整左边 - 以右边为锚点进行等比例缩放
     */
    private fun adjustLeft(dx: Float) {
        if (currentRatio > 0) {
            // 固定比例：以右边为锚点，根据 dx 计算新的宽度和高度

            // 先保存原来的中心Y坐标
            val centerY = tempCropRect.centerY()
            val right = tempCropRect.right

            // 计算新的宽度（向左拖动 dx 为负值，宽度增加）
            var newWidth = right - (tempCropRect.left + dx)
            var newHeight = newWidth / currentRatio

            // 检查最小尺寸
            val (minWidth, minHeight) = getMinSizeForRatio()
            if (newWidth < minWidth || newHeight < minHeight) {
                newWidth = minWidth.coerceAtLeast(minHeight * currentRatio)
                newHeight = newWidth / currentRatio
            }

            // 以右边为锚点，调整左边和上下边
            tempCropRect.right = right
            tempCropRect.left = right - newWidth
            tempCropRect.top = centerY - newHeight / 2
            tempCropRect.bottom = centerY + newHeight / 2
        } else {
            // 自由比例：只调整左侧
            var newLeft = tempCropRect.left + dx
            // 检查最小宽度
            if (tempCropRect.right - newLeft < MIN_CROP_SIZE) {
                newLeft = tempCropRect.right - MIN_CROP_SIZE
            }
            tempCropRect.left = newLeft
        }
    }

    /**
     * 调整右边 - 以左边为锚点进行等比例缩放
     */
    private fun adjustRight(dx: Float) {
        if (currentRatio > 0) {
            // 固定比例：以左边为锚点，根据 dx 计算新的宽度和高度

            // 先保存原来的中心Y坐标
            val centerY = tempCropRect.centerY()
            val left = tempCropRect.left

            // 计算新的宽度
            var newWidth = (tempCropRect.right + dx) - left
            var newHeight = newWidth / currentRatio

            // 检查最小尺寸
            val (minWidth, minHeight) = getMinSizeForRatio()
            if (newWidth < minWidth || newHeight < minHeight) {
                newWidth = minWidth.coerceAtLeast(minHeight * currentRatio)
                newHeight = newWidth / currentRatio
            }

            // 以左边为锚点，调整右边和上下边
            tempCropRect.left = left
            tempCropRect.right = left + newWidth
            tempCropRect.top = centerY - newHeight / 2
            tempCropRect.bottom = centerY + newHeight / 2
        } else {
            // 自由比例：只调整右侧
            var newRight = tempCropRect.right + dx
            // 检查最小宽度
            if (newRight - tempCropRect.left < MIN_CROP_SIZE) {
                newRight = tempCropRect.left + MIN_CROP_SIZE
            }
            tempCropRect.right = newRight
        }
    }

    /**
     * 调整左上角 - 以右下角为锚点进行等比例缩放
     */
    private fun adjustTopLeft(dx: Float, dy: Float) {
        if (currentRatio > 0) {

            // 根据拖动方向决定以哪个方向为主
            var newWidth: Float
            var newHeight: Float

            if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                // 以水平方向为主
                newWidth = tempCropRect.right - (tempCropRect.left + dx)
                newHeight = newWidth / currentRatio
            } else {
                // 以垂直方向为主
                newHeight = tempCropRect.bottom - (tempCropRect.top + dy)
                newWidth = newHeight * currentRatio
            }

            // 检查最小尺寸
            val (minWidth, minHeight) = getMinSizeForRatio()
            if (newWidth < minWidth || newHeight < minHeight) {
                newWidth = minWidth.coerceAtLeast(minHeight * currentRatio)
                newHeight = newWidth / currentRatio
            }

            // 以右下角为锚点，调整左上角
            tempCropRect.left = tempCropRect.right - newWidth
            tempCropRect.top = tempCropRect.bottom - newHeight
        } else {
            // 自由比例：分别调整左和上
            var newLeft = tempCropRect.left + dx
            var newTop = tempCropRect.top + dy

            // 检查最小尺寸
            if (tempCropRect.right - newLeft < MIN_CROP_SIZE) {
                newLeft = tempCropRect.right - MIN_CROP_SIZE
            }
            if (tempCropRect.bottom - newTop < MIN_CROP_SIZE) {
                newTop = tempCropRect.bottom - MIN_CROP_SIZE
            }

            tempCropRect.left = newLeft
            tempCropRect.top = newTop
        }
    }

    /**
     * 调整右上角 - 以左下角为锚点进行等比例缩放
     */
    private fun adjustTopRight(dx: Float, dy: Float) {
        if (currentRatio > 0) {

            var newWidth: Float
            var newHeight: Float

            if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                // 以水平方向为主
                newWidth = (tempCropRect.right + dx) - tempCropRect.left
                newHeight = newWidth / currentRatio
            } else {
                // 以垂直方向为主
                newHeight = tempCropRect.bottom - (tempCropRect.top + dy)
                newWidth = newHeight * currentRatio
            }

            // 检查最小尺寸
            val (minWidth, minHeight) = getMinSizeForRatio()
            if (newWidth < minWidth || newHeight < minHeight) {
                newWidth = minWidth.coerceAtLeast(minHeight * currentRatio)
                newHeight = newWidth / currentRatio
            }

            // 以左下角为锚点，调整右上角
            tempCropRect.right = tempCropRect.left + newWidth
            tempCropRect.top = tempCropRect.bottom - newHeight
        } else {
            // 自由比例
            var newRight = tempCropRect.right + dx
            var newTop = tempCropRect.top + dy

            if (newRight - tempCropRect.left < MIN_CROP_SIZE) {
                newRight = tempCropRect.left + MIN_CROP_SIZE
            }
            if (tempCropRect.bottom - newTop < MIN_CROP_SIZE) {
                newTop = tempCropRect.bottom - MIN_CROP_SIZE
            }

            tempCropRect.right = newRight
            tempCropRect.top = newTop
        }
    }

    /**
     * 调整左下角 - 以右上角为锚点进行等比例缩放
     */
    private fun adjustBottomLeft(dx: Float, dy: Float) {
        if (currentRatio > 0) {

            var newWidth: Float
            var newHeight: Float

            if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                // 以水平方向为主
                newWidth = tempCropRect.right - (tempCropRect.left + dx)
                newHeight = newWidth / currentRatio
            } else {
                // 以垂直方向为主
                newHeight = (tempCropRect.bottom + dy) - tempCropRect.top
                newWidth = newHeight * currentRatio
            }

            // 检查最小尺寸
            val (minWidth, minHeight) = getMinSizeForRatio()
            if (newWidth < minWidth || newHeight < minHeight) {
                newWidth = minWidth.coerceAtLeast(minHeight * currentRatio)
                newHeight = newWidth / currentRatio
            }

            // 以右上角为锚点，调整左下角
            tempCropRect.left = tempCropRect.right - newWidth
            tempCropRect.bottom = tempCropRect.top + newHeight
        } else {
            // 自由比例
            var newLeft = tempCropRect.left + dx
            var newBottom = tempCropRect.bottom + dy

            if (tempCropRect.right - newLeft < MIN_CROP_SIZE) {
                newLeft = tempCropRect.right - MIN_CROP_SIZE
            }
            if (newBottom - tempCropRect.top < MIN_CROP_SIZE) {
                newBottom = tempCropRect.top + MIN_CROP_SIZE
            }

            tempCropRect.left = newLeft
            tempCropRect.bottom = newBottom
        }
    }

    /**
     * 调整右下角 - 以左上角为锚点进行等比例缩放
     */
    private fun adjustBottomRight(dx: Float, dy: Float) {
        if (currentRatio > 0) {

            var newWidth: Float
            var newHeight: Float

            if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                // 以水平方向为主
                newWidth = (tempCropRect.right + dx) - tempCropRect.left
                newHeight = newWidth / currentRatio
            } else {
                // 以垂直方向为主
                newHeight = (tempCropRect.bottom + dy) - tempCropRect.top
                newWidth = newHeight * currentRatio
            }

            // 检查最小尺寸
            val (minWidth, minHeight) = getMinSizeForRatio()
            if (newWidth < minWidth || newHeight < minHeight) {
                newWidth = minWidth.coerceAtLeast(minHeight * currentRatio)
                newHeight = newWidth / currentRatio
            }

            // 以左上角为锚点，调整右下角
            tempCropRect.right = tempCropRect.left + newWidth
            tempCropRect.bottom = tempCropRect.top + newHeight
        } else {
            // 自由比例
            var newRight = tempCropRect.right + dx
            var newBottom = tempCropRect.bottom + dy

            if (newRight - tempCropRect.left < MIN_CROP_SIZE) {
                newRight = tempCropRect.left + MIN_CROP_SIZE
            }
            if (newBottom - tempCropRect.top < MIN_CROP_SIZE) {
                newBottom = tempCropRect.top + MIN_CROP_SIZE
            }

            tempCropRect.right = newRight
            tempCropRect.bottom = newBottom
        }
    }

    private fun handleImageDrag(event: MotionEvent) {
        val pointerIndex = event.findPointerIndex(activePointerId)
        if (pointerIndex < 0) return

        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        val dx = x - lastTouch.x
        val dy = y - lastTouch.y

        imageMatrix.postTranslate(dx, dy)
        calculateImageRect()
        lastTouch.set(x, y)
        postInvalidateOnAnimation()
    }

    private fun handleActionPointerUp(event: MotionEvent) {
        val pointerIndex = event.actionIndex
        val pointerId = event.getPointerId(pointerIndex)

        // 结束缩放
        if (event.pointerCount <= 2) {
            isScaling = false
            lastSpan = 0f
        }
        isShowingGuideLines = false
        // 如果抬起的是当前活动手指，切换到另一个手指
        if (pointerId == activePointerId) {
            // 找到另一个仍然按下的手指
            val newPointerIndex = if (pointerIndex == 0) 1 else 0
            // 确保新索引有效（指针数量足够）
            if (event.pointerCount > newPointerIndex) {
                activePointerId = event.getPointerId(newPointerIndex)
                lastTouch.set(event.getX(newPointerIndex), event.getY(newPointerIndex))
            }
        }

        // 抬起后剩余的手指数量
        val remainingPointers = event.pointerCount - 1
        if (remainingPointers >= 1) {
            // 还有手指在屏幕上，更新到剩余手指的位置
            val remainingIndex = if (pointerIndex == 0) 1 else 0
            if (event.pointerCount > remainingIndex) {
                lastTouch.set(event.getX(remainingIndex), event.getY(remainingIndex))
            }
        }
    }

    private fun handleActionUp() {
        // 结束缩放
        isScaling = false
        lastSpan = 0f

        // 所有手指离开屏幕时隐藏网格线
        isShowingGuideLines = false

        if (isDraggingEdge) {
            animateRestoreCropRect()
        } else {
            animateCheckBounds()
        }

        activePointerId = -1
        isDraggingEdge = false
        activeEdge = EdgeType.NONE
    }

    private fun handleActionCancel() {
        isScaling = false
        lastSpan = 0f
        activePointerId = -1
        isDraggingEdge = false
        isShowingGuideLines = false
        activeEdge = EdgeType.NONE
    }

    private fun detectEdgeTouch(x: Float, y: Float): EdgeType {
        val threshold = EDGE_TOUCH_THRESHOLD

        val nearTop = kotlin.math.abs(y - cropRect.top) < threshold
        val nearBottom = kotlin.math.abs(y - cropRect.bottom) < threshold
        val nearLeft = kotlin.math.abs(x - cropRect.left) < threshold
        val nearRight = kotlin.math.abs(x - cropRect.right) < threshold

        val inHorizontalRange = x >= cropRect.left - threshold && x <= cropRect.right + threshold
        val inVerticalRange = y >= cropRect.top - threshold && y <= cropRect.bottom + threshold

        return when {
            nearTop && nearLeft && inHorizontalRange && inVerticalRange -> EdgeType.TOP_LEFT
            nearTop && nearRight && inHorizontalRange && inVerticalRange -> EdgeType.TOP_RIGHT
            nearBottom && nearLeft && inHorizontalRange && inVerticalRange -> EdgeType.BOTTOM_LEFT
            nearBottom && nearRight && inHorizontalRange && inVerticalRange -> EdgeType.BOTTOM_RIGHT
            nearTop && inHorizontalRange && x >= cropRect.left && x <= cropRect.right -> EdgeType.TOP
            nearBottom && inHorizontalRange && x >= cropRect.left && x <= cropRect.right -> EdgeType.BOTTOM
            nearLeft && inVerticalRange && y >= cropRect.top && y <= cropRect.bottom -> EdgeType.LEFT
            nearRight && inVerticalRange && y >= cropRect.top && y <= cropRect.bottom -> EdgeType.RIGHT
            else -> EdgeType.NONE
        }
    }

    private fun animateRestoreCropRect() {
        val bitmap = sourceBitmap ?: return
        calculateImageRect()

        // 当前裁剪框中心对应的图片区域
        val cropCenterX = cropRect.centerX()
        val cropCenterY = cropRect.centerY()

        val currentScale = getCurrentScale()
        val currentTranslate = getCurrentTranslate()

        // 裁剪框中心在图片坐标系中的位置
        val imageCenterX = (cropCenterX - currentTranslate.x) / currentScale
        val imageCenterY = (cropCenterY - currentTranslate.y) / currentScale

        // 计算目标裁剪框
        val targetCropRect = if (currentRatio > 0) {
            // 固定比例：还原到原始裁剪框
            RectF(originalCropRect)
        } else {
            // 自由比例：保持当前裁剪框的宽高比，居中，不超过视图 80%
            val cropAspectRatio = cropRect.width() / cropRect.height()

            // 视图允许的最大裁剪框尺寸
            val maxCropWidth = width * CROP_BOX_SIZE_RATIO
            val maxCropHeight = height * CROP_BOX_SIZE_RATIO

            // 计算目标裁剪框大小，保持比例且不超过最大尺寸
            val targetRect = RectF()
            if (cropAspectRatio >= maxCropWidth / maxCropHeight) {
                // 裁剪框较宽，以最大宽度为准
                val targetWidth = maxCropWidth
                val targetHeight = targetWidth / cropAspectRatio
                targetRect.left = (width - targetWidth) / 2
                targetRect.top = (height - targetHeight) / 2
                targetRect.right = targetRect.left + targetWidth
                targetRect.bottom = targetRect.top + targetHeight
            } else {
                // 裁剪框较高，以最大高度为准
                val targetHeight = maxCropHeight
                val targetWidth = targetHeight * cropAspectRatio
                targetRect.left = (width - targetWidth) / 2
                targetRect.top = (height - targetHeight) / 2
                targetRect.right = targetRect.left + targetWidth
                targetRect.bottom = targetRect.top + targetHeight
            }
            targetRect
        }

        // 计算需要的缩放比例
        val scaleX = targetCropRect.width() / cropRect.width()
        val scaleY = targetCropRect.height() / cropRect.height()
        val scale = maxOf(scaleX, scaleY)

        // 目标缩放值
        val targetScale = currentScale * scale

        // 目标平移值
        val targetTranslateX = targetCropRect.centerX() - imageCenterX * targetScale
        val targetTranslateY = targetCropRect.centerY() - imageCenterY * targetScale

        cancelAnimations()

        val startCropRect = RectF(cropRect)
        val endCropRect = targetCropRect

        savedMatrix.set(imageMatrix)

        restoreAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = decelerateInterpolator

            addUpdateListener { animation ->
                val fraction = animation.animatedFraction

                // 动画还原裁剪框
                cropRect.left =
                    startCropRect.left + (endCropRect.left - startCropRect.left) * fraction
                cropRect.top = startCropRect.top + (endCropRect.top - startCropRect.top) * fraction
                cropRect.right =
                    startCropRect.right + (endCropRect.right - startCropRect.right) * fraction
                cropRect.bottom =
                    startCropRect.bottom + (endCropRect.bottom - startCropRect.bottom) * fraction

                // 动画缩放图片
                val animScale = currentScale + (targetScale - currentScale) * fraction
                // 动画平移图片
                val animTranslateX =
                    currentTranslate.x + (targetTranslateX - currentTranslate.x) * fraction
                val animTranslateY =
                    currentTranslate.y + (targetTranslateY - currentTranslate.y) * fraction

                // 重建矩阵：scale -> rotate -> translate
                imageMatrix.reset()
                imageMatrix.postScale(animScale, animScale)

                // 应用旋转
                if (currentRotation != 0) {
                    val scaledWidth = bitmap.width * animScale
                    val scaledHeight = bitmap.height * animScale
                    imageMatrix.postRotate(
                        currentRotation.toFloat(),
                        scaledWidth / 2,
                        scaledHeight / 2
                    )
                }

                imageMatrix.postTranslate(animTranslateX, animTranslateY)

                calculateImageRect()
                postInvalidateOnAnimation()
            }

            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (currentRatio > 0) {
                        cropRect.set(originalCropRect)
                    } else {
                        cropRect.set(endCropRect)
                    }
                    calculateImageRect()
                    checkAndFixBounds()
                }
            })
        }.also { it.start() }
    }

    private fun checkAndFixBounds() {
        val bitmap = sourceBitmap ?: return
        calculateImageRect()

        // 检查图片是否比裁剪框小，如果是则需要放大
        val currentScale = getCurrentScale()
        val (bmpWidth, bmpHeight) = getRotatedBitmapSize(bitmap)
        val minScale = maxOf(
            cropRect.width() / bmpWidth,
            cropRect.height() / bmpHeight
        )

        if (currentScale < minScale) {
            // 图片太小，放大到能覆盖裁剪框
            val scale = minScale / currentScale
            imageMatrix.postScale(scale, scale, cropRect.centerX(), cropRect.centerY())
            calculateImageRect()
        }

        // 修正边界
        fixTranslateBounds()
    }

    /**
     * 只修正平移边界，不做缩放修正
     * 用于缩放过程中，避免闪烁
     */
    private fun fixTranslateBounds() {
        calculateImageRect()

        var dx = 0f
        var dy = 0f

        if (imageRect.width() >= cropRect.width()) {
            when {
                imageRect.left > cropRect.left -> dx = cropRect.left - imageRect.left
                imageRect.right < cropRect.right -> dx = cropRect.right - imageRect.right
            }
        } else {
            // 图片宽度小于裁剪框，居中
            dx = cropRect.centerX() - imageRect.centerX()
        }

        if (imageRect.height() >= cropRect.height()) {
            when {
                imageRect.top > cropRect.top -> dy = cropRect.top - imageRect.top
                imageRect.bottom < cropRect.bottom -> dy = cropRect.bottom - imageRect.bottom
            }
        } else {
            // 图片高度小于裁剪框，居中
            dy = cropRect.centerY() - imageRect.centerY()
        }

        if (dx != 0f || dy != 0f) {
            imageMatrix.postTranslate(dx, dy)
            calculateImageRect()
        }
    }

    private fun animateCheckBounds() {
        val bitmap = sourceBitmap ?: return
        calculateImageRect()

        val currentScale = getCurrentScale()
        val (bmpWidth, bmpHeight) = getRotatedBitmapSize(bitmap)
        val minScale = maxOf(
            cropRect.width() / bmpWidth,
            cropRect.height() / bmpHeight
        )

        // 如果图片比裁剪框小，动画放大到覆盖裁剪框
        if (currentScale < minScale) {
            animateFitToCrop()
            return
        }

        var dx = 0f
        var dy = 0f
        var needAnimate = false

        // 图片宽度足够，检查左右边界
        if (imageRect.width() >= cropRect.width()) {
            when {
                imageRect.left > cropRect.left -> {
                    dx = cropRect.left - imageRect.left
                    needAnimate = true
                }

                imageRect.right < cropRect.right -> {
                    dx = cropRect.right - imageRect.right
                    needAnimate = true
                }
            }
        }

        // 图片高度足够，检查上下边界
        if (imageRect.height() >= cropRect.height()) {
            when {
                imageRect.top > cropRect.top -> {
                    dy = cropRect.top - imageRect.top
                    needAnimate = true
                }

                imageRect.bottom < cropRect.bottom -> {
                    dy = cropRect.bottom - imageRect.bottom
                    needAnimate = true
                }
            }
        }

        if (needAnimate && (dx != 0f || dy != 0f)) {
            animateBounce(dx, dy)
        } else {
            postInvalidate()
        }
    }

    private fun animateBounce(dx: Float, dy: Float) {
        cancelAnimations()

        savedMatrix.set(imageMatrix)

        bounceAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            interpolator = decelerateInterpolator

            addUpdateListener { animation ->
                val fraction = animation.animatedFraction
                val currentDx = dx * fraction
                val currentDy = dy * fraction

                imageMatrix.set(savedMatrix)
                imageMatrix.postTranslate(currentDx, currentDy)
                calculateImageRect()
                postInvalidateOnAnimation()
            }
        }.also { it.start() }
    }

    // ==================== 绘制方法 ====================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val bitmap = sourceBitmap ?: return

        // 绘制图片
        canvas.save()
        canvas.concat(imageMatrix)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        canvas.restore()

        // 绘制遮罩和裁剪框
        drawOverlay(canvas)
        drawCropBox(canvas)

        // 绘制三分线网格
        if (isShowingGuideLines) {
            drawGuideLines(canvas)
        }
    }

    private fun drawOverlay(canvas: Canvas) {
        clipPath.reset()
        clipPath.addRect(
            cropRect.left,
            cropRect.top,
            cropRect.right,
            cropRect.bottom,
            Path.Direction.CW
        )

        canvas.save()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            canvas.clipOutPath(clipPath)
        } else {
            canvas.clipPath(clipPath, Region.Op.DIFFERENCE)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
        canvas.restore()
    }

    private fun drawCropBox(canvas: Canvas) {
        canvas.drawRect(cropRect, cropBoxPaint)

        val cornerLength = CORNER_LINE_LENGTH

        // 四角标记
        canvas.drawLine(
            cropRect.left, cropRect.top,
            cropRect.left + cornerLength, cropRect.top, cornerPaint
        )
        canvas.drawLine(
            cropRect.left, cropRect.top,
            cropRect.left, cropRect.top + cornerLength, cornerPaint
        )

        canvas.drawLine(
            cropRect.right - cornerLength, cropRect.top,
            cropRect.right, cropRect.top, cornerPaint
        )
        canvas.drawLine(
            cropRect.right, cropRect.top,
            cropRect.right, cropRect.top + cornerLength, cornerPaint
        )

        canvas.drawLine(
            cropRect.left, cropRect.bottom - cornerLength,
            cropRect.left, cropRect.bottom, cornerPaint
        )
        canvas.drawLine(
            cropRect.left, cropRect.bottom,
            cropRect.left + cornerLength, cropRect.bottom, cornerPaint
        )

        canvas.drawLine(
            cropRect.right, cropRect.bottom - cornerLength,
            cropRect.right, cropRect.bottom, cornerPaint
        )
        canvas.drawLine(
            cropRect.right - cornerLength, cropRect.bottom,
            cropRect.right, cropRect.bottom, cornerPaint
        )
    }

    private fun drawGuideLines(canvas: Canvas) {
        val thirdWidth = cropRect.width() / 3f
        val thirdHeight = cropRect.height() / 3f

        for (i in 1..2) {
            val x = cropRect.left + thirdWidth * i
            canvas.drawLine(x, cropRect.top, x, cropRect.bottom, guideLinePaint)
        }

        for (i in 1..2) {
            val y = cropRect.top + thirdHeight * i
            canvas.drawLine(cropRect.left, y, cropRect.right, y, guideLinePaint)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            calculateCropRect()
            if (sourceBitmap != null) {
                fitImageToCrop()
            }
        }
    }

    fun getCroppedImage(): Bitmap? {
        val bitmap = sourceBitmap ?: return null

        val cropWidth = cropRect.width().toInt()
        val cropHeight = cropRect.height().toInt()

        if (cropWidth <= 0 || cropHeight <= 0) return null

        // 创建结果 bitmap
        val result = Bitmap.createBitmap(cropWidth, cropHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // 计算从裁剪框到视图的偏移
        canvas.translate(-cropRect.left, -cropRect.top)

        // 应用图片变换矩阵（绘制图片到裁剪框位置）
        canvas.concat(imageMatrix)

        // 绘制图片
        canvas.drawBitmap(bitmap, 0f, 0f, null)

        return result
    }

    /**
     * 重置裁剪框和图片
     */
    fun reset() {
        currentScaleLevel = 0
        currentRotation = 0
        calculateCropRect()
        fitImageToCrop()
        invalidate()
    }
}
