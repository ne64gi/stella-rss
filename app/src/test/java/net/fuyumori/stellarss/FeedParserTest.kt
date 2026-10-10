package net.fuyumori.stellarss

import org.junit.Assert.*
import org.junit.Test

class FeedParserTest {
    private fun rss(item: String) = FeedParser.parse(("""<rss version="2.0" xmlns:m="http://search.yahoo.com/mrss/" xmlns:c="http://purl.org/rss/1.0/modules/content/"><channel><title>Daily &amp; News</title>$item</channel></rss>""").byteInputStream(), "https://example.com/feed.xml")
    @Test fun mediaEnclosureAndHtmlAreOrderedAndRelativeUrlsResolved() {
        val feed = rss("""<item><guid>x</guid><link>/one</link><title>Hello &amp; world</title>
            <description><![CDATA[Text <img src="/description.jpg">]]></description>
            <c:encoded><![CDATA[<img src="/content.jpg">]]></c:encoded>
            <enclosure url="/movie.mp4" type="video/mp4"/><enclosure url="/enclosure.jpg" type="image/jpeg"/>
            <m:group><m:content url="/media.jpg" medium="image"/><m:thumbnail url="/thumb.jpg"/></m:group>
            <pubDate>Wed, 02 Oct 2002 08:00:00 GMT</pubDate></item>""")
        assertEquals("Daily & News", feed.title)
        val entry = feed.entries.single()
        assertEquals("https://example.com/one", entry.url)
        assertEquals("Hello & world", entry.title)
        assertEquals(listOf("media", "thumb", "enclosure", "content", "description").map { "https://example.com/$it.jpg" }, entry.images)
        assertEquals(1033545600000, entry.published)
    }
    @Test fun atomAlternateLinksXmlBaseAndXhtmlImages() {
        val atom = """<feed xmlns="http://www.w3.org/2005/Atom" xml:base="https://example.com/news/"><title>Atom</title>
          <entry xml:base="day/"><id>urn:uuid:1</id><title type="html">Title &amp;amp; text</title>
          <link rel="self" href="entry.xml"/><link rel="alternate" href="article"/>
          <link rel="enclosure" type="image/png" href="photo.png"/>
          <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml">Details<img src="more.png"/></div></content>
          <published>2026-10-10T09:00:00+09:00</published><updated>2026-10-11T00:00:00Z</updated></entry></feed>"""
        val item = FeedParser.parse(atom.byteInputStream(), "https://other.org/rss").entries.single()
        assertEquals("https://example.com/news/day/article", item.url)
        assertEquals(listOf("https://example.com/news/day/photo.png", "https://example.com/news/day/more.png"), item.images)
        assertEquals("Title & text", item.title)
        assertEquals(1791590400000L, item.published)
    }
    @Test fun jpcertStyleRdfReadsRootItemsSequenceAndMinutePrecisionDcDates() {
        val xml = """<rdf:RDF xmlns="http://purl.org/rss/1.0/"
            xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
            xmlns:dc="http://purl.org/dc/elements/1.1/">
            <channel rdf:about="https://example.com/rss/news.rdf"><title>注意喚起</title>
              <items><rdf:Seq><rdf:li rdf:resource="/alert"/><rdf:li rdf:resource="/weekly#1"/></rdf:Seq></items>
            </channel>
            <item rdf:about="/weekly#1"><title>週次報告</title><link>/weekly#1</link><dc:date>2026-10-07T12:00+09:00</dc:date></item>
            <item rdf:about="/alert"><title>注意喚起 (更新)</title><link>/alert</link><dc:date>2026-10-09T10:12+09:00</dc:date></item>
            </rdf:RDF>"""
        val feed = FeedParser.parse(xml.byteInputStream(), "https://example.com/rss/news.rdf")
        assertEquals("注意喚起", feed.title)
        assertEquals(listOf("注意喚起 (更新)", "週次報告"), feed.entries.map { it.title })
        assertEquals("https://example.com/alert", feed.entries[0].key)
        assertEquals("https://example.com/weekly#1", feed.entries[1].url)
        assertEquals(java.time.Instant.parse("2026-10-09T01:12:00Z").toEpochMilli(), feed.entries[0].published)
        assertTrue(feed.entries.all { it.images.isEmpty() })
    }
    @Test fun prefixedRss1UsesNamespaceUrisXmlBaseStableIdentityAndContentImages() {
        val xml = """<r:RDF xmlns:r="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
            xmlns:s="http://purl.org/rss/1.0/" xmlns:c="http://purl.org/rss/1.0/modules/content/"
            xmlns:other="urn:other" xml:base="https://example.com/news/">
            <s:channel><other:title>Wrong</other:title><s:title>RSS1</s:title></s:channel>
            <other:item><other:link>https://example.com/wrong</other:link></other:item>
            <s:item xml:base="day/" r:about="stable" other:about="wrong">
              <other:link>https://example.com/wrong</other:link><s:link>current</s:link>
              <other:title>Wrong</other:title><s:title>Current</s:title>
              <c:encoded><![CDATA[<img src="photo.jpg">]]></c:encoded>
            </s:item></r:RDF>"""
        val feed = FeedParser.parse(xml.byteInputStream(), "https://other.example/feed")
        assertEquals("RSS1", feed.title)
        val item = feed.entries.single()
        assertEquals("Current", item.title)
        assertEquals("https://example.com/news/day/stable", item.key)
        assertEquals("https://example.com/news/day/current", item.url)
        assertEquals(listOf("https://example.com/news/day/photo.jpg"), item.images)
    }
    @Test fun rdfAboutFallbackStillRequiresSafeArticleUrlAndKeepsOpaqueIdentity() {
        val xml = """<rdf:RDF xmlns="http://purl.org/rss/1.0/" xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <channel><title>Fallback</title></channel>
            <item rdf:about="https://example.com/no-link"><title>No link</title></item>
            <item rdf:about="urn:news:1"><link>https://example.com/with-link</link></item>
            <item rdf:about="javascript:bad"><link>file:///secret</link></item>
            <item rdf:about="https://user:pass@example.com/private"/>
            </rdf:RDF>"""
        val items = FeedParser.parse(xml.byteInputStream(), "https://example.com/feed").entries
        assertEquals(2, items.size)
        assertEquals("https://example.com/no-link", items[0].url)
        assertEquals("urn:news:1", items[1].key)
    }
    @Test(expected = IllegalArgumentException::class) fun unrelatedRdfIsNotAcceptedAsAnEmptyFeed() {
        FeedParser.parse("""<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><channel><title>Not RSS1</title></channel></rdf:RDF>""".byteInputStream(), "https://example.com")
    }
    @Test(expected = IllegalArgumentException::class) fun rdfLocalNameWithoutRdfNamespaceIsRejected() {
        FeedParser.parse("""<RDF xmlns="http://purl.org/rss/1.0/"><channel><title>Not RDF</title></channel></RDF>""".byteInputStream(), "https://example.com")
    }
    @Test fun nonWebAndCredentialUrlsAreRejected() {
        assertNull(webUrl("javascript:alert(1)", "https://example.com"))
        assertNull(webUrl("file:///etc/passwd"))
        assertNull(webUrl("https://user:pass@example.com/"))
        assertEquals("https://example.com/img.png", webUrl("//example.com/img.png", "https://a.test"))
    }
    @Test fun noDateRemainsNullForRepositoryStableFallback() {
        val item = rss("<item><link>https://example.com/1</link><title>no date</title></item>").entries.single()
        assertNull(item.published); assertTrue(item.images.isEmpty())
    }
    @Test fun duplicateIdsAreCollapsedAndInvalidEntriesSkipped() {
        val item = "<item><guid>same</guid><link>https://example.com/1</link></item>"
        assertEquals(1, rss(item + item + "<item><guid isPermaLink=\"false\">not-a-link</guid></item>").entries.size)
    }
    @Test fun enclosureWithoutImageMimeFallsThroughAndGuidPermalinkWorks() {
        val item = rss("""<item><guid>https://example.com/entry</guid><m:content type="video/mp4" url="/x.mp4"/><enclosure type="audio/mpeg" url="/x.mp3"/></item>""").entries.single()
        assertEquals("https://example.com/entry", item.url); assertTrue(item.images.isEmpty())
    }
    @Test fun ogImageAttributeOrderEntitiesAndSchemes() {
        assertEquals(listOf("https://example.com/pic.jpg?a=1&b=2"), FeedParser.ogImages("""<meta content='/pic.jpg?a=1&amp;b=2' property='og:image'><meta property='og:image' content='javascript:bad'>""", "https://example.com/article"))
    }
    @Test fun declaredLegacyEncodingIsHonoured() {
        val xml = """<?xml version="1.0" encoding="ISO-8859-1"?><rss><channel><title>Café</title></channel></rss>"""
        assertEquals("Café", FeedParser.parse(xml.toByteArray(Charsets.ISO_8859_1).inputStream(), "https://example.com").title)
    }
    @Test(expected = IllegalArgumentException::class) fun htmlIsNotSilentlyAnEmptyFeed() {
        FeedParser.parse("<html><body>Login</body></html>".byteInputStream(), "https://example.com")
    }
    @Test(expected = IllegalStateException::class) fun dtdIsRejectedWithoutResolvingExternalEntities() {
        FeedParser.parse("""<!DOCTYPE rss [<!ENTITY secret SYSTEM "file:///etc/passwd">]><rss><channel><title>&secret;</title></channel></rss>""".byteInputStream(), "https://example.com")
    }
}
