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

package org.akanework.gramophone.ui

import android.content.DialogInterface
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.DialogCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.collections.AlbumKey
import org.akanework.gramophone.logic.collections.CollectionData
import org.akanework.gramophone.logic.collections.collectionsRepository
import uk.akane.libphonograph.items.Album
import uk.akane.libphonograph.items.albumId

object CollectionDialogs {

    /**
     * Create a new collection ([existing] == null) or edit the name and description of one.
     * [onSaved] runs on the main thread with the saved collection.
     */
    fun showEditDialog(
        activity: MainActivity,
        existing: CollectionData? = null,
        initialAlbums: List<AlbumKey> = emptyList(),
        onSaved: (CollectionData) -> Unit = {},
    ) {
        val repo = activity.collectionsRepository
        val d = MaterialAlertDialogBuilder(activity)
            .setTitle(if (existing == null) R.string.create_collection else R.string.edit_collection)
            .setView(R.layout.dialog_collection)
            .setPositiveButton(if (existing == null) R.string.collection_create_action else R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        val nameLayout = DialogCompat.requireViewById(d, R.id.nameLayout) as TextInputLayout
        val nameText = DialogCompat.requireViewById(d, R.id.nameText) as TextInputEditText
        val descText = DialogCompat.requireViewById(d, R.id.descriptionText) as TextInputEditText
        val save = d.getButton(DialogInterface.BUTTON_POSITIVE)
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        fun validate(): Boolean {
            val name = nameText.text?.toString().orEmpty()
            val error = when {
                name.isBlank() -> null // no error before typing, button is just disabled
                repo.nameTaken(name, existing?.id) ->
                    activity.getString(R.string.collection_name_taken)
                else -> null
            }
            nameLayout.error = error
            val ok = name.isNotBlank() && error == null
            save.isEnabled = ok
            return ok
        }

        nameText.setText(existing?.name.orEmpty())
        descText.setText(existing?.description.orEmpty())
        nameText.setSelection(nameText.text?.length ?: 0)
        nameText.addTextChangedListener(afterTextChanged = { validate() })
        validate()

        save.setOnClickListener {
            if (!validate()) return@setOnClickListener
            val name = nameText.text.toString()
            val description = descText.text?.toString().orEmpty()
            save.isEnabled = false
            activity.lifecycleScope.launch {
                val saved = if (existing == null) {
                    repo.create(name, description, initialAlbums)
                } else {
                    repo.update(existing.id, name, description)
                    existing.copy(name = name.trim(), description = description.trim())
                }
                d.dismiss()
                onSaved(saved)
            }
        }
        nameText.requestFocus()
    }

    /**
     * Shows every collection with a checkbox. Checked ones contain [album]; the person can tick
     * several at once, or make a new collection that starts with this album.
     */
    fun showAddAlbumDialog(activity: MainActivity, album: Album) {
        val repo = activity.collectionsRepository
        val key = AlbumKey.of(album)
        val albumTitle = album.title ?: activity.getString(R.string.unknown_album)
        activity.lifecycleScope.launch {
            val collections = repo.collections.first()
            if (collections.isEmpty()) {
                // nothing to pick from yet, go straight to creating the first one
                showEditDialog(activity, initialAlbums = listOf(key)) {
                    toast(activity, activity.getString(R.string.added_to_collection, albumTitle, it.name))
                }
                return@launch
            }
            val names = collections.map { it.name }.toTypedArray()
            val checked = collections.map { it.contains(key) }.toBooleanArray()
            val before = checked.copyOf()
            MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.add_album_to_collection_title, albumTitle))
                .setIcon(R.drawable.outline_library_add_24)
                .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setPositiveButton(R.string.collection_done) { _, _ ->
                    if (checked.contentEquals(before)) return@setPositiveButton
                    val ids = collections.filterIndexed { i, _ -> checked[i] }.map { it.id }.toSet()
                    val added = collections.filterIndexed { i, _ -> checked[i] && !before[i] }
                    activity.lifecycleScope.launch {
                        repo.setMembership(key, ids)
                        if (added.size == 1) {
                            toast(activity, activity.getString(
                                R.string.added_to_collection, albumTitle, added[0].name))
                        } else if (added.size > 1) {
                            toast(activity, activity.resources.getQuantityString(
                                R.plurals.added_to_collections, added.size, albumTitle, added.size))
                        }
                    }
                }
                .setNeutralButton(R.string.create_collection) { _, _ ->
                    showEditDialog(activity, initialAlbums = listOf(key)) {
                        toast(activity, activity.getString(R.string.added_to_collection, albumTitle, it.name))
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** "Add album to collection" from a song: finds the song's album in the library first. */
    fun showAddAlbumOfSongDialog(activity: MainActivity, song: MediaItem) {
        val albumId = song.mediaMetadata.albumId
        activity.lifecycleScope.launch {
            val album = albumId?.let { id ->
                activity.reader.albumListFlow.first().find { it.id == id }
            }
            if (album == null) {
                toast(activity, activity.getString(R.string.collection_album_not_found))
            } else {
                showAddAlbumDialog(activity, album)
            }
        }
    }

    fun confirmDelete(activity: MainActivity, collection: CollectionData, onDeleted: () -> Unit = {}) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.delete_collection)
            .setMessage(activity.getString(R.string.delete_collection_message, collection.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                activity.lifecycleScope.launch {
                    activity.collectionsRepository.delete(collection.id)
                    onDeleted()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(activity: MainActivity, text: String) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
    }
}
