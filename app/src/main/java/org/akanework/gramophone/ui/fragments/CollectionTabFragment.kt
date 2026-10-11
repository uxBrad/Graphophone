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

package org.akanework.gramophone.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.collections.collectionsRepository
import org.akanework.gramophone.logic.enableEdgeToEdgePaddingListener
import org.akanework.gramophone.logic.ui.MyRecyclerView
import org.akanework.gramophone.ui.MainActivity
import org.akanework.gramophone.ui.adapters.AlbumAdapter
import org.akanework.gramophone.ui.adapters.ArtistAdapter
import org.akanework.gramophone.ui.adapters.DateAdapter
import org.akanework.gramophone.ui.adapters.GenreAdapter
import org.akanework.gramophone.ui.adapters.SongAdapter

/**
 * One tab (songs, albums, artists, ...) of a collection page, showing only what is inside
 * that collection. Hosted by [CollectionSubFragment].
 */
class CollectionTabFragment : Fragment() {
    companion object {
        const val ARG_TAB = "Tab"
        const val ARG_COLLECTION = "CollectionId"
    }

    private var adapter: AdapterFragment.BaseInterface<*>? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val rootView = inflater.inflate(R.layout.fragment_recyclerview, container, false)
        val recyclerView = rootView.findViewById<MyRecyclerView>(R.id.recyclerview)
        val host = requireParentFragment() as CollectionSubFragment
        recyclerView.setRecycledViewPool(host.recycledViewPool)
        recyclerView.enableEdgeToEdgePaddingListener()

        val id = requireArguments().getLong(ARG_COLLECTION)
        val activity = requireActivity() as MainActivity
        val collection = requireContext().collectionsRepository
            .resolved(activity.reader.albumListFlow)
            .map { list -> list.find { it.id == id } }
        val unknown = requireContext().getString(R.string.unknown_collection)
        val title = collection.map { it?.title ?: unknown }
        val songIds: Flow<Set<String>> = collection.map { c ->
            c?.songList.orEmpty().mapTo(HashSet()) { it.mediaId }
        }

        val created: AdapterFragment.BaseInterface<*> = when (requireArguments().getInt(ARG_TAB)) {
            R.id.songs -> SongAdapter(
                this, title,
                songList = collection.map { it?.songList },
                isSubFragment = R.id.collection,
                allowDiffUtils = true,
            )
            R.id.artists -> ArtistAdapter(this, scopeSongIds = songIds)
            R.id.genres -> GenreAdapter(this, songIds)
            R.id.dates -> DateAdapter(this, songIds)
            else -> AlbumAdapter(
                this, title, collection.map { it?.albums },
                isSubFragment = R.id.collection,
                collectionId = id,
            )
        }
        adapter = created
        recyclerView.adapter = created.concatAdapter
        recyclerView.setAppBar(host.appBarLayout)
        recyclerView.fastScroll(created, created.itemHeightHelper)
        return rootView
    }

    fun onTabReselected() {
        adapter?.onTabReselected()
    }

    override fun onDestroyView() {
        adapter = null
        super.onDestroyView()
    }
}
