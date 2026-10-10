/*
 *     Copyright (C) 2026 The Gramophone authors
 *
 *     Gramophone is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Gramophone is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.akanework.gramophone.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.target
import com.google.android.material.color.MaterialColors
import kotlin.math.min

/**
 * Draws up to three album covers as a stack, front cover at the bottom and the others peeking
 * out above it, smaller and slightly shaded. Used as the thumbnail for collections.
 * Missing covers (fewer than three albums, or albums without art) use [placeholderRes].
 */
class StackedCoversDrawable(
    context: Context,
    covers: List<Uri?>,
    @DrawableRes placeholderRes: Int,
) : Drawable() {

    private val placeholder = AppCompatResources.getDrawable(context, placeholderRes)!!.mutate()
    private val layers = arrayOfNulls<Drawable>(LAYERS)
    private val requests = mutableListOf<Disposable>()
    private val rect = RectF()
    private val layerBounds = Rect()
    private val clip = Path()
    private val density = context.resources.displayMetrics.density
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = MaterialColors.getColor(
            context, com.google.android.material.R.attr.colorSurface, Color.WHITE
        )
    }
    private var drawAlpha = 255

    init {
        val loader = context.imageLoader
        covers.take(LAYERS).forEachIndexed { i, uri ->
            if (uri == null) return@forEachIndexed
            requests += loader.enqueue(
                ImageRequest.Builder(context)
                    .data(uri)
                    .size(COVER_SIZE_PX)
                    .target(onSuccess = { image ->
                        layers[i] = image.asDrawable(context.resources).mutate()
                        invalidateSelf()
                    })
                    .build()
            )
        }
    }

    /** Stop loading covers, call when the view showing this is recycled. */
    fun cancel() {
        requests.forEach { it.dispose() }
        requests.clear()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val front = min(w, h) * FRONT_SIZE
        val step = h * STEP
        // draw back to front so the front cover ends up on top
        for (i in LAYERS - 1 downTo 0) {
            val size = front * (1f - SHRINK * i)
            val left = b.left + (w - size) / 2f
            val top = b.bottom - front - step * i
            rect.set(left, top, left + size, top + size)
            val radius = size * CORNER

            val save = canvas.save()
            clip.reset()
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
            canvas.clipPath(clip)
            rect.round(layerBounds)
            val d = layers[i] ?: placeholder
            d.bounds = layerBounds
            d.alpha = drawAlpha
            d.draw(canvas)
            if (i > 0) {
                shadePaint.alpha = (SHADE_PER_LAYER * i * drawAlpha / 255)
                canvas.drawRect(rect, shadePaint)
            }
            canvas.restoreToCount(save)
            edgePaint.alpha = drawAlpha
            canvas.drawRoundRect(rect, radius, radius, edgePaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        drawAlpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        placeholder.colorFilter = colorFilter
        layers.forEach { it?.colorFilter = colorFilter }
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val LAYERS = 3
        const val FRONT_SIZE = 0.84f // front cover takes this much of the square
        const val STEP = 0.075f // how far each back cover peeks out, relative to height
        const val SHRINK = 0.09f // how much smaller each back cover is
        const val CORNER = 0.06f
        const val SHADE_PER_LAYER = 45
        const val COVER_SIZE_PX = 384
    }
}
