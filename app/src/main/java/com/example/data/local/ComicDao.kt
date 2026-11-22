package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ComicDao {
    @Transaction
    @Query("SELECT * FROM series ORDER BY updatedAt DESC")
    fun observeLibrary(): Flow<List<SeriesWithChapters>>

    @Transaction
    @Query("SELECT * FROM chapters WHERE id = :chapterId")
    fun observeChapterWithPages(chapterId: Long): Flow<ChapterWithPages?>

    @Query("SELECT * FROM series WHERE slug = :slug LIMIT 1")
    suspend fun getSeriesBySlug(slug: String): SeriesEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeries(series: SeriesEntity): Long

    @Update
    suspend fun updateSeries(series: SeriesEntity)

    @Query("DELETE FROM series WHERE id = :seriesId")
    suspend fun deleteSeriesById(seriesId: Long)

    @Query("SELECT * FROM chapters WHERE sourceUrl = :sourceUrl LIMIT 1")
    suspend fun getChapterBySourceUrl(sourceUrl: String): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE id = :chapterId LIMIT 1")
    suspend fun getChapterById(chapterId: Long): ChapterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapter(chapter: ChapterEntity): Long

    @Update
    suspend fun updateChapter(chapter: ChapterEntity)

    @Query("DELETE FROM chapters WHERE id = :chapterId")
    suspend fun deleteChapterById(chapterId: Long)

    @Query("DELETE FROM pages WHERE chapterId = :chapterId")
    suspend fun deletePagesForChapter(chapterId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPages(pages: List<PageEntity>)

    @Query("SELECT * FROM pages WHERE chapterId = :chapterId ORDER BY pageIndex ASC")
    suspend fun getPagesForChapter(chapterId: Long): List<PageEntity>

    @Query("UPDATE pages SET localFilePath = :path, mimeType = :mimeType, width = :width, height = :height, fileSizeBytes = :bytes, isDownloaded = :downloaded WHERE id = :pageId")
    suspend fun updatePageDownload(
        pageId: Long,
        path: String?,
        mimeType: String,
        width: Int,
        height: Int,
        bytes: Long,
        downloaded: Boolean
    )

    @Query("UPDATE pages SET isCopied = 1 WHERE id = :pageId")
    suspend fun markPageCopied(pageId: Long)

    @Query("UPDATE chapters SET downloadedCount = :downloadedCount, status = :status WHERE id = :chapterId")
    suspend fun updateChapterProgress(chapterId: Long, downloadedCount: Int, status: String)
}
