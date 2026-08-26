/*
 * Copyright (c) 2024 Auxio Project
 * SongPickerViewModel.kt is part of Auxio.
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

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * A [ViewModel] managing the state of song-related dialogs.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@HiltViewModel
class SongPickerViewModel @Inject constructor(private val musicRepository: MusicRepository) :
    ViewModel(), MusicRepository.UpdateListener {
    private val _currentSongToDelete = MutableStateFlow<Song?>(null)
    /** The current [Song] that needs its deletion confirmed. Null if none yet. */
    val currentSongToDelete: StateFlow<Song?>
        get() = _currentSongToDelete

    init {
        musicRepository.addUpdateListener(this)
    }

    override fun onMusicChanges(changes: MusicRepository.Changes) {
        if (changes.deviceLibrary) {
            _currentSongToDelete.value =
                _currentSongToDelete.value?.let { song ->
                    musicRepository.library?.findSong(song.uid)
                }
            L.d("Updated song to delete to ${_currentSongToDelete.value}")
        }
    }

    override fun onCleared() {
        musicRepository.removeUpdateListener(this)
    }

    /**
     * Set a new [currentSongToDelete] from a [Song] [Music.UID].
     *
     * @param songUid The [Music.UID] of the [Song] to delete.
     */
    fun setSongToDelete(songUid: Music.UID) {
        L.d("Opening song $songUid to delete")
        _currentSongToDelete.value = musicRepository.library?.findSong(songUid)
        if (_currentSongToDelete.value == null) {
            L.w("Given song UID to delete was invalid")
        }
    }
}
