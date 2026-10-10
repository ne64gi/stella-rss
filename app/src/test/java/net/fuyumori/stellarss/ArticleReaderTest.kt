package net.fuyumori.stellarss

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class ArticleReaderTest {
    private fun parse(xml: String) = FeedParser.parse(xml.byteInputStream(), "https://example.org/feed.xml").entries.single()
    @Test fun rssBodyAndSummaryRemainSeparateAndRelativeLinksResolve() {
        val entry = parse("""<rss xmlns:content="http://purl.org/rss/1.0/modules/content/"><channel><item><guid>one</guid><title>Title</title><link>https://example.org/news/a</link><description><![CDATA[<p>Short <b>summary</b></p>]]></description><content:encoded xml:base="https://cdn.example.org/story/"><![CDATA[<h2>Heading</h2><p>Full <em>article</em> text</p><img src="a.png"><a href="details">Read</a>]]></content:encoded></item></channel></rss>""")
        assertEquals("Short summary", entry.summary)
        val body = Jsoup.parse(entry.bodyHtml)
        assertEquals("Heading", body.select("h2").text())
        assertEquals("https://cdn.example.org/story/a.png", body.select("img").attr("src"))
        assertEquals("https://cdn.example.org/story/details", body.select("a").attr("href"))
        assertFalse(entry.summaryHtml.contains("Full"))
    }
    @Test fun atomXhtmlPreservesMixedContentOrderAndNestedBase() {
        val entry = parse("""<feed xmlns="http://www.w3.org/2005/Atom"><entry><id>one</id><link href="https://example.org/a"/><title>T</title><summary type="text">&lt;script&gt;literal&lt;/script&gt;</summary><content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><h2>Header</h2><p>Before <b>bold</b> after</p><div xml:base="https://img.example.org/"><img src="a.png"/></div></div></content></entry></feed>""")
        assertTrue(Jsoup.parse(entry.bodyHtml).text().contains("Before bold after"))
        assertEquals("https://img.example.org/a.png", Jsoup.parse(entry.bodyHtml).select("img").attr("src"))
        assertEquals("<script>literal</script>", Jsoup.parse(entry.summaryHtml).text())
        assertTrue(Jsoup.parse(entry.summaryHtml).select("script").isEmpty())
    }
    @Test fun atomPlainHtmlAndOutOfLineContentAreDistinguished() {
        fun atom(content: String) = parse("""<feed xmlns="http://www.w3.org/2005/Atom"><entry><id>one</id><link href="https://example.org/a"/><title>T</title>$content</entry></feed>""")
        assertEquals("<b>literal</b>", Jsoup.parse(atom("<content>&lt;b&gt;literal&lt;/b&gt;</content>").bodyHtml).text())
        assertEquals("bold", Jsoup.parse(atom("<content type='html'>&lt;b&gt;bold&lt;/b&gt;</content>").bodyHtml).select("b").text())
        assertEquals("", atom("<content type='text/html' src='https://example.org/external'/>").bodyHtml)
    }
    @Test fun activeMarkupAndUntrustedSchemesNeverSurviveAllowlist() {
        val html = """<base href='https://evil.test/'><meta http-equiv='refresh' content='0;url=https://evil.test'><script>alert(1)</script><iframe src='https://evil.test'></iframe><svg><script>alert(2)</script></svg><form action='https://evil.test'><input autofocus onfocus='alert(3)'></form><p style='background:url(https://evil.test)' onclick='bad()'>Safe</p><img src='data:image/svg+xml,bad' onerror='bad()'><a href='java&#x73;cript:alert(1)'>bad</a><a href='file:///sdcard/x'>file</a><a href='intent://x'>intent</a><img src='content://private'><a href='https://user:pass@example.org'>credentials</a><a href='/safe'>OK</a>"""
        val cleaned = ArticleHtml.sanitize(html, "https://example.org/feed")
        val doc = Jsoup.parse(cleaned)
        assertEquals(0, doc.select("script,iframe,svg,form,input,meta,base,[style],[onclick],[onerror],img").size)
        assertEquals(listOf("https://example.org/safe"), doc.select("a[href]").map { it.attr("href") })
        assertTrue(doc.text().contains("Safe"))
    }
    @Test fun pageShowsScopeAndRewritesImagesOnlyToItsOwnMap() {
        val a = Article("one", 1, "<script>title</script>", "https://example.org/a", "Only summary", 1,
            bodyHtml = "<h2>Full</h2><p>${"Long body. ".repeat(50)}</p><img src='https://example.org/img'>", summaryHtml = "<p>Only summary</p>")
        val row = ArticleRow(a, "Source", true, true)
        val full = ArticleHtml.page(row, ReaderSettings(), "Today", "one")
        val doc = Jsoup.parse(full.html)
        assertEquals(1, full.images.size)
        assertEquals("https://example.org/img", full.images.values.single())
        assertTrue(doc.select("img").attr("src").startsWith(ArticleHtml.ORIGIN))
        assertEquals(0, doc.select("script").size)
        assertEquals("<script>title</script>", doc.select("h1").text())
        assertTrue(full.notice.contains("全文とは限りません"))
        val summary = ArticleHtml.page(row, ReaderSettings(summaryFirst = true, images = false), "Today", "two")
        assertTrue(summary.notice.contains("概要")); assertFalse(summary.html.contains("Long body")); assertTrue(summary.images.isEmpty())
        val noImage = ArticleHtml.page(row, ReaderSettings(images = false), "Today", "three")
        assertTrue(noImage.images.isEmpty()); assertTrue(Jsoup.parse(noImage.html).select("img").isEmpty())
    }
    @Test fun noBodyDoesNotInventTextAndLargeBodiesAreLabelled() {
        val a = Article("empty", 1, "Title", "https://example.org/a", "", 1)
        assertTrue(ArticleHtml.page(ArticleRow(a, "Feed", true, true), ReaderSettings(), "Now", "x").notice.contains("配信されていません"))
        val entry = parse("<rss xmlns:content='http://purl.org/rss/1.0/modules/content/'><channel><item><link>https://example.org/a</link><content:encoded>${"x".repeat(ArticleHtml.MAX_HTML + 10)}</content:encoded></item></channel></rss>")
        assertTrue(entry.bodyTruncated); assertTrue(entry.bodyHtml.length <= ArticleHtml.MAX_HTML)
        val page = ArticleHtml.page(ArticleRow(a.copy(bodyHtml = entry.bodyHtml, bodyTruncated = true), "Feed", true, true), ReaderSettings(), "Now", "y")
        assertTrue(page.notice.contains("保存できた範囲"))
    }
    @Test fun settingsAreBoundedAndRoundTrip() {
        val value = ReaderSettings(true, 26, 2.1f, 32, false, true)
        assertEquals(value, ReaderSettings.decode(value.encode()))
        assertEquals(ReaderSettings(), ReaderSettings.decode(null))
        val malformed = ReaderSettings.decode("true|100|NaN|-20|false|true")
        assertEquals(28, malformed.fontSp); assertEquals(1.7f, malformed.lineHeight); assertEquals(8, malformed.margin)
    }
}
