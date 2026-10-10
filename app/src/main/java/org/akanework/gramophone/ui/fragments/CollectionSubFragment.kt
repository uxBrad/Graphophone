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
import android.widget.TextView
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.collections.collectionsRepository
import org.akanework.gramophone.logic.enableEdgeToEdgePaddingListener
import org.akanework.gramophone.logic.ui.MyRecyclerView
import org.akanework.gramophone.ui.CollectionDialogs
import org.akanework.gramophone.ui.adapters.AlbumAdapter

/**
 * One collection: its name and description up top, its albums in a grid below, with the
 * usual play all / shuffle buttons. Albums can be removed from their long-press menu.
 */
class CollectionSubFragment : BaseFragment(true) {
    companion object {
        const val ARG_ID = "CollectionId"
    }

    val collectionId: Long
        get() = requireArguments().getLong(ARG_ID)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val rootView = inflater.inflate(R.layout.fragment_collection_sub, container, false)
        val topAppBar = rootView.findViewById<MaterialToolbar>(R.id.topAppBar)
        val collapsingToolbar = rootView.findViewById<CollapsingToolbarLayout>(R.id.collapsingtoolbar)
        val description = rootView.findViewById<TextView>(R.id.collection_description)
        val appBarLayout = rootView.findViewById<AppBarLayout>(R.id.appbarlayout)
        val recyclerView = rootView.findViewById<MyRecyclerView>(R.id.recyclerview)
        appBarLayout.enableEdgeToEdgePaddingListener()

        val id = collectionId
        val repo = requireContext().collectionsRepository
        val item = repo.resolved(mainActivity.reader.albumListFlow)
            .map { list -> list.find { it.id == id } }
        val title = item.map { it?.title ?: requireContext().getString(R.string.unknown_collection) }

        val albumAdapter = AlbumAdapter(
            this, title, item.map { it?.albums },
            isSubFragment = R.id.collection,
            collectionId = id,
        )
        recyclerView.enableEdgeToEdgePaddingListener()
        recyclerView.setAppBar(appBarLayout)
        recyclerView.adapter = albumAdapter.concatAdapter
        recyclerView.fastScroll(albumAdapter, albumAdapter.itemHeightHelper)

        val defaultTitleMarginBottom = collapsingToolbar.expandedTitleMarginBottom
        lifecycleScope.launch(Dispatchers.Default) {
            item.collect { collection ->
                withContext(Dispatchers.Main) {
                    if (view == null) return@withContext
                    if (collection == null) {
                        // deleted from somewhere else while open
                        if (isAdded) requireActivity().supportFragmentManager.popBackStack()
                        return@withContext
                    }
                    collapsingToolbar.title = collection.title
                    description.text = collection.description
                    description.visibility =
                        if (collection.description.isBlank()) View.GONE else View.VISIBLE
                    // keep the big title sitting above the description text
                    description.doOnLayout {
                        collapsingToolbar.expandedTitleMarginBottom = defaultTitleMarginBottom +
                                if (description.visibility == View.VISIBLE) description.height else 0
                    }
                }
            }
        }

        topAppBar.inflateMenu(R.menu.collection_subfragment_menu)
        topAppBar.setOnMenuItemClickListener {
            val data = repo.current()?.find { c -> c.id == id } ?: return@setOnMenuItemClickListener false
            when (it.itemId) {
                R.id.edit_collection -> CollectionDialogs.showEditDialog(mainActivity, data)
                R.id.delete_collection -> CollectionDialogs.confirmDelete(mainActivity, data)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        topAppBar.setNavigationOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }

        return rootView
    }
}
