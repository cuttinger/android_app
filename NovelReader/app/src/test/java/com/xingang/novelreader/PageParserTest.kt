package com.xingang.novelreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 用 twkan.com 抓下来的真实页面验证解析器（目录页已展开全部章节）
class PageParserTest {

    private fun fixture(name: String) =
        javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes().toString(Charsets.UTF_8)

    @Test
    fun catalogPage() {
        val page = PageParser.parse(fixture("twkan_index.html"), "https://twkan.com/book/111560/index.html")
        assertTrue("应识别为目录页，实际 $page", page is Page.Catalog)
        page as Page.Catalog
        println("书名=${page.title} 章节数=${page.chapters.size}")
        println("首章=${page.chapters.first()}")
        println("末章=${page.chapters.last()}")
        assertEquals("让仙门再次伟大", page.title)
        assertEquals(418, page.chapters.size)
        assertTrue(page.chapters.first().text.startsWith("第一章"))
        assertEquals("https://twkan.com/txt/111560/56227466", page.chapters.first().url)
        assertTrue(page.chapters.last().text.startsWith("第405章"))
    }

    @Test
    fun chapterPage() {
        val page = PageParser.parse(fixture("twkan_chapter.html"), "https://twkan.com/txt/111560/56227467")
        assertTrue("应识别为阅读页，实际 ${page::class.simpleName}", page is Page.Chapter)
        page as Page.Chapter
        println("标题=${page.title} 书名=${page.bookTitle} 段落数=${page.paragraphs.size}")
        println("上一章=${page.prev} 下一章=${page.next} 目录=${page.catalog}")
        println("首段=${page.paragraphs.first()}")
        println("末段=${page.paragraphs.last()}")
        page.paragraphs.filter { it.length < 12 }.take(10).forEach { println("短段: $it") }
        assertEquals("第二章 无间道", page.title)
        assertEquals("让仙门再次伟大", page.bookTitle)
        assertEquals("https://twkan.com/txt/111560/56227466", page.prev?.url)
        assertEquals("https://twkan.com/txt/111560/56227468", page.next?.url)
        assertEquals("https://twkan.com/book/111560/index.html", page.catalog?.url)
        assertTrue("段落数 ${page.paragraphs.size}", page.paragraphs.size in 120..140)
        assertTrue(page.paragraphs.first().startsWith("白莲教"))
        assertTrue(page.paragraphs.last().endsWith("正好找机会和上线碰头。』"))
        assertNotNull(page.paragraphs.none { it.contains("twkan") || it.contains("廣告") })
    }
}

// deqixs.cc：正文 ajax 渲染 + 前端分页 + JS 按钮导航；xwbxsw.net：静态页 + 章内分页
class OtherSitesTest {
    private fun fixture(name: String) =
        javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes().toString(Charsets.UTF_8)

    @Test
    fun deqixsChapterPage1() {
        val page = PageParser.parse(fixture("deqixs_p1.html"), "https://www.deqixs.cc/books/5407/5664755.html")
        assertTrue("应识别为阅读页，实际 ${page::class.simpleName}", page is Page.Chapter)
        page as Page.Chapter
        println("[deqixs] 标题=${page.title} 段落数=${page.paragraphs.size}")
        println("[deqixs] 上一章=${page.prev} 下一章=${page.next} 目录=${page.catalog}")
        println("[deqixs] 首段=${page.paragraphs.first()}")
        println("[deqixs] 末段=${page.paragraphs.last()}")
        assertTrue(page.title.startsWith("第四百零二章"))
        println("[deqixs] 书名=${page.bookTitle}")
        assertTrue("书名 ${page.bookTitle}", page.bookTitle?.startsWith("让仙门再次伟大") == true)
        assertTrue("段落数 ${page.paragraphs.size}", page.paragraphs.size >= 20)
        assertTrue(page.paragraphs.first().startsWith("“掌教客气了"))
        // 上一章：从脚本 loadChapter 中提取到的真实 URL
        assertEquals("https://www.deqixs.cc/books/5407/5664754.html", page.prev?.url)
        // 下一页：纯 JS 按钮 → 点击动作
        assertEquals("下一页", page.next?.text)
        assertTrue(page.next!!.isAction)
        assertEquals("https://www.deqixs.cc/books/5407/", page.catalog?.url)
        assertTrue(page.paragraphs.none { it.contains("正在加载") })
    }

    @Test
    fun xwbxswChapterPage1() {
        val page = PageParser.parse(fixture("xwbxsw_p1.html"), "http://www.xwbxsw.net/book/246565/3374902.html")
        assertTrue("应识别为阅读页，实际 ${page::class.simpleName}", page is Page.Chapter)
        page as Page.Chapter
        println("[xwbxsw] 标题=${page.title} 段落数=${page.paragraphs.size}")
        println("[xwbxsw] 上一章=${page.prev} 下一章=${page.next} 目录=${page.catalog}")
        println("[xwbxsw] 首段=${page.paragraphs.first()}")
        println("[xwbxsw] 末段=${page.paragraphs.last()}")
        assertTrue(page.title.startsWith("第三百八十七章"))
        println("[xwbxsw] 书名=${page.bookTitle}")
        assertEquals("让仙门再次伟大", page.bookTitle)
        assertTrue("段落数 ${page.paragraphs.size}", page.paragraphs.size >= 10)
        assertEquals("http://www.xwbxsw.net/book/246565/3374901.html", page.prev?.url)
        assertEquals("http://www.xwbxsw.net/book/246565/3374902_2.html", page.next?.url)
        assertEquals("下一页", page.next?.text)
        assertEquals("http://www.xwbxsw.net/book/246/246565/", page.catalog?.url)
    }

    @Test
    fun chapterNumbers() {
        assertEquals(1, PageParser.chapterNumber("第一章 我們之中出了一個叛徒！"))
        assertEquals(15, PageParser.chapterNumber("第十五章 聞名一縣"))
        assertEquals(402, PageParser.chapterNumber("第四百零二章 怎么又是你啊"))
        assertEquals(385, PageParser.chapterNumber("第385章 對武道的探索"))
        assertEquals(null, PageParser.chapterNumber("番外 一"))
    }
}
