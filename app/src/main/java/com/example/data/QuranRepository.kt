package com.example.data

import kotlinx.coroutines.flow.Flow

class QuranRepository(private val quranDao: QuranDao) {

    val completedSurahs: Flow<List<CompletedSurah>> = quranDao.getAllCompletedSurahs()
    val bookmarkedVerses: Flow<List<BookmarkedVerse>> = quranDao.getAllBookmarkedVerses()
    val themeSetting: Flow<ThemeSetting?> = quranDao.getThemeSetting()

    suspend fun toggleCompletedSurah(surahNumber: Int, isCompleted: Boolean) {
        if (isCompleted) {
            quranDao.insertCompletedSurah(CompletedSurah(surahNumber))
        } else {
            quranDao.deleteCompletedSurah(surahNumber)
        }
    }

    suspend fun toggleBookmarkedVerse(verse: Verse, isBookmarked: Boolean) {
        val key = verse.key
        if (isBookmarked) {
            quranDao.insertBookmarkedVerse(
                BookmarkedVerse(
                    verseKey = key,
                    surahNumber = verse.surahNumber,
                    verseNumber = verse.verseNumber,
                    arabicText = verse.arabicText,
                    englishTranslation = verse.englishTranslation,
                    surahNameEnglish = verse.surahNameEnglish
                )
            )
        } else {
            quranDao.deleteBookmarkedVerse(key)
        }
    }

    suspend fun toggleBookmarkedVerseDirect(verse: BookmarkedVerse, isBookmarked: Boolean) {
        if (isBookmarked) {
            quranDao.insertBookmarkedVerse(verse)
        } else {
            quranDao.deleteBookmarkedVerse(verse.verseKey)
        }
    }

    fun isVerseBookmarked(verseKey: String): Flow<Boolean> = quranDao.isVerseBookmarked(verseKey)

    suspend fun saveThemeSetting(isDarkMode: Boolean) {
        quranDao.insertThemeSetting(ThemeSetting(isDarkMode = isDarkMode))
    }
}
