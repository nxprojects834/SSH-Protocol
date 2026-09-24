package com.mdevz.sp.ui.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors

class TrafficSparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(
    context,
    attrs,
    defStyleAttr
) {
    private val samples =
        ArrayDeque<Long>()

    private val linePaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            style =
                Paint.Style.STROKE

            strokeWidth =
                resources
                    .displayMetrics
                    .density * 2f

            strokeCap =
                Paint.Cap.ROUND

            strokeJoin =
                Paint.Join.ROUND
        }

    private val path =
        Path()

    fun addSample(
        value: Long
    ) {
        if (
            samples.size >=
                MAX_SAMPLES
        ) {
            samples.removeFirst()
        }

        samples.addLast(
            value.coerceAtLeast(0L)
        )

        invalidate()
    }

    fun clearSamples() {
        if (samples.isEmpty()) {
            return
        }

        samples.clear()
        invalidate()
    }

    override fun onDraw(
        canvas: Canvas
    ) {
        super.onDraw(canvas)

        val values =
            samples.toList()

        if (values.size < 2) {
            return
        }

        val contentWidth =
            width -
                paddingLeft -
                paddingRight

        val contentHeight =
            height -
                paddingTop -
                paddingBottom

        if (
            contentWidth <= 0 ||
            contentHeight <= 0
        ) {
            return
        }

        val maximum =
            values
                .maxOrNull()
                ?.coerceAtLeast(1L)
                ?: 1L

        val step =
            contentWidth.toFloat() /
                (values.size - 1)

        linePaint.color =
            MaterialColors.getColor(
                this,
                android.R.attr.colorAccent
            )

        path.reset()

        values.forEachIndexed {
            index,
            value ->

            val x =
                paddingLeft +
                    step * index

            val ratio =
                value.toFloat() /
                    maximum.toFloat()

            val y =
                paddingTop +
                    contentHeight *
                    (1f - ratio)

            if (index == 0) {
                path.moveTo(
                    x,
                    y
                )
            } else {
                path.lineTo(
                    x,
                    y
                )
            }
        }

        canvas.drawPath(
            path,
            linePaint
        )
    }

    private companion object {
        const val MAX_SAMPLES =
            24
    }
}
