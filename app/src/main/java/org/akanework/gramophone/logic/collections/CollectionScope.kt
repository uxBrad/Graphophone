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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import uk.akane.libphonograph.items.Artist
import uk.akane.libphonograph.items.Date
import uk.akane.libphonograph.items.Genre

/**
 * Narrows library-wide artist/genre/date lists to what is inside one collection, so the
 * collection's tabs show only the songs that belong to it. [songIds] are media ids.
 */
fun Flow<List<Artist>>.scopedTo(songIds: Flow<Set<String>>): Flow<List<Artist>> =
    combine(this, songIds) { artists, ids ->
        artists.mapNotNull { a ->
            val songs = a.songList.filter { it.mediaId in ids }
            if (songs.isEmpty()) null else a.copy(
                songList = songs,
                albumList = a.albumList.filter { al -> al.songList.any { it.mediaId in ids } },
            )
        }
    }

@JvmName("genresScopedTo")
fun Flow<List<Genre>>.scopedTo(songIds: Flow<Set<String>>): Flow<List<Genre>> =
    combine(this, songIds) { genres, ids ->
        genres.mapNotNull { g ->
            val songs = g.songList.filter { it.mediaId in ids }
            if (songs.isEmpty()) null else g.copy(songList = songs)
        }
    }

@JvmName("datesScopedTo")
fun Flow<List<Date>>.scopedTo(songIds: Flow<Set<String>>): Flow<List<Date>> =
    combine(this, songIds) { dates, ids ->
        dates.mapNotNull { d ->
            val songs = d.songList.filter { it.mediaId in ids }
            if (songs.isEmpty()) null else d.copy(songList = songs)
        }
    }
