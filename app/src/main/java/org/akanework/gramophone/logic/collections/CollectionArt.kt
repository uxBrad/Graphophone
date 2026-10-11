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

package org.akanework.gramophone.logic.collections

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

/**
 * Copies a picture chosen by the person into app storage as a square JPEG, so the collection
 * keeps its art even if the original file moves, and no storage permission is needed later.
 */
object CollectionArt {
    private const val TAG = "CollectionArt"
    private const val DIR = "collection_art"
    private const val SIZE = 800

    /** Returns the path of the saved copy, or null if the image could not be read. */
    suspend fun import(context: Context, collectionId: Long, uri: Uri): String? =
        withContext(Dispatchers.IO) {
            try {
                val resolver = context.contentResolver
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                var sample = 1
                while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE) sample *= 2
                val decoded = resolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(
                        it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                } ?: return@withContext null
                val side = min(decoded.width, decoded.height)
                val square = Bitmap.createBitmap(
                    decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
                val result = if (side > SIZE) Bitmap.createScaledBitmap(square, SIZE, SIZE, true) else square
                val dir = File(context.filesDir, DIR).apply { mkdirs() }
                // new name every time so image caches never show the previous picture
                val out = File(dir, "c${collectionId}_${System.currentTimeMillis()}.jpg")
                out.outputStream().use { result.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                out.absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "Could not import collection art", e)
                null
            }
        }
}
