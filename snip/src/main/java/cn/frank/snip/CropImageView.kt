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
import kotlin.math.abs
import kotlin.math.sqrt
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withMatrix
import androidx.core.graphics.withSave

/**
 * 图片裁剪 View
 *
 * 功能特性：
 * - 任意裁剪比例，包括：1:1、16:9、9:16、4:3、3:4、自由比例
 * - 单指拖动图片
 * - 双指缩放图片
 * - 双击放大（最多5次）
 * - 固定裁剪框大小（占视图区域80%，居中）
 * - 拖动裁剪框边缘调整裁剪区域，松手后还原
 * - 固定比例模式下保持裁剪框比例
 * - Matrix 实现，流畅动画
 * - 边界回弹动画
 * - 三分线网格（触摸时显示）
 * - 图片旋转功能
 *
 * @author shangmingchao
 */
class CropImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ==================== 边缘/角类型（位标志） ====================
    private enum class EdgeType(val flags: Int) {
        NONE(0),
        TOP(FLAG_TOP),
        BOTTOM(FLAG_BOTTOM),
        LEFT(FLAG_LEFT),
        RIGHT(FLAG_RIGHT),
        TOP_LEFT(FLAG_TOP or FLAG_LEFT),
        TOP_RIGHT(FLAG_TOP or FLAG_RIGHT),
        BOTTOM_LEFT(FLAG_BOTTOM or FLAG_LEFT),
        BOTTOM_RIGHT(FLAG_BOTTOM or FLAG_RIGHT);

        val movesTop: Boolean get() = flags and FLAG_TOP != 0
        val movesBottom: Boolean get() = flags and FLAG_BOTTOM != 0
        val movesLeft: Boolean get() = flags and FLAG_LEFT != 0
        val movesRight: Boolean get() = flags and FLAG_RIGHT != 0
    }

    // ==================== 常量配置 ====================
    companion object {
        private const val FLAG_TOP = 1
        private const val FLAG_BOTTOM = 2
        private const val FLAG_LEFT = 4
        private const val FLAG_RIGHT = 8

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
    private var currentScaleLevel = 0
    private val lockedFocus = PointF()
    private var isScaling = false
    private var lastSpan = 0f

    // ==================== 边缘拖动相关 ====================
    private var activeEdge = EdgeType.NONE
    private var isDraggingEdge = false
    private var isShowingGuideLines = false
    private val edgeTouchPoint = PointF()

    // ==================== 旋转相关 ====================
    private var currentRotation = 0

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

    // ==================== 公共 API ====================

    /**
     * 设置图片位图
     */
    fun setImageBitmap(bitmap: Bitmap?) {
        sourceBitmap = bitmap
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
        if (currentRatio != ratio) {
            currentRatio = ratio
            calculateCropRect()
            fitImageToCrop()
            invalidate()
        }
    }

    /**
     * 旋转
     */
    fun rotate(degrees: Float) {
        if (sourceBitmap != null) animateRotate(degrees)
    }

    /**
     * 获取裁剪后的图片
     */
    fun getCroppedImage(): Bitmap? {
        val bitmap = sourceBitmap ?: return null
        val cropWidth = cropRect.width().toInt()
        val cropHeight = cropRect.height().toInt()
        if (cropWidth <= 0 || cropHeight <= 0) return null

        return createBitmap(cropWidth, cropHeight).also { result ->
            Canvas(result).apply {
                translate(-cropRect.left, -cropRect.top)
                concat(imageMatrix)
                drawBitmap(bitmap, 0f, 0f, null)
            }
        }
    }

    fun reset() {
        currentScaleLevel = 0
        currentRotation = 0
        calculateCropRect()
        fitImageToCrop()
        invalidate()
    }

    // ==================== 矩阵与裁剪框计算 ====================

    private fun resetMatrix() {
        imageMatrix.reset()
        currentScaleLevel = 0
        currentRotation = 0
    }

    private fun calculateCropRect() {
        val viewWidth = width
        val viewHeight = height
        if (viewWidth <= 0 || viewHeight <= 0) return

        val maxW = viewWidth * CROP_BOX_SIZE_RATIO
        val maxH = viewHeight * CROP_BOX_SIZE_RATIO

        val (cropWidth, cropHeight) = if (currentRatio > 0) {
            calculateFixedSizeRatio(maxW, maxH, currentRatio)
        } else {
            sourceBitmap?.let { bitmap ->
                val (bmpW, bmpH) = getRotatedBitmapSize(bitmap)
                val scale = minOf(maxW / bmpW, maxH / bmpH, 1f)
                bmpW * scale to bmpH * scale
            } ?: run {
                val size = maxW.coerceAtMost(maxH)
                size to size
            }
        }

        val left = (viewWidth - cropWidth) / 2f
        val top = (viewHeight - cropHeight) / 2f
        cropRect.set(left, top, left + cropWidth, top + cropHeight)
        originalCropRect.set(cropRect)
    }

    /**
     * 在给定最大区域内，计算指定比例的裁剪框尺寸
     */
    private fun calculateFixedSizeRatio(maxW: Float, maxH: Float, ratio: Float): Pair<Float, Float> {
        return if (ratio >= 1f) {
            val h = (maxW / ratio).coerceAtMost(maxH)
            h * ratio to h
        } else {
            val w = (maxH * ratio).coerceAtMost(maxW)
            w to w / ratio
        }
    }

    private fun calculateImageRect() {
        sourceBitmap?.let { bitmap ->
            imageRect.set(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
            imageMatrix.mapRect(imageRect)
        }
    }

    private fun getRotatedBitmapSize(bitmap: Bitmap): Pair<Float, Float> {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        return if (currentRotation == 90 || currentRotation == 270) h to w else w to h
    }

    /**
     * 构建适配裁剪框的变换矩阵（缩放 → 旋转 → 平移居中）
     */
    private fun buildFitMatrix(bitmap: Bitmap, targetRect: RectF = cropRect): Matrix {
        val (bmpW, bmpH) = getRotatedBitmapSize(bitmap)
        val scale = maxOf(targetRect.width() / bmpW, targetRect.height() / bmpH)

        return Matrix().apply {
            postScale(scale, scale)
            if (currentRotation != 0) {
                postRotate(currentRotation.toFloat(), bitmap.width * scale / 2, bitmap.height * scale / 2)
            }
            val mapped = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
            mapRect(mapped)
            postTranslate(targetRect.centerX() - mapped.centerX(), targetRect.centerY() - mapped.centerY())
        }
    }

    private fun fitImageToCrop() {
        val bitmap = sourceBitmap ?: return
        if (width <= 0 || height <= 0) return
        imageMatrix.set(buildFitMatrix(bitmap))
        currentScaleLevel = 0
        calculateImageRect()
    }

    private fun getCurrentScale(): Float {
        imageMatrix.getValues(matrixValues)
        val scaleX = matrixValues[Matrix.MSCALE_X]
        val skewY = matrixValues[Matrix.MSKEW_Y]
        return sqrt(scaleX * scaleX + skewY * skewY)
    }

    private fun getCurrentTranslate(): PointF {
        imageMatrix.getValues(matrixValues)
        return PointF(matrixValues[Matrix.MTRANS_X], matrixValues[Matrix.MTRANS_Y])
    }

    private fun getMinScale(): Float {
        val bitmap = sourceBitmap ?: return 1f
        val (bmpW, bmpH) = getRotatedBitmapSize(bitmap)
        return maxOf(cropRect.width() / bmpW, cropRect.height() / bmpH)
    }

    /**
     * 计算图片边界修正偏移量
     */
    private fun calculateBoundsOffset(): Pair<Float, Float> {
        calculateImageRect()
        val dx = when {
            imageRect.width() >= cropRect.width() -> when {
                imageRect.left > cropRect.left -> cropRect.left - imageRect.left
                imageRect.right < cropRect.right -> cropRect.right - imageRect.right
                else -> 0f
            }
            else -> cropRect.centerX() - imageRect.centerX()
        }
        val dy = when {
            imageRect.height() >= cropRect.height() -> when {
                imageRect.top > cropRect.top -> cropRect.top - imageRect.top
                imageRect.bottom < cropRect.bottom -> cropRect.bottom - imageRect.bottom
                else -> 0f
            }
            else -> cropRect.centerY() - imageRect.centerY()
        }
        return dx to dy
    }

    // ==================== 旋转功能 ====================

    private fun animateRotate(degrees: Float) {
        cancelAnimations()
        currentRotation = (currentRotation + degrees.toInt() + 360) % 360

        val centerX = cropRect.centerX()
        val centerY = cropRect.centerY()
        savedMatrix.set(imageMatrix)

        rotateAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = decelerateInterpolator
            addUpdateListener { animation ->
                imageMatrix.set(savedMatrix)
                imageMatrix.postRotate(degrees * animation.animatedFraction, centerX, centerY)
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    ensureCropWithinBounds()
                    invalidate()
                }
            })
        }.also { it.start() }
    }

    private fun ensureCropWithinBounds() {
        calculateImageRect()
        val currentScale = getCurrentScale()
        val minScale = getMinScale()
        if (currentScale < minScale) {
            val scale = minScale / currentScale
            imageMatrix.postScale(scale, scale, cropRect.centerX(), cropRect.centerY())
        }
        fixTranslateBounds()
        calculateImageRect()
    }

    // ==================== 手势监听 ====================

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
        val targetScale = (getCurrentScale() * SCALE_FACTOR).coerceAtMost(MAX_SCALE)
        animateZoom(focusX, focusY, targetScale)
    }

    // ==================== 动画方法 ====================

    private fun animateZoom(focusX: Float, focusY: Float, targetScale: Float) {
        val startScale = getCurrentScale()
        savedMatrix.set(imageMatrix)
        cancelAnimations()

        bounceAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = overshootInterpolator
            addUpdateListener { animation ->
                val fraction = animation.animatedFraction
                val scaleValue = startScale + (targetScale - startScale) * fraction
                imageMatrix.set(savedMatrix)
                imageMatrix.postScale(scaleValue / startScale, scaleValue / startScale, focusX, focusY)
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
        val targetMatrix = buildFitMatrix(bitmap)
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

    private fun animateRestoreCropRect() {
        val bitmap = sourceBitmap ?: return
        calculateImageRect()

        val currentScale = getCurrentScale()
        val currentTranslate = getCurrentTranslate()
        val imageCenterX = (cropRect.centerX() - currentTranslate.x) / currentScale
        val imageCenterY = (cropRect.centerY() - currentTranslate.y) / currentScale

        val targetCropRect = if (currentRatio > 0) {
            RectF(originalCropRect)
        } else {
            val aspectRatio = cropRect.width() / cropRect.height()
            val maxCropWidth = width * CROP_BOX_SIZE_RATIO
            val maxCropHeight = height * CROP_BOX_SIZE_RATIO
            val (tw, th) = calculateFixedSizeRatio(maxCropWidth, maxCropHeight, aspectRatio)
            RectF((width - tw) / 2, (height - th) / 2, (width + tw) / 2, (height + th) / 2)
        }

        val scale = maxOf(targetCropRect.width() / cropRect.width(), targetCropRect.height() / cropRect.height())
        val targetScale = currentScale * scale
        val targetTranslateX = targetCropRect.centerX() - imageCenterX * targetScale
        val targetTranslateY = targetCropRect.centerY() - imageCenterY * targetScale

        cancelAnimations()

        val startCropRect = RectF(cropRect)
        savedMatrix.set(imageMatrix)

        restoreAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = decelerateInterpolator
            addUpdateListener { animation ->
                val f = animation.animatedFraction

                cropRect.left = startCropRect.left + (targetCropRect.left - startCropRect.left) * f
                cropRect.top = startCropRect.top + (targetCropRect.top - startCropRect.top) * f
                cropRect.right = startCropRect.right + (targetCropRect.right - startCropRect.right) * f
                cropRect.bottom = startCropRect.bottom + (targetCropRect.bottom - startCropRect.bottom) * f

                val animScale = currentScale + (targetScale - currentScale) * f
                val animTx = currentTranslate.x + (targetTranslateX - currentTranslate.x) * f
                val animTy = currentTranslate.y + (targetTranslateY - currentTranslate.y) * f

                imageMatrix.reset()
                imageMatrix.postScale(animScale, animScale)
                if (currentRotation != 0) {
                    imageMatrix.postRotate(
                        currentRotation.toFloat(),
                        bitmap.width * animScale / 2,
                        bitmap.height * animScale / 2
                    )
                }
                imageMatrix.postTranslate(animTx, animTy)
                calculateImageRect()
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    cropRect.set(if (currentRatio > 0) originalCropRect else targetCropRect)
                    calculateImageRect()
                    checkAndFixBounds()
                }
            })
        }.also { it.start() }
    }

    private fun animateBounce(dx: Float, dy: Float) {
        cancelAnimations()
        savedMatrix.set(imageMatrix)

        bounceAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            interpolator = decelerateInterpolator
            addUpdateListener { animation ->
                val f = animation.animatedFraction
                imageMatrix.set(savedMatrix)
                imageMatrix.postTranslate(dx * f, dy * f)
                calculateImageRect()
                postInvalidateOnAnimation()
            }
        }.also { it.start() }
    }

    private fun cancelAnimations() {
        bounceAnimator?.cancel()
        restoreAnimator?.cancel()
        rotateAnimator?.cancel()
    }

    // ==================== 边界检查 ====================

    private fun checkAndFixBounds() {
        calculateImageRect()
        val currentScale = getCurrentScale()
        val minScale = getMinScale()
        if (currentScale < minScale) {
            val scale = minScale / currentScale
            imageMatrix.postScale(scale, scale, cropRect.centerX(), cropRect.centerY())
        }
        fixTranslateBounds()
    }

    private fun fixTranslateBounds() {
        val (dx, dy) = calculateBoundsOffset()
        if (dx != 0f || dy != 0f) {
            imageMatrix.postTranslate(dx, dy)
            calculateImageRect()
        }
    }

    private fun animateCheckBounds() {
        if (sourceBitmap == null) return
        calculateImageRect()
        if (getCurrentScale() < getMinScale()) {
            animateFitToCrop()
            return
        }
        val (dx, dy) = calculateBoundsOffset()
        if (dx != 0f || dy != 0f) animateBounce(dx, dy) else postInvalidate()
    }

    // ==================== 触摸事件 ====================

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (sourceBitmap == null) return false
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
        isShowingGuideLines = true

        activeEdge = detectEdgeTouch(event.x, event.y)
        isDraggingEdge = activeEdge != EdgeType.NONE
        if (isDraggingEdge) {
            tempCropRect.set(cropRect)
            originalCropRect.set(cropRect)
        }

        edgeTouchPoint.set(event.x, event.y)
        savedMatrix.set(imageMatrix)
    }

    private fun handleActionPointerDown(event: MotionEvent) {
        isDraggingEdge = false
        isShowingGuideLines = true
        if (event.pointerCount == 2) {
            isScaling = true
            lastSpan = calculateSpan(event)
            updateLockedFocus(event)
        }
    }

    private fun handleActionMove(event: MotionEvent) {
        when {
            isScaling && event.pointerCount >= 2 -> handleCustomScale(event)
            isDraggingEdge -> handleEdgeDrag(event)
            else -> handleImageDrag(event)
        }
    }

    private fun handleCustomScale(event: MotionEvent) {
        val currentSpan = calculateSpan(event)
        if (currentSpan < MIN_POINTER_SPAN || lastSpan < MIN_POINTER_SPAN) {
            lastSpan = currentSpan
            return
        }
        val scaleFactor = currentSpan / lastSpan
        lastSpan = currentSpan
        updateLockedFocus(event)

        imageMatrix.postScale(scaleFactor, scaleFactor, lockedFocus.x, lockedFocus.y)
        calculateImageRect()
        postInvalidateOnAnimation()
    }

    private fun calculateSpan(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    private fun updateLockedFocus(event: MotionEvent) {
        if (event.pointerCount >= 2) {
            lockedFocus.set(
                (event.getX(0) + event.getX(1)) / 2f,
                (event.getY(0) + event.getY(1)) / 2f
            )
        }
    }

    private fun handleEdgeDrag(event: MotionEvent) {
        val dx = event.x - edgeTouchPoint.x
        val dy = event.y - edgeTouchPoint.y
        tempCropRect.set(originalCropRect)

        // 应用拖动
        if (activeEdge.movesLeft) tempCropRect.left += dx
        if (activeEdge.movesRight) tempCropRect.right += dx
        if (activeEdge.movesTop) tempCropRect.top += dy
        if (activeEdge.movesBottom) tempCropRect.bottom += dy

        // 约束
        if (currentRatio > 0) constrainRatio(dx, dy) else constrainMinSize()

        cropRect.set(tempCropRect)
        postInvalidateOnAnimation()
    }

    /**
     * 固定比例约束：根据主方向计算等比尺寸，锚定对边/对角
     */
    private fun constrainRatio(dx: Float, dy: Float) {
        val edge = activeEdge
        val isHorizontal = edge.movesLeft || edge.movesRight
        val isVertical = edge.movesTop || edge.movesBottom

        // 确定主方向（角调整时根据拖动幅度判断）
        val dominantH = isHorizontal && (!isVertical || abs(dx) > abs(dy))

        var newWidth: Float
        var newHeight: Float
        if (dominantH) {
            newWidth = tempCropRect.width()
            newHeight = newWidth / currentRatio
        } else {
            newHeight = tempCropRect.height()
            newWidth = newHeight * currentRatio
        }

        // 最小尺寸检查
        val (minWidth, minHeight) = getMinSizeForRatio()
        if (newWidth < minWidth || newHeight < minHeight) {
            newWidth = minWidth.coerceAtLeast(minHeight * currentRatio)
            newHeight = newWidth / currentRatio
        }

        // 垂直方向：移动的边锚定对边，未移动的边锚定中心
        if (edge.movesTop && !edge.movesBottom) {
            tempCropRect.top = tempCropRect.bottom - newHeight
        } else if (edge.movesBottom && !edge.movesTop) {
            tempCropRect.bottom = tempCropRect.top + newHeight
        } else {
            val cy = tempCropRect.centerY()
            tempCropRect.top = cy - newHeight / 2
            tempCropRect.bottom = cy + newHeight / 2
        }

        // 水平方向：同理
        if (edge.movesLeft && !edge.movesRight) {
            tempCropRect.left = tempCropRect.right - newWidth
        } else if (edge.movesRight && !edge.movesLeft) {
            tempCropRect.right = tempCropRect.left + newWidth
        } else {
            val cx = tempCropRect.centerX()
            tempCropRect.left = cx - newWidth / 2
            tempCropRect.right = cx + newWidth / 2
        }
    }

    /**
     * 自由比例约束：确保最小尺寸
     */
    private fun constrainMinSize() {
        val edge = activeEdge
        if (edge.movesLeft && tempCropRect.width() < MIN_CROP_SIZE) {
            tempCropRect.left = tempCropRect.right - MIN_CROP_SIZE
        }
        if (edge.movesRight && tempCropRect.width() < MIN_CROP_SIZE) {
            tempCropRect.right = tempCropRect.left + MIN_CROP_SIZE
        }
        if (edge.movesTop && tempCropRect.height() < MIN_CROP_SIZE) {
            tempCropRect.top = tempCropRect.bottom - MIN_CROP_SIZE
        }
        if (edge.movesBottom && tempCropRect.height() < MIN_CROP_SIZE) {
            tempCropRect.bottom = tempCropRect.top + MIN_CROP_SIZE
        }
    }

    private fun getMinSizeForRatio(): Pair<Float, Float> {
        return if (currentRatio > 0) {
            if (currentRatio >= 1f) MIN_CROP_SIZE to MIN_CROP_SIZE * currentRatio
            else MIN_CROP_SIZE / currentRatio to MIN_CROP_SIZE
        } else {
            MIN_CROP_SIZE to MIN_CROP_SIZE
        }
    }

    private fun handleImageDrag(event: MotionEvent) {
        val pointerIndex = event.findPointerIndex(activePointerId)
        if (pointerIndex < 0) return
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        imageMatrix.postTranslate(x - lastTouch.x, y - lastTouch.y)
        lastTouch.set(x, y)
        calculateImageRect()
        postInvalidateOnAnimation()
    }

    private fun handleActionPointerUp(event: MotionEvent) {
        val pointerIndex = event.actionIndex
        val pointerId = event.getPointerId(pointerIndex)

        if (event.pointerCount <= 2) {
            isScaling = false
            lastSpan = 0f
        }
        isShowingGuideLines = false

        val otherIndex = if (pointerIndex == 0) 1 else 0
        if (event.pointerCount > otherIndex) {
            if (pointerId == activePointerId) {
                activePointerId = event.getPointerId(otherIndex)
            }
            lastTouch.set(event.getX(otherIndex), event.getY(otherIndex))
        }
    }

    private fun handleActionUp() {
        isScaling = false
        lastSpan = 0f
        isShowingGuideLines = false
        if (isDraggingEdge) animateRestoreCropRect() else animateCheckBounds()
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
        val t = EDGE_TOUCH_THRESHOLD
        val nearTop = abs(y - cropRect.top) < t
        val nearBottom = abs(y - cropRect.bottom) < t
        val nearLeft = abs(x - cropRect.left) < t
        val nearRight = abs(x - cropRect.right) < t
        val inH = x >= cropRect.left - t && x <= cropRect.right + t
        val inV = y >= cropRect.top - t && y <= cropRect.bottom + t
        val inCropH = x >= cropRect.left && x <= cropRect.right
        val inCropV = y >= cropRect.top && y <= cropRect.bottom

        return when {
            nearTop && nearLeft && inH && inV -> EdgeType.TOP_LEFT
            nearTop && nearRight && inH && inV -> EdgeType.TOP_RIGHT
            nearBottom && nearLeft && inH && inV -> EdgeType.BOTTOM_LEFT
            nearBottom && nearRight && inH && inV -> EdgeType.BOTTOM_RIGHT
            nearTop && inH && inCropH -> EdgeType.TOP
            nearBottom && inH && inCropH -> EdgeType.BOTTOM
            nearLeft && inV && inCropV -> EdgeType.LEFT
            nearRight && inV && inCropV -> EdgeType.RIGHT
            else -> EdgeType.NONE
        }
    }

    // ==================== 绘制 ====================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = sourceBitmap ?: return

        canvas.withMatrix(imageMatrix) {
            drawBitmap(bitmap, 0f, 0f, null)
        }

        drawOverlay(canvas)
        drawCropBox(canvas)
        if (isShowingGuideLines) drawGuideLines(canvas)
    }

    private fun drawOverlay(canvas: Canvas) {
        clipPath.reset()
        clipPath.addRect(cropRect, Path.Direction.CW)
        canvas.withSave {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                clipOutPath(clipPath)
            } else {
                clipPath(clipPath, Region.Op.DIFFERENCE)
            }
            drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
        }
    }

    private fun drawCropBox(canvas: Canvas) {
        canvas.drawRect(cropRect, cropBoxPaint)

        val len = CORNER_LINE_LENGTH
        val l = cropRect.left; val t = cropRect.top
        val r = cropRect.right; val b = cropRect.bottom

        // 四角标记：(起点x, 起点y, 水平偏移, 垂直偏移) × 2条线
        val corners = arrayOf(
            floatArrayOf(l, t, len, 0f, 0f, len),   // 左上
            floatArrayOf(r, t, -len, 0f, 0f, len),  // 右上
            floatArrayOf(l, b, len, 0f, 0f, -len),   // 左下
            floatArrayOf(r, b, -len, 0f, 0f, -len),  // 右下
        )
        for (c in corners) {
            canvas.drawLine(c[0], c[1], c[0] + c[2], c[1] + c[3], cornerPaint)
            canvas.drawLine(c[0], c[1], c[0] + c[4], c[1] + c[5], cornerPaint)
        }
    }

    private fun drawGuideLines(canvas: Canvas) {
        val tw = cropRect.width() / 3f
        val th = cropRect.height() / 3f
        for (i in 1..2) {
            val x = cropRect.left + tw * i
            canvas.drawLine(x, cropRect.top, x, cropRect.bottom, guideLinePaint)
        }
        for (i in 1..2) {
            val y = cropRect.top + th * i
            canvas.drawLine(cropRect.left, y, cropRect.right, y, guideLinePaint)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            calculateCropRect()
            if (sourceBitmap != null) fitImageToCrop()
        }
    }
}
