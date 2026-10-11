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
import androidx.core.util.AtomicFile
import androidx.media3.common.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import uk.akane.libphonograph.items.Album
import java.io.File
import java.util.Locale

/**
 * Stores user-made album collections ("Old School Metal", "Slow Jazz", ...) in a small JSON
 * file in app storage. MediaStore has no concept of collections, so they live here instead.
 */
class CollectionsRepository private constructor(context: Context) {

    private val appContext = context.applicationContext

    // lazy: looking up filesDir touches the disk, which must not happen on the main thread
    private val file by lazy { AtomicFile(File(appContext.filesDir, FILE_NAME)) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val state = MutableStateFlow<List<CollectionData>?>(null)

    /** All saved collections, in creation order. Emits once the file has been read. */
    val collections: Flow<List<CollectionData>> = state.filterNotNull()

    init {
        scope.launch {
            mutex.withLock {
                if (state.value == null) state.value = readFromDisk()
            }
        }
    }

    /** Collections with their albums looked up in the current library. */
    fun resolved(library: Flow<List<Album>>): Flow<List<MusicCollection>> =
        combine(collections, library) { saved, albums ->
            val byKey = HashMap<String, Album>(albums.size)
            albums.forEach { byKey.putIfAbsent(AlbumKey.of(it).normalized, it) }
            saved.map { data ->
                MusicCollection(data, data.albums.mapNotNull { byKey[it.normalized] })
            }
        }

    /** Snapshot of the current collections (null only before the first read finishes). */
    fun current(): List<CollectionData>? = state.value

    fun nameTaken(name: String, exceptId: Long? = null): Boolean {
        val wanted = name.trim().lowercase(Locale.ROOT)
        return state.value.orEmpty().any {
            it.id != exceptId && it.name.trim().lowercase(Locale.ROOT) == wanted
        }
    }

    suspend fun create(
        name: String,
        description: String,
        albums: List<AlbumKey> = emptyList()
    ): CollectionData {
        lateinit var created: CollectionData
        edit { list ->
            val now = System.currentTimeMillis()
            // ids only need to be unique, the timestamp also gives a creation order
            val id = maxOf(now, (list.maxOfOrNull { it.id } ?: 0L) + 1)
            created = CollectionData(id, name.trim(), description.trim(), albums, now)
            list + created
        }
        return created
    }

    suspend fun update(id: Long, name: String, description: String) = edit { list ->
        list.map { if (it.id == id) it.copy(name = name.trim(), description = description.trim()) else it }
    }

    /** Sets (or with null, clears) the picture used as this collection's art. */
    suspend fun setCustomCover(id: Long, path: String?) = edit { list ->
        list.map {
            if (it.id != id) it else {
                if (it.customCover != null && it.customCover != path) File(it.customCover).delete()
                it.copy(customCover = path)
            }
        }
    }

    suspend fun delete(id: Long) = edit { list ->
        list.find { it.id == id }?.customCover?.let { File(it).delete() }
        list.filterNot { it.id == id }
    }

    suspend fun addAlbum(id: Long, key: AlbumKey) = edit { list ->
        list.map { if (it.id == id && !it.contains(key)) it.copy(albums = it.albums + key) else it }
    }

    suspend fun removeAlbum(id: Long, key: AlbumKey) = edit { list ->
        list.map {
            if (it.id == id) it.copy(albums = it.albums.filterNot { a -> a.normalized == key.normalized })
            else it
        }
    }

    /** Makes [key] a member of exactly the collections in [ids], keeping existing order. */
    suspend fun setMembership(key: AlbumKey, ids: Set<Long>) = edit { list ->
        list.map {
            val has = it.contains(key)
            val wants = it.id in ids
            when {
                wants && !has -> it.copy(albums = it.albums + key)
                !wants && has -> it.copy(albums = it.albums.filterNot { a -> a.normalized == key.normalized })
                else -> it
            }
        }
    }

    suspend fun moveAlbum(id: Long, from: Int, to: Int) = edit { list ->
        list.map {
            if (it.id != id || from !in it.albums.indices || to !in it.albums.indices) it
            else it.copy(albums = it.albums.toMutableList().apply { add(to, removeAt(from)) })
        }
    }

    private suspend fun edit(block: (List<CollectionData>) -> List<CollectionData>) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val updated = block(state.value ?: readFromDisk())
                writeToDisk(updated)
                state.value = updated
            }
        }
    }

    private fun readFromDisk(): List<CollectionData> {
        if (!file.baseFile.exists()) return emptyList()
        return try {
            fromJson(String(file.readFully(), Charsets.UTF_8))
        } catch (e: Exception) {
            Log.e(TAG, "Could not read collections, starting empty", e)
            emptyList()
        }
    }

    private fun writeToDisk(list: List<CollectionData>) {
        val out = file.startWrite()
        try {
            out.write(toJson(list).toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    companion object {
        private const val TAG = "CollectionsRepository"
        private const val FILE_NAME = "collections.json"
        private const val FORMAT_VERSION = 1

        @Volatile
        private var instance: CollectionsRepository? = null

        fun get(context: Context): CollectionsRepository =
            instance ?: synchronized(this) {
                instance ?: CollectionsRepository(context).also { instance = it }
            }

        /** JSON shape is also what export/import will use, so keep it stable. */
        fun toJson(list: List<CollectionData>): String = JSONObject().apply {
            put("version", FORMAT_VERSION)
            put("collections", JSONArray().apply {
                list.forEach { c ->
                    put(JSONObject().apply {
                        put("id", c.id)
                        put("name", c.name)
                        put("description", c.description)
                        put("createdAt", c.createdAt)
                        c.customCover?.let { put("customCover", it) }
                        put("albums", JSONArray().apply {
                            c.albums.forEach { a ->
                                put(JSONObject().apply {
                                    put("title", a.title)
                                    put("artist", a.artist)
                                })
                            }
                        })
                    })
                }
            })
        }.toString()

        fun fromJson(json: String): List<CollectionData> {
            val root = JSONObject(json)
            val arr = root.optJSONArray("collections") ?: return emptyList()
            return (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                val albums = c.optJSONArray("albums") ?: JSONArray()
                CollectionData(
                    id = c.getLong("id"),
                    name = c.optString("name"),
                    description = c.optString("description"),
                    albums = (0 until albums.length()).map { j ->
                        val a = albums.getJSONObject(j)
                        AlbumKey(a.optString("title"), a.optString("artist"))
                    },
                    createdAt = c.optLong("createdAt", 0L),
                    customCover = if (c.has("customCover")) c.getString("customCover") else null,
                )
            }
        }
    }
}

val Context.collectionsRepository: CollectionsRepository
    get() = CollectionsRepository.get(this)
