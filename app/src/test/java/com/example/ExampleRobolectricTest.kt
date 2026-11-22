package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.extractor.ComicExtractorEngine
import com.example.data.extractor.ComixSiteExtractor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("PanelPull", appName)
    }

    @Test
    fun `extractor filters logos and extracts manga pages and chapters`() {
        val html = """
            <html>
              <head><title>Solo Leveling - Capítulo 45 - Ler Manhwa</title></head>
              <body>
                <img class="site-logo" src="https://cdn.example.com/logo.png" alt="Logo" />
                <div class="reading-content">
                  <img data-src="https://cdn.example.com/sl/ch45/001.jpg" width="800" height="1200" />
                  <img data-lazy-src="/sl/ch45/002.webp" width="800" height="1200" />
                  <img class="discord-banner" src="https://cdn.example.com/discord_banner.png" />
                </div>
                <a href="https://example.com/solo-leveling/capitulo-46">Capítulo 46</a>
              </body>
            </html>
        """.trimIndent()

        val engine = ComicExtractorEngine()
        val result = engine.parseHtmlDocument("https://example.com/solo-leveling/capitulo-45", html)

        assertEquals("Solo Leveling", result.seriesTitle)
        assertEquals("45", result.chapterNumber)
        assertEquals(2, result.pages.size)
        assertEquals("https://cdn.example.com/sl/ch45/001.jpg", result.pages[0].imageUrl)
        assertEquals("https://example.com/sl/ch45/002.webp", result.pages[1].imageUrl)
        assertTrue(result.detectedChapters.any { it.chapterNumber == "46" })
    }

    @Test
    fun `comix url parser extracts hashId, readable title, chapterId and chapterNumber`() {
        val comix = ComixSiteExtractor(ComicExtractorEngine.defaultHttpClient())
        val url = "https://comix.to/title/vlxz-ive-become-the-devils-master/2922186-chapter-19"

        assertTrue(comix.canHandle(url))
        val parts = comix.parseComixUrl(url)
        assertNotNull(parts)
        assertEquals("vlxz", parts!!.hashId)
        assertEquals("vlxz-ive-become-the-devils-master", parts.seriesSlug)
        assertEquals("Ive Become The Devils Master", parts.readableTitle)
        assertEquals("2922186", parts.chapterId)
        assertEquals("19", parts.chapterNumber)
    }

    @Test
    fun `comix api v2 json parser extracts ordered chapter pages and chapter list`() {
        val comix = ComixSiteExtractor(ComicExtractorEngine.defaultHttpClient())
        val chapterUrl = "https://comix.to/title/vlxz-ive-become-the-devils-master/2922186-chapter-19"

        val chapterJson = JSONObject(
            """
            {
              "status": 200,
              "result": {
                "chapter_id": 2922186,
                "number": 19,
                "images": [
                  { "width": 800, "height": 2400, "url": "https://wowpic.comix.to/files/2922186/001.webp" },
                  { "width": 800, "height": 2600, "url": "https://wowpic.comix.to/files/2922186/002.avif" }
                ]
              }
            }
            """.trimIndent()
        )

        val (pages, chNum) = comix.parseChapterApiResponse(chapterJson, chapterUrl, "19")
        assertEquals("19", chNum)
        assertEquals(2, pages.size)
        assertEquals("https://wowpic.comix.to/files/2922186/001.webp", pages[0].imageUrl)
        assertEquals("https://wowpic.comix.to/files/2922186/002.avif", pages[1].imageUrl)
        assertEquals(800, pages[0].widthHint)

        val chaptersJson = JSONObject(
            """
            {
              "status": 200,
              "result": {
                "items": [
                  { "chapter_id": 2922185, "number": 18, "name": "Awakening" },
                  { "chapter_id": 2922186, "number": 19, "name": "" },
                  { "chapter_id": 2922190, "number": 20, "name": "Next Trial" }
                ]
              }
            }
            """.trimIndent()
        )
        val links = comix.parseChaptersListApiResponse(
            json = chaptersJson,
            baseOrigin = "https://comix.to",
            seriesSlug = "vlxz-ive-become-the-devils-master",
            currentSourceUrl = chapterUrl
        )
        assertEquals(2, links.size)
        assertEquals("18", links[0].chapterNumber)
        assertEquals("https://comix.to/title/vlxz-ive-become-the-devils-master/2922190-chapter-20", links[1].url)
    }

    @Test
    fun `extractor parses next_f escaped json payloads even when sidebar thumbnails exist`() {
        val html = """
            <html>
              <head><title>Comix</title></head>
              <body>
                <aside class="sidebar">
                  <img src="https://comix.to/posters/rec1.jpg" width="300" height="400" />
                  <img src="https://comix.to/posters/rec2.jpg" width="300" height="400" />
                  <img src="https://comix.to/posters/rec3.jpg" width="300" height="400" />
                </aside>
                <script>
                  self.__next_f.push([1,"{\"chapter_id\":2922186,\"number\":19,\"images\":[{\"url\":\"https:\/\/img.comix.to\/ch19\/01.webp\",\"width\":800,\"height\":2200},{\"url\":\"https:\/\/img.comix.to\/ch19\/02.webp\",\"width\":800,\"height\":2300}]}"]);
                </script>
              </body>
            </html>
        """.trimIndent()

        val engine = ComicExtractorEngine()
        val result = engine.parseHtmlDocument(
            "https://comix.to/title/vlxz-ive-become-the-devils-master/2922186-chapter-19",
            html
        )

        assertEquals("Ive Become The Devils Master", result.seriesTitle)
        assertEquals("19", result.chapterNumber)
        assertEquals(2, result.pages.size)
        assertEquals("https://img.comix.to/ch19/01.webp", result.pages[0].imageUrl)
        assertEquals("https://img.comix.to/ch19/02.webp", result.pages[1].imageUrl)
    }

    @Test
    fun `apk export helper returns valid app metadata and apk filename`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = com.example.data.util.ApkExportHelper(context)
        val info = helper.getInstalledApkInfo()

        assertEquals("PanelPull", info.appName)
        assertTrue(info.suggestedFileName.startsWith("PanelPull-v"))
        assertTrue(info.suggestedFileName.endsWith(".apk"))
    }

    @Test
    fun `extractor stitches multi-chunk next_f scripts split across script tags`() {
        val html = """
            <html>
              <head><title>Comix</title></head>
              <body>
                <script>
                  self.__next_f.push([1,"{\"chapter_id\":2922186,\"number\":19,\"images\":[{\"url\":\"https:\/\/wowpic.store\/ch19\/001.webp\"},"]);
                </script>
                <script>
                  self.__next_f.push([1,"{\"url\":\"https:\/\/wowpic.store\/ch19\/002.webp\"}]}"]);
                </script>
              </body>
            </html>
        """.trimIndent()

        val engine = ComicExtractorEngine()
        val result = engine.parseHtmlDocument(
            "https://comix.to/title/vlxz-ive-become-the-devils-master/2922186-chapter-19",
            html
        )

        assertEquals(2, result.pages.size)
        assertEquals("https://wowpic.store/ch19/001.webp", result.pages[0].imageUrl)
        assertEquals("https://wowpic.store/ch19/002.webp", result.pages[1].imageUrl)
    }

    @Test
    fun `comix api parser handles nested chapter object and files key`() {
        val comix = ComixSiteExtractor(ComicExtractorEngine.defaultHttpClient())
        val nestedJson = JSONObject(
            """
            {
              "result": {
                "chapter": {
                  "number": "19",
                  "files": [
                    { "file": "https://wowpic.store/2922186/p1.webp" },
                    { "image_url": "https://wowpic.store/2922186/p2.webp" }
                  ]
                }
              }
            }
            """.trimIndent()
        )
        val (pages, chNum) = comix.parseChapterApiResponse(
            nestedJson,
            "https://comix.to/title/vlxz-ive-become-the-devils-master/2922186-chapter-19",
            "19"
        )
        assertEquals("19", chNum)
        assertEquals(2, pages.size)
        assertEquals("https://wowpic.store/2922186/p1.webp", pages[0].imageUrl)
        assertEquals("https://wowpic.store/2922186/p2.webp", pages[1].imageUrl)
    }

    @Test
    fun `webview script helper filters posters and parses api extraction json`() {
        assertTrue(
            com.example.ui.components.WebViewJsScripts.isLikelySniffedComicImage(
                "https://wowpic2.store/files/2922186/page01.webp"
            )
        )
        assertTrue(
            !com.example.ui.components.WebViewJsScripts.isLikelySniffedComicImage(
                "https://comix.to/posters/cover.jpg"
            )
        )
        val payload = com.example.ui.components.WebViewJsScripts.parseExtractionJson(
            rawJson = """{"url":"https://comix.to/title/vlxz-ive-become-the-devils-master/2922186-chapter-19","title":"Chapter 19","images":["https://wowpic.store/1.webp","https://wowpic.store/2.webp"],"chapters":[{"text":"Capítulo 20","href":"https://comix.to/title/vlxz-ive-become-the-devils-master/2922190-chapter-20"}],"source":"api","readyState":"complete"}""",
            fallbackUrl = "https://comix.to",
            fallbackTitle = "Comix",
            sniffedSnapshot = emptyList()
        )
        assertEquals("api", payload.source)
        assertEquals(2, payload.images.size)
        assertEquals(1, payload.chapters.size)
    }

    @Test
    fun `comic image loader detects real formats from magic bytes and rejects html responses`() {
        val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)
        val jpgHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
        val webpHeader = "RIFF1234WEBP".toByteArray(Charsets.US_ASCII)
        val avifHeader = byteArrayOf(0, 0, 0, 0x1C) + "ftypavif".toByteArray(Charsets.US_ASCII)
        val htmlBytes = "<!DOCTYPE html><html><body>403 Forbidden</body></html>".toByteArray(Charsets.UTF_8)

        assertEquals("png" to "image/png", com.example.data.extractor.ComicImageLoader.detectFormatFromBytes(pngHeader))
        assertEquals("jpg" to "image/jpeg", com.example.data.extractor.ComicImageLoader.detectFormatFromBytes(jpgHeader))
        assertEquals("webp" to "image/webp", com.example.data.extractor.ComicImageLoader.detectFormatFromBytes(webpHeader))
        assertEquals("avif" to "image/avif", com.example.data.extractor.ComicImageLoader.detectFormatFromBytes(avifHeader))
        assertTrue(com.example.data.extractor.ComicImageLoader.looksLikeHtmlNotImage(htmlBytes))
        assertTrue(!com.example.data.extractor.ComicImageLoader.looksLikeHtmlNotImage(pngHeader + ByteArray(16)))
    }

    @Test
    fun `smart stitch pixel detector identifies uniform gutters and ignores edge margins`() {
        val uniformWhite = IntArray(200) { android.graphics.Color.WHITE }
        val uniformDark = IntArray(200) { android.graphics.Color.rgb(20, 20, 24) }
        val rowWithBubble = IntArray(200) { android.graphics.Color.WHITE }.apply {
            this[100] = android.graphics.Color.BLACK
        }
        val rowWithEdgeBorderOnly = IntArray(200) { android.graphics.Color.WHITE }.apply {
            this[0] = android.graphics.Color.BLACK
            this[2] = android.graphics.Color.BLACK
            this[199] = android.graphics.Color.BLACK
        }

        assertTrue(com.example.data.stitch.SmartStitchEngine.isRowSafeForCut(uniformWhite, ignorablePixels = 5, sensitivity = 90))
        assertTrue(com.example.data.stitch.SmartStitchEngine.isRowSafeForCut(uniformDark, ignorablePixels = 5, sensitivity = 90))
        assertTrue(!com.example.data.stitch.SmartStitchEngine.isRowSafeForCut(rowWithBubble, ignorablePixels = 5, sensitivity = 90))
        assertTrue(com.example.data.stitch.SmartStitchEngine.isRowSafeForCut(rowWithEdgeBorderOnly, ignorablePixels = 5, sensitivity = 90))
        assertTrue(com.example.data.stitch.SmartStitchEngine.isRowSafeForCut(rowWithBubble, ignorablePixels = 5, sensitivity = 0))
    }

    @Test
    fun `smart stitch avoids slicing through speech bubbles by shifting to nearest clean gutter`() {
        val cleanRow = IntArray(100) { android.graphics.Color.WHITE }
        val bubbleRow = IntArray(100) { android.graphics.Color.WHITE }.apply {
            this[50] = android.graphics.Color.BLACK
        }

        // Simulate artwork/bubble spanning rows 950..1080 around idealY = 1000
        val cutY = com.example.data.stitch.SmartStitchEngine.findSmartCutY(
            startY = 0,
            targetHeight = 1000,
            totalHeight = 2500,
            sensitivity = 90,
            ignorablePixels = 5,
            scanStep = 5,
            readRow = { y -> if (y in 950..1080) bubbleRow else cleanRow }
        )

        assertTrue("Expected smart cut outside bubble zone [950..1080], got $cutY", cutY < 950 || cutY > 1080)
        assertEquals(945, cutY)
    }

    @Test
    fun `smart stitch stitches bitmaps of different widths with automatic width enforcement`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = com.example.data.stitch.SmartStitchEngine(context)

        val tempRoot = java.io.File(context.cacheDir, "smartstitch_test_${System.currentTimeMillis()}").apply { mkdirs() }
        val input1 = java.io.File(tempRoot, "001.png")
        val input2 = java.io.File(tempRoot, "002.png")
        val outSmartDir = java.io.File(tempRoot, "out_smart")
        val outStripDir = java.io.File(tempRoot, "out_strip")

        try {
            // Page 1: 400x700 white canvas with a dark panel band in the middle
            val bmp1 = android.graphics.Bitmap.createBitmap(400, 700, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(bmp1).apply {
                drawColor(android.graphics.Color.WHITE)
                val paint = android.graphics.Paint().apply { color = android.graphics.Color.DKGRAY }
                drawRect(40f, 100f, 360f, 550f, paint)
            }
            java.io.FileOutputStream(input1).use { bmp1.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bmp1.recycle()

            // Page 2: 600x900 (scales to 400x600 under AUTOMATIC width enforcement -> total height = 1300)
            val bmp2 = android.graphics.Bitmap.createBitmap(600, 900, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(bmp2).apply {
                drawColor(android.graphics.Color.WHITE)
                val paint = android.graphics.Paint().apply { color = android.graphics.Color.BLUE }
                drawRect(60f, 200f, 540f, 700f, paint)
            }
            java.io.FileOutputStream(input2).use { bmp2.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bmp2.recycle()

            val smartResult = kotlinx.coroutines.runBlocking {
                engine.stitchFiles(
                    inputFiles = listOf(input1, input2),
                    outputDir = outSmartDir,
                    galleryRelativePath = null,
                    config = com.example.data.stitch.SmartStitchConfig(
                        detectorMode = com.example.data.stitch.StitchDetectorMode.SMART_PIXEL,
                        splitHeight = 1000,
                        sensitivity = 90,
                        ignorablePixels = 5,
                        scanStep = 5,
                        widthEnforcement = com.example.data.stitch.WidthEnforcementMode.AUTOMATIC,
                        outputFormat = com.example.data.stitch.StitchOutputFormat.PNG,
                        saveToGallery = false
                    ),
                    onProgress = {}
                )
            }

            assertEquals(2, smartResult.inputPageCount)
            assertEquals(400, smartResult.targetWidth)
            assertTrue("Expected at least 2 slices for 1300px height with 1000px split", smartResult.outputFiles.size >= 2)
            assertEquals(1300, smartResult.outputFiles.sumOf { it.height })
            assertTrue(smartResult.outputFiles.all { it.file.exists() && it.file.length() > 0L && it.width == 400 })

            val stripResult = kotlinx.coroutines.runBlocking {
                engine.stitchFiles(
                    inputFiles = listOf(input1, input2),
                    outputDir = outStripDir,
                    galleryRelativePath = null,
                    config = com.example.data.stitch.SmartStitchConfig(
                        detectorMode = com.example.data.stitch.StitchDetectorMode.SINGLE_STRIP,
                        widthEnforcement = com.example.data.stitch.WidthEnforcementMode.AUTOMATIC,
                        outputFormat = com.example.data.stitch.StitchOutputFormat.PNG,
                        saveToGallery = false
                    ),
                    onProgress = {}
                )
            }

            assertEquals(1, stripResult.outputFiles.size)
            assertEquals(400, stripResult.outputFiles[0].width)
            assertEquals(1300, stripResult.outputFiles[0].height)
            assertTrue(stripResult.outputFiles[0].file.exists() && stripResult.outputFiles[0].file.length() > 0L)
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
