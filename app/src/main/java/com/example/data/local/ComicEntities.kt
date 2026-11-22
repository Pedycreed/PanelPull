package com.example.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "series")
data class SeriesEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val slug: String,
    val sourceHost: String,
    val comicFormat: String,
    val coverPathOrUrl: String = "",
    val folderPath: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = SeriesEntity::class,
            parentColumns = ["id"],
            childColumns = ["seriesId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("seriesId"), Index(value = ["sourceUrl"], unique = true)]
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val seriesId: Long,
    val chapterNumber: String,
    val chapterLabel: String,
    val sourceUrl: String,
    val folderPath: String,
    val pageCount: Int,
    val downloadedCount: Int = 0,
    val status: String = "EXTRACTED",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("chapterId")]
)
data class PageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chapterId: Long,
    val pageIndex: Int,
    val imageUrl: String,
    val refererUrl: String,
    val localFilePath: String? = null,
    val mimeType: String = "image/jpeg",
    val width: Int = 0,
    val height: Int = 0,
    val fileSizeBytes: Long = 0L,
    val isDownloaded: Boolean = false,
    val isCopied: Boolean = false
)

data class ChapterWithPages(
    @Embedded val chapter: ChapterEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "chapterId"
    )
    val pages: List<PageEntity>
)

data class SeriesWithChapters(
    @Embedded val series: SeriesEntity,
    @Relation(
        entity = ChapterEntity::class,
        parentColumn = "id",
        entityColumn = "seriesId"
    )
    val chapters: List<ChapterWithPages>
)
