package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface QuranDao {

    // Completed Surahs
    @Query("SELECT * FROM completed_surahs")
    fun getAllCompletedSurahs(): Flow<List<CompletedSurah>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCompletedSurah(completedSurah: CompletedSurah)

    @Query("DELETE FROM completed_surahs WHERE surahNumber = :surahNumber")
    suspend fun deleteCompletedSurah(surahNumber: Int)

    // Bookmarked Verses
    @Query("SELECT * FROM bookmarked_verses ORDER BY timestamp DESC")
    fun getAllBookmarkedVerses(): Flow<List<BookmarkedVerse>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmarkedVerse(verse: BookmarkedVerse)

    @Query("DELETE FROM bookmarked_verses WHERE verseKey = :verseKey")
    suspend fun deleteBookmarkedVerse(verseKey: String)

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarked_verses WHERE verseKey = :verseKey)")
    fun isVerseBookmarked(verseKey: String): Flow<Boolean>

    // Theme Settings
    @Query("SELECT * FROM theme_settings WHERE id = 1")
    fun getThemeSetting(): Flow<ThemeSetting?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertThemeSetting(themeSetting: ThemeSetting)
}
