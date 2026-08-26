/*
 * Copyright (c) 2024 Auxio Project
 * DeleteSongDialog.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.oxycblt.auxio.music.decision

import android.app.Activity
import android.app.RecoverableSecurityException
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.LayoutInflater
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.io.FileNotFoundException
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogDeleteSongBinding
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.ui.ViewBindingMaterialDialogFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.showToast
import org.oxycblt.auxio.util.unlikelyToBeNull
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * A [ViewBindingMaterialDialogFragment] that asks the user to confirm the deletion of a [Song].
 *
 * Deletes the actual audio file from storage and updates the library and playback state.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class DeleteSongDialog : ViewBindingMaterialDialogFragment<DialogDeleteSongBinding>() {
    private val pickerModel: SongPickerViewModel by viewModels()
    private val musicModel: MusicViewModel by activityViewModels()
    @Inject lateinit var playbackManager: PlaybackStateManager
    private val args: DeleteSongDialogArgs by navArgs()

    private val intentSenderLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val song = pickerModel.currentSongToDelete.value
            if (result.resultCode == Activity.RESULT_OK && song != null) {
                L.d("Scoped storage deletion request confirmed by user for $song")
                onDeletionSuccessful(song)
            } else {
                L.w("Scoped storage deletion was cancelled or denied")
                requireContext().showToast(R.string.err_delete_permission_denied)
                findNavController().navigateUp()
            }
        }

    override fun onConfigDialog(builder: AlertDialog.Builder) {
        builder
            .setTitle(R.string.lbl_confirm_delete_song)
            .setPositiveButton(R.string.lbl_delete) { _, _ ->
                val song = pickerModel.currentSongToDelete.value
                if (song != null) {
                    performDelete(song)
                } else {
                    findNavController().navigateUp()
                }
            }
            .setNegativeButton(R.string.lbl_cancel, null)
    }

    override fun onCreateBinding(inflater: LayoutInflater) =
        DialogDeleteSongBinding.inflate(inflater)

    override fun onBindingCreated(binding: DialogDeleteSongBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        // --- VIEWMODEL SETUP ---
        musicModel.songDecision.consume()
        pickerModel.setSongToDelete(args.songUid)
        collectImmediately(pickerModel.currentSongToDelete, ::updateSongToDelete)
    }

    private fun updateSongToDelete(song: Song?) {
        if (song == null) {
            L.d("No song to delete, navigating away")
            findNavController().navigateUp()
            return
        }

        requireBinding().deletionInfo.text =
            getString(R.string.fmt_delete_song_info, song.name.resolve(requireContext()))
    }

    private fun performDelete(song: Song) {
        val context = requireContext().applicationContext
        val uri = song.uri
        L.d("Attempting to delete song file with URI: $uri (scheme=${uri.scheme}, authority=${uri.authority})")

        // 1. SAF Tree / Document URI Deletion
        val isSafDocument =
            try {
                DocumentsContract.isDocumentUri(context, uri) ||
                    (uri.authority != null && uri.authority?.contains("documents") == true)
            } catch (e: Exception) {
                false
            }

        if (isSafDocument) {
            try {
                val deleted = DocumentsContract.deleteDocument(context.contentResolver, uri)
                if (deleted) {
                    L.d("DocumentsContract deleted document successfully: $uri")
                    onDeletionSuccessful(song)
                    return
                }
            } catch (e: FileNotFoundException) {
                L.d("File already deleted from SAF storage: $uri")
                onDeletionSuccessful(song)
                return
            } catch (e: Exception) {
                L.e(e, "DocumentsContract deletion threw an exception")
            }
        }

        // 2. MediaStore URI Deletion
        if (uri.authority == MediaStore.AUTHORITY) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val rows = context.contentResolver.delete(uri, null, null)
                    if (rows > 0) {
                        L.d("MediaStore deleted $rows row(s) directly")
                        onDeletionSuccessful(song)
                        return
                    } else {
                        // Request user confirmation via MediaStore.createDeleteRequest
                        val pendingIntent =
                            MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
                        intentSenderLauncher.launch(
                            IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                        )
                        return
                    }
                } catch (e: SecurityException) {
                    try {
                        val pendingIntent =
                            MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
                        intentSenderLauncher.launch(
                            IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                        )
                        return
                    } catch (ex: Exception) {
                        L.e(ex, "Failed to launch MediaStore.createDeleteRequest")
                    }
                } catch (e: Exception) {
                    L.e(e, "MediaStore delete failed on Android 11+")
                }
            } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                try {
                    val rows = context.contentResolver.delete(uri, null, null)
                    if (rows > 0) {
                        L.d("MediaStore deleted $rows row(s) on Android 10")
                        onDeletionSuccessful(song)
                        return
                    }
                } catch (e: RecoverableSecurityException) {
                    intentSenderLauncher.launch(
                        IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build()
                    )
                    return
                } catch (e: Exception) {
                    L.e(e, "MediaStore delete failed on Android 10")
                }
            } else {
                try {
                    val rows = context.contentResolver.delete(uri, null, null)
                    L.d("MediaStore delete returned $rows on Android < 10")
                } catch (e: Exception) {
                    L.e(e, "MediaStore delete failed on pre-Q")
                }
            }
        }

        // 3. Direct Filesystem Fallback
        deleteDirectFileFallback(song)
    }

    private fun deleteDirectFileFallback(song: Song) {
        try {
            val pathStr = song.uri.path
            val file = if (pathStr != null) File(pathStr) else null
            if (file != null && file.exists()) {
                if (file.delete()) {
                    L.d("Direct file delete succeeded: ${file.absolutePath}")
                    onDeletionSuccessful(song)
                    return
                }
            } else if (file != null && !file.exists()) {
                L.d("File no longer exists on disk: ${file.absolutePath}")
                onDeletionSuccessful(song)
                return
            }
        } catch (e: Exception) {
            L.e(e, "Direct file deletion failed")
        }

        requireContext().showToast(R.string.err_delete_song_failed)
        findNavController().navigateUp()
    }

    private fun onDeletionSuccessful(song: Song) {
        L.d("Song deletion successful for $song, cleaning queue and refreshing library")
        // Remove from playback queue if present
        val queue = playbackManager.queue
        for (i in queue.indices.reversed()) {
            if (queue[i].uid == song.uid) {
                L.d("Removing deleted song from playback queue at index $i")
                playbackManager.removeQueueItem(i)
            }
        }

        // Refresh library to clean up cache & graph
        musicModel.refresh()
        requireContext().showToast(R.string.lng_song_deleted)
        findNavController().navigateUp()
    }
}
