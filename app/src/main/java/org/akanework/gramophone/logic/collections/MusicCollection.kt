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

import androidx.media3.common.MediaItem
import uk.akane.libphonograph.items.Album
import uk.akane.libphonograph.items.Item
import java.util.Locale

/**
 * Identifies an album independently of MediaStore ids, which can change when the library
 * is rescanned. Matching is case- and whitespace-insensitive.
 */
data class AlbumKey(val title: String, val artist: String) {
    val normalized: String
        get() = title.trim().lowercase(Locale.ROOT) + "\u0000" + artist.trim().lowercase(Locale.ROOT)

    companion object {
        fun of(album: Album) = AlbumKey(album.title.orEmpty().trim(), album.albumArtist.orEmpty().trim())
    }
}

/** What gets saved to disk for one collection. */
data class CollectionData(
    val id: Long,
    val name: String,
    val description: String,
    val albums: List<AlbumKey>,
    val createdAt: Long,
) {
    fun contains(key: AlbumKey) = albums.any { it.normalized == key.normalized }
}

/**
 * A collection resolved against the current library. [albums] only holds albums that exist
 * on the device right now; albums that went missing stay saved in [data] and reappear if the
 * files come back.
 */
data class MusicCollection(
    val data: CollectionData,
    val albums: List<Album>,
) : Item {
    override val id: Long get() = data.id
    override val title: String get() = data.name
    val description: String get() = data.description
    override val songList: List<MediaItem> by lazy { albums.flatMap { it.songList } }
}
