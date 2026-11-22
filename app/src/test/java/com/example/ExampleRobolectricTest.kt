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
}
