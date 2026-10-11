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

package org.akanework.gramophone.ui.adapters

import android.net.Uri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File
import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.iterator
import coil3.dispose
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.collections.MusicCollection
import org.akanework.gramophone.logic.collections.collectionsRepository
import org.akanework.gramophone.ui.CollectionDialogs
import org.akanework.gramophone.ui.MainActivity
import org.akanework.gramophone.ui.components.StackedCoversDrawable
import org.akanework.gramophone.ui.fragments.AdapterFragment
import org.akanework.gramophone.ui.fragments.CollectionSubFragment

/**
 * The Collections tab: user-made groups of albums, each shown with a stacked-albums thumbnail.
 */
class CollectionAdapter(
    fragment: AdapterFragment,
) : BaseAdapter<MusicCollection>(
    fragment,
    liveData = fragment.requireContext().collectionsRepository
        .resolved((fragment.requireActivity() as MainActivity).reader.albumListFlow),
    sortHelper = StoreCollectionHelper,
    naturalOrderHelper = null,
    initialSortType = Sorter.Type.ByAddDateAscending,
    pluralStr = R.plurals.collections,
    defaultLayoutType = LayoutType.GRID,
    allowDiffUtils = true,
) {

    init {
        lateInit()
    }

    override val showMenuInGrid: Boolean get() = true

    override fun virtualTitleOf(item: MusicCollection): String =
        context.getString(R.string.unknown_collection)

    // the stacked thumbnail is drawn in onBindViewHolder, so give coil nothing to load
    override fun coverOf(item: MusicCollection): Uri? = null

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val item = list!!.second[position]
        holder.songCover.dispose()
        (holder.songCover.drawable as? StackedCoversDrawable)?.cancel()
        val customCover = item.data.customCover?.let { File(it) }?.takeIf { it.exists() }
        holder.songCover.setImageDrawable(
            if (customCover != null) {
                StackedCoversDrawable(
                    context, listOf(Uri.fromFile(customCover)), R.drawable.ic_default_cover,
                    single = true
                )
            } else {
                StackedCoversDrawable(
                    context,
                    item.albums.map { it.cover },
                    R.drawable.ic_default_cover
                )
            }
        )
        holder.songCover.contentDescription = null // title is read out already
        // show the description under the name when there is one, album count otherwise
        val albumCount = context.resources.getQuantityString(
            R.plurals.albums, item.albums.size, item.albums.size
        )
        if (holder.trackCount != null) {
            // grid card has a separate third line for the count
            holder.subTitle.text = item.description
            holder.trackCount.text = albumCount
        } else {
            holder.subTitle.text = item.description.ifBlank { albumCount }
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        (holder.songCover.drawable as? StackedCoversDrawable)?.cancel()
        super.onViewRecycled(holder)
    }

    override fun onClick(item: MusicCollection, position: Int) {
        mainActivity.startFragment(CollectionSubFragment()) {
            putLong(CollectionSubFragment.ARG_ID, item.id)
        }
    }

    override fun onMenu(item: MusicCollection, popupMenu: PopupMenu) {
        popupMenu.inflate(R.menu.more_menu)
        popupMenu.menu.iterator().forEach {
            it.isVisible = it.itemId == R.id.play_next || it.itemId == R.id.add_to_queue
                    || it.itemId == R.id.rename || it.itemId == R.id.set_collection_art
                    || it.itemId == R.id.delete
                    || (it.itemId == R.id.remove_collection_art && item.data.customCover != null)
        }
        popupMenu.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.play_next -> {
                    val controller = mainActivity.getPlayer()
                    controller?.addMediaItems(controller.currentMediaItemIndex + 1, item.songList)
                }

                R.id.add_to_queue -> mainActivity.getPlayer()?.addMediaItems(item.songList)
                R.id.rename -> CollectionDialogs.showEditDialog(mainActivity, item.data)
                R.id.set_collection_art -> mainActivity.pickCollectionArt(item.id)
                R.id.remove_collection_art -> mainActivity.lifecycleScope.launch {
                    context.collectionsRepository.setCustomCover(item.id, null)
                }
                R.id.delete -> CollectionDialogs.confirmDelete(mainActivity, item.data)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
    }

    override fun createDecorAdapter(): BaseDecorAdapter<out BaseAdapter<MusicCollection>> =
        CollectionDecorAdapter(this)

    private inner class CollectionDecorAdapter(
        adapter: CollectionAdapter
    ) : BaseDecorAdapter<CollectionAdapter>(adapter, R.plurals.collections) {

        override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: List<Any?>) {
            super.onBindViewHolder(holder, position, payloads)
            val label = context.getString(R.string.create_collection)
            holder.createPlaylist.visibility = View.VISIBLE
            holder.createPlaylist.contentDescription = label
            TooltipCompat.setTooltipText(holder.createPlaylist, label)
            holder.createPlaylist.setOnClickListener {
                CollectionDialogs.showEditDialog(mainActivity)
            }
        }

        override fun onViewRecycled(holder: ViewHolder) {
            holder.createPlaylist.visibility = View.GONE
            holder.createPlaylist.setOnClickListener(null)
            super.onViewRecycled(holder)
        }
    }

    object StoreCollectionHelper : StoreItemHelper<MusicCollection>(
        setOf(
            Sorter.Type.ByTitleDescending, Sorter.Type.ByTitleAscending,
            Sorter.Type.BySizeDescending, Sorter.Type.BySizeAscending,
            Sorter.Type.ByAddDateDescending, Sorter.Type.ByAddDateAscending,
        )
    ) {
        override fun getAddDate(item: MusicCollection): Long = item.data.createdAt
    }
}
