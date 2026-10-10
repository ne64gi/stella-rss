package net.fuyumori.stellarss

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

fun webUrl(value: String?, base: String? = null): String? {
    if (value.isNullOrBlank()) return null
    val url = base?.toHttpUrlOrNull()?.resolve(value.trim()) ?: value.trim().toHttpUrlOrNull()
    return url?.takeIf { it.username.isEmpty() && it.password.isEmpty() }?.toString()
}
fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

data class ParsedEntry(val key: String, val title: String, val url: String, val summary: String,
                       val published: Long?, val images: List<String>, val bodyHtml: String = "",
                       val summaryHtml: String = "", val bodyTruncated: Boolean = false)
data class ParsedFeed(val title: String, val entries: List<ParsedEntry>)

/** Namespace-aware pull parser; no external entities, scripts, DTDs or unbounded bodies. */
object FeedParser {
    private const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private const val RSS1 = "http://purl.org/rss/1.0/"
    private const val MEDIA = "http://search.yahoo.com/mrss/"
    private const val CONTENT = "http://purl.org/rss/1.0/modules/content/"
    private const val DC = "http://purl.org/dc/elements/1.1/"
    private data class Node(val name: String, val ns: String, val attrs: Map<String, String>,
                            val base: String, val children: MutableList<Node> = mutableListOf(),
                            val text: StringBuilder = StringBuilder(), val parts: MutableList<Any> = mutableListOf()) {
        fun direct(name: String, namespace: String? = null) = children.firstOrNull { it.name == name && (namespace == null || it.ns == namespace) }
        fun value(name: String, namespace: String? = null) = direct(name, namespace)?.value().orEmpty()
        fun value(): String = text.toString()
        fun element(): org.jsoup.nodes.Element = org.jsoup.nodes.Element(name).also { element ->
            attrs.filterKeys { !it.startsWith("{") && it != "xmlns" }.forEach { (key, value) ->
                element.attr(key, if (key in listOf("href", "src", "data-src")) webUrl(value, base) ?: value else value)
            }
            parts.forEach { if (it is Node) element.appendChild(it.element()) else element.appendText(it.toString()) }
        }
        fun markup(): String = parts.joinToString("") { if (it is Node) it.element().outerHtml() else ArticleHtml.text(it.toString()) }
        fun payload(atom: Boolean): String = when {
            atom && attrs["src"] != null -> "" // Out-of-line Atom content is not fetched implicitly.
            atom && attrs["type"] == "xhtml" -> markup()
            atom && (attrs["type"] == null || attrs["type"] == "text") -> "<p>${ArticleHtml.text(value())}</p>"
            atom && attrs["type"] !in listOf("html", "text/html", "application/xhtml+xml") -> ""
            children.isNotEmpty() -> markup()
            else -> value()
        }
        fun all(): List<Node> = listOf(this) + children.flatMap { it.all() }
        fun rdfReference(name: String): String? = attrs["{$RDF}$name"]?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { webUrl(it, base) ?: it }
    }
    fun parse(input: InputStream, source: String): ParsedFeed {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(input, null) // Honour XML encoding / BOM, not only UTF-8.
        val stack = ArrayDeque<Node>()
        var root: Node? = null
        var nodes = 0
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.DOCDECL -> error("DTD付きのフィードには対応していません")
                XmlPullParser.START_TAG -> {
                    require(++nodes <= 30000 && stack.size < 48) { "フィードが大きすぎます" }
                    val inherited = stack.lastOrNull()?.base ?: source
                    val base = webUrl(parser.getAttributeValue("http://www.w3.org/XML/1998/namespace", "base"), inherited) ?: inherited
                    val attrs = buildMap {
                        for (i in 0 until parser.attributeCount) {
                            put(parser.getAttributeName(i), parser.getAttributeValue(i))
                            put("{${parser.getAttributeNamespace(i)}}${parser.getAttributeName(i)}", parser.getAttributeValue(i))
                        }
                    }
                    val node = Node(parser.name.lowercase(Locale.ROOT), parser.namespace.orEmpty(), attrs, base)
                    stack.lastOrNull()?.children?.add(node)
                    stack.lastOrNull()?.parts?.add(node)
                    if (root == null) root = node
                    stack.addLast(node)
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> {
                    val t = parser.text.orEmpty()
                    stack.forEach { it.text.append(t) }
                    stack.lastOrNull()?.parts?.add(t)
                }
                XmlPullParser.END_TAG -> stack.removeLast()
            }
            parser.nextToken()
        }
        val document = requireNotNull(root) { "空のフィードです" }
        val rdf = document.name == "rdf" && document.ns == RDF
        require(document.name == "rss" || document.name == "feed" || rdf) { "RSS 1.0 / RSS 2.0 / AtomのURLを指定してください" }
        val channel = when {
            rdf -> requireNotNull(document.direct("channel", RSS1)) { "RSS 1.0のchannelがありません" }
            document.name == "rss" -> document.direct("channel") ?: error("channelがありません")
            else -> document
        }
        // RSS 1.0 items are siblings of channel, not its children. Namespace URIs, not prefixes, identify them.
        val itemNodes = if (rdf) {
            val order = channel.direct("items", RSS1)?.direct("seq", RDF)?.children.orEmpty()
                .filter { it.name == "li" && it.ns == RDF }.mapNotNull { it.rdfReference("resource") }
                .distinct().withIndex().associate { it.value to it.index }
            document.children.filter { it.name == "item" && it.ns == RSS1 }
                .sortedBy { order[it.rdfReference("about")] ?: Int.MAX_VALUE }
        } else channel.children.filter { it.name == "item" || it.name == "entry" }
        val entries = itemNodes.take(250).mapNotNull { node ->
            val atom = node.name == "entry"
            val linkNode = if (atom) node.children.firstOrNull {
                it.name == "link" && (it.attrs["rel"] ?: "alternate") == "alternate" &&
                    (it.attrs["type"] == null || it.attrs["type"] == "text/html" || it.attrs["type"] == "application/xhtml+xml")
            } else node.direct("link", if (rdf) RSS1 else null)
            val raw = if (atom) linkNode?.attrs?.get("href") else linkNode?.value()
            val guid = node.direct("guid")
            val about = if (rdf) node.rdfReference("about") else null
            val url = webUrl(raw, linkNode?.base ?: node.base) ?: if (rdf) webUrl(about, node.base)
                else if (!atom && guid?.attrs?.get("isPermaLink") != "false") webUrl(guid?.value(), node.base) else null
            if (url == null) return@mapNotNull null
            val html = node.children.filter { it.name in listOf("description", "summary", "content", "encoded") && it.ns != MEDIA }
            val bodyNode = if (atom) node.direct("content", "http://www.w3.org/2005/Atom") ?: node.direct("content")
                else node.direct("encoded", CONTENT)
            val summaryNode = if (atom) node.direct("summary") else node.direct("description", if (rdf) RSS1 else null)
            val bodyRaw = bodyNode?.payload(atom).orEmpty()
            val summaryRaw = summaryNode?.payload(atom).orEmpty()
            val bodyHtml = ArticleHtml.sanitize(bodyRaw, bodyNode?.base ?: url)
            val summaryHtml = ArticleHtml.sanitize(summaryRaw, summaryNode?.base ?: url)
            val media = node.all().filter { it.ns == MEDIA && it.name in listOf("content", "thumbnail") &&
                (it.attrs["type"].isNullOrBlank() || it.attrs["type"]!!.startsWith("image/")) &&
                (it.attrs["medium"].isNullOrBlank() || it.attrs["medium"] == "image") }
                .mapNotNull { webUrl(it.attrs["url"], it.base) }
            val enclosures = node.children.filter { (it.name == "enclosure" || it.name == "link" && it.attrs["rel"] == "enclosure") &&
                (it.attrs["type"].isNullOrBlank() || it.attrs["type"]!!.startsWith("image/")) }
                .mapNotNull { webUrl(it.attrs["url"] ?: it.attrs["href"], it.base) }
            val inline = html.sortedBy { if (it.ns == CONTENT || it.name == "content") 0 else 1 }
                .flatMap { block -> htmlImages(block.value(), block.base) + block.all().filter { it.name == "img" }
                    .mapNotNull { webUrl(it.attrs["src"] ?: it.attrs["data-src"], it.base) } }
            val date = node.value("published").ifBlank { node.value("pubdate") }.ifBlank {
                node.children.firstOrNull { it.ns == DC && it.name == "date" }?.value().orEmpty()
            }.ifBlank { node.value("updated") }
            val key = about ?: node.value(if (atom) "id" else "guid").ifBlank { url }
            ParsedEntry(key.take(4096), Jsoup.parse(node.value("title", if (rdf) RSS1 else null)).text().ifBlank { url }.take(1000), url,
                Jsoup.parse(summaryHtml.ifBlank { bodyHtml }).text().take(3000), date(date),
                (media + enclosures + inline).distinct().take(12), bodyHtml, summaryHtml,
                bodyRaw.length > ArticleHtml.MAX_HTML || summaryRaw.length > ArticleHtml.MAX_HTML)
        }
        return ParsedFeed(Jsoup.parse(channel.value("title", if (rdf) RSS1 else null)).text().take(200), entries.distinctBy { it.key })
    }
    fun htmlImages(html: String, base: String): List<String> = Jsoup.parse(html, base).select("img").mapNotNull {
        webUrl(it.attr("src").ifBlank { it.attr("data-src") }, base)
    }
    fun ogImages(html: String, base: String): List<String> = Jsoup.parse(html, base)
        .select("meta[property=og:image], meta[property=og:image:secure_url], meta[name=og:image]")
        .mapNotNull { webUrl(it.attr("content"), base) }.distinct()
    fun date(value: String): Long? {
        val s = value.trim()
        runCatching { return Instant.parse(s).toEpochMilli() }
        for (format in listOf(DateTimeFormatter.ISO_OFFSET_DATE_TIME, DateTimeFormatter.RFC_1123_DATE_TIME,
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.US),
            DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss Z", Locale.US),
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss z", Locale.US))) {
            runCatching { return ZonedDateTime.parse(s, format).toInstant().toEpochMilli() }
        }
        return null
    }
}
