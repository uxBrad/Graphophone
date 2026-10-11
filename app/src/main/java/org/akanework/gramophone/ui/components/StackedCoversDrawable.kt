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
import kotlin.math.abs
import kotlin.math.min

/**
 * Thumbnail for a collection. Shows the first album big in the middle with more albums fanned
 * out behind it on both sides: five albums when the collection has at least five, otherwise
 * three. With [single] set it draws one picture filling the whole thumbnail instead, used when
 * the person picked their own collection art.
 */
class StackedCoversDrawable(
    context: Context,
    covers: List<Uri?>,
    @DrawableRes placeholderRes: Int,
    private val single: Boolean = false,
) : Drawable() {

    // horizontal position of each cover, 0 is the big middle one. Cover i goes in slot i.
    private val offsets: IntArray = when {
        single -> intArrayOf(0)
        covers.size >= 5 -> intArrayOf(0, -1, 1, -2, 2)
        else -> intArrayOf(0, -1, 1)
    }
    private val used = if (single) 1 else min(offsets.size, maxOf(covers.size, 1))
    private val placeholder = AppCompatResources.getDrawable(context, placeholderRes)!!.mutate()
    private val layers = arrayOfNulls<Drawable>(offsets.size)
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
        covers.take(used).forEachIndexed { i, uri ->
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
        val outer = canvas.save()
        canvas.clipRect(b)
        if (single) {
            layerBounds.set(b)
            val d = layers[0] ?: placeholder
            d.bounds = layerBounds
            d.alpha = drawAlpha
            d.draw(canvas)
            canvas.restoreToCount(outer)
            return
        }
        // draw the outermost first so the middle cover ends up on top
        val order = (0 until used).sortedByDescending { abs(offsets[it]) }
        for (i in order) {
            val off = offsets[i]
            val rank = abs(off)
            val size = w * (if (offsets.size > 3) SIZES5 else SIZES3)[rank]
            val cx = b.left + w / 2f + (if (off < 0) -1f else 1f) * w * (if (offsets.size > 3) DX5 else DX3)[rank]
            val left = cx - size / 2f
            val top = b.top + (h - size) / 2f
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
            if (rank > 0) {
                shadePaint.alpha = SHADE[rank] * drawAlpha / 255
                canvas.drawRect(rect, shadePaint)
            }
            canvas.restoreToCount(save)
            edgePaint.alpha = drawAlpha
            canvas.drawRoundRect(rect, radius, radius, edgePaint)
        }
        canvas.restoreToCount(outer)
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
        // indexed by distance from the middle (0, 1, 2)
        // three covers get more room each than five
        val SIZES3 = floatArrayOf(0.64f, 0.46f, 0f) // cover size relative to the width
        val DX3 = floatArrayOf(0f, 0.36f, 0f) // how far from the center, relative to width
        val SIZES5 = floatArrayOf(0.46f, 0.34f, 0.26f)
        val DX5 = floatArrayOf(0f, 0.22f, 0.385f)
        val SHADE = intArrayOf(0, 85, 140) // darkness of covers further away
        const val CORNER = 0.07f
        const val COVER_SIZE_PX = 384
    }
}
