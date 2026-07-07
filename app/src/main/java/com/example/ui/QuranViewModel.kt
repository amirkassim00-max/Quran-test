package com.example.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.audio.PlaybackState
import com.example.audio.QuranAudioPlayer
import com.example.data.BookmarkedVerse
import com.example.data.QuranDatabase
import com.example.data.QuranRepository
import com.example.data.QuranStaticData
import com.example.data.Surah
import com.example.data.Verse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class QuranViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: QuranRepository

    init {
        val database = QuranDatabase.getDatabase(application)
        repository = QuranRepository(database.quranDao())
        QuranAudioPlayer.initTts(application)
    }

    // Tab Selection
    private val _currentTab = MutableStateFlow("chapters")
    val currentTab: StateFlow<String> = _currentTab.asStateFlow()

    // Search Query
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Selected Surah for Reading
    private val _selectedSurah = MutableStateFlow<Surah?>(null)
    val selectedSurah: StateFlow<Surah?> = _selectedSurah.asStateFlow()

    // Offline Playback toggle (User preference)
    private val _offlineMode = MutableStateFlow(true)
    val offlineMode: StateFlow<Boolean> = _offlineMode.asStateFlow()

    // Completed Surah IDs
    val completedSurahIds: StateFlow<Set<Int>> = repository.completedSurahs
        .map { list -> list.map { it.surahNumber }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    // Bookmarked Verses
    val bookmarkedVerses: StateFlow<List<BookmarkedVerse>> = repository.bookmarkedVerses
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Theme Setting
    val isDarkMode: StateFlow<Boolean> = repository.themeSetting
        .map { it?.isDarkMode ?: true } // Dark theme by default to reduce eye strain
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // Playback State
    val playbackState: StateFlow<PlaybackState> = QuranAudioPlayer.state

    // Search Results (filtering static key verses)
    val searchResults: StateFlow<List<Verse>> = _searchQuery
        .combine(MutableStateFlow(QuranStaticData.keyVerses)) { query, verses ->
            if (query.isBlank()) {
                verses
            } else {
                verses.filter {
                    it.englishTranslation.contains(query, ignoreCase = true) ||
                    it.surahNameEnglish.contains(query, ignoreCase = true) ||
                    it.verseNumber.toString() == query
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun selectTab(tab: String) {
        _currentTab.value = tab
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun selectSurah(surah: Surah?) {
        _selectedSurah.value = surah
    }

    fun toggleOfflineMode() {
        _offlineMode.value = !_offlineMode.value
    }

    fun toggleTheme() {
        viewModelScope.launch {
            repository.saveThemeSetting(!isDarkMode.value)
        }
    }

    // Toggle Surah Completion
    fun toggleSurahCompletion(surahNumber: Int) {
        viewModelScope.launch {
            val isCompleted = completedSurahIds.value.contains(surahNumber)
            repository.toggleCompletedSurah(surahNumber, !isCompleted)
        }
    }

    // Toggle Verse Bookmark
    fun toggleVerseBookmark(verse: Verse) {
        viewModelScope.launch {
            val isBookmarked = bookmarkedVerses.value.any { it.verseKey == verse.key }
            repository.toggleBookmarkedVerse(verse, !isBookmarked)
        }
    }

    // Direct Toggle for Room BookmarkedVerse
    fun toggleBookmarkedVerseDirect(verse: BookmarkedVerse) {
        viewModelScope.launch {
            repository.toggleBookmarkedVerseDirect(verse, false) // Always delete from bookmarks view
        }
    }

    // Playback control
    fun playSurah(surah: Surah) {
        QuranAudioPlayer.playSurah(getApplication(), surah, _offlineMode.value)
    }

    fun pausePlayback() {
        QuranAudioPlayer.pause(getApplication())
    }

    fun resumePlayback() {
        QuranAudioPlayer.resume(getApplication())
    }

    fun stopPlayback() {
        QuranAudioPlayer.stop(getApplication())
    }

    fun seekPlayback(percent: Float) {
        QuranAudioPlayer.seekTo(getApplication(), percent)
    }

    // Get verses for a specific Surah
    fun getVersesForSurah(surahNumber: Int): List<Verse> {
        return QuranStaticData.keyVerses.filter { it.surahNumber == surahNumber }
    }

    override fun onCleared() {
        super.onCleared()
        // We do not release QuranAudioPlayer here as it runs in application scope/service
    }

    // ViewModel Factory
    class Factory(private val application: Application) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(QuranViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return QuranViewModel(application) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
