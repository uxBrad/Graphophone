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
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.collections.collectionsRepository
import org.akanework.gramophone.logic.enableEdgeToEdgePaddingListener
import org.akanework.gramophone.logic.getStringStrict
import org.akanework.gramophone.ui.CollectionDialogs
import org.akanework.gramophone.ui.adapters.ViewPager2Adapter
import org.akanework.gramophone.ui.adapters.ViewPager2Adapter.Companion.Tab

/**
 * One collection: its name and description up top, then the same tabs as the home screen
 * (songs, albums, artists, ...) limited to what is in the collection. Albums can be removed
 * from their long-press menu.
 */
class CollectionSubFragment : BaseFragment(true) {
    companion object {
        const val ARG_ID = "CollectionId"

        // home screen tabs that make sense inside a collection
        private val SUPPORTED = setOf(Tab.Songs, Tab.Albums, Tab.Artists, Tab.Genres, Tab.Dates)
    }

    val collectionId: Long
        get() = requireArguments().getLong(ARG_ID)

    val recycledViewPool = RecyclerView.RecycledViewPool()
    lateinit var appBarLayout: AppBarLayout
        private set

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val rootView = inflater.inflate(R.layout.fragment_collection_sub, container, false)
        val topAppBar = rootView.findViewById<MaterialToolbar>(R.id.topAppBar)
        val collapsingToolbar = rootView.findViewById<CollapsingToolbarLayout>(R.id.collapsingtoolbar)
        val description = rootView.findViewById<TextView>(R.id.collection_description)
        appBarLayout = rootView.findViewById(R.id.appbarlayout)
        val tabLayout = rootView.findViewById<TabLayout>(R.id.tab_layout)
        val viewPager = rootView.findViewById<ViewPager2>(R.id.collection_viewpager)
        appBarLayout.enableEdgeToEdgePaddingListener()

        val id = collectionId
        val repo = requireContext().collectionsRepository
        val item = repo.resolved(mainActivity.reader.albumListFlow)
            .map { list -> list.find { it.id == id } }

        // same tab order and visibility as the home screen settings
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext().applicationContext)
        val all = ViewPager2Adapter.mapSettingToTabList(prefs.getStringStrict("tabs", "")!!)
        val shown = all.subList(0, all.indexOf(null).coerceAtLeast(0))
            .filterNotNull().filter { it in SUPPORTED }
        // songs, albums and artists are always available here, even if hidden on the home screen
        val tabs = listOf(Tab.Songs).filter { it !in shown } + shown +
                listOf(Tab.Albums, Tab.Artists).filter { it !in shown }

        viewPager.offscreenPageLimit = tabs.size
        viewPager.adapter = object : FragmentStateAdapter(childFragmentManager, viewLifecycleOwner.lifecycle) {
            override fun getItemCount() = tabs.size
            override fun createFragment(position: Int): Fragment = CollectionTabFragment().apply {
                arguments = Bundle().apply {
                    putInt(CollectionTabFragment.ARG_TAB, tabs[position].id)
                    putLong(CollectionTabFragment.ARG_COLLECTION, id)
                }
            }
            override fun getItemId(position: Int) = tabs[position].id.toLong()
            override fun containsItem(itemId: Long) = tabs.any { it.id.toLong() == itemId }
        }
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = getString(tabs[position].label)
            tab.view.post {
                try {
                    val lp = tab.view.layoutParams as ViewGroup.MarginLayoutParams
                    lp.marginStart = if (position == 0)
                        resources.getDimension(R.dimen.tab_layout_content_padding).toInt() else 0
                    lp.marginEnd = if (position == tabLayout.tabCount - 1)
                        resources.getDimension(R.dimen.tab_layout_content_padding).toInt() else 0
                    tab.view.layoutParams = lp
                } catch (_: IllegalStateException) {
                }
            }
        }.attach()
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(p0: TabLayout.Tab?) {}
            override fun onTabUnselected(p0: TabLayout.Tab?) {}
            override fun onTabReselected(p0: TabLayout.Tab?) {
                if (p0 != null)
                    (childFragmentManager.findFragmentByTag("f${tabs[p0.position].id}")
                            as CollectionTabFragment?)?.onTabReselected()
            }
        })
        if (tabs.size < 2) {
            tabLayout.visibility = View.GONE
            viewPager.isUserInputEnabled = false
        }
        // a collection is about its albums, so open there when that tab is shown
        if (savedInstanceState == null) {
            val start = tabs.indexOf(Tab.Albums)
            if (start > 0) viewPager.setCurrentItem(start, false)
        }

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
                    topAppBar.menu.findItem(R.id.remove_collection_art)?.isVisible =
                        collection.data.customCover != null
                    // keep the big title sitting above the description text
                    description.doOnLayout {
                        collapsingToolbar.expandedTitleMarginBottom = defaultTitleMarginBottom +
                                if (description.visibility == View.VISIBLE) description.height else 0
                    }
                }
            }
        }

        topAppBar.inflateMenu(R.menu.collection_subfragment_menu)
        topAppBar.menu.findItem(R.id.remove_collection_art)?.isVisible = false
        topAppBar.setOnMenuItemClickListener {
            val data = repo.current()?.find { c -> c.id == id } ?: return@setOnMenuItemClickListener false
            when (it.itemId) {
                R.id.edit_collection -> CollectionDialogs.showEditDialog(mainActivity, data)
                R.id.set_collection_art -> mainActivity.pickCollectionArt(id)
                R.id.remove_collection_art -> mainActivity.lifecycleScope.launch {
                    repo.setCustomCover(id, null)
                }
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
