package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "completed_surahs")
data class CompletedSurah(
    @PrimaryKey val surahNumber: Int,
    val completedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "bookmarked_verses")
data class BookmarkedVerse(
    @PrimaryKey val verseKey: String, // format "surahNumber:verseNumber"
    val surahNumber: Int,
    val verseNumber: Int,
    val arabicText: String,
    val englishTranslation: String,
    val surahNameEnglish: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "theme_settings")
data class ThemeSetting(
    @PrimaryKey val id: Int = 1,
    val isDarkMode: Boolean
)
