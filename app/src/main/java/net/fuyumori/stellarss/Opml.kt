package net.fuyumori.stellarss

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.io.OutputStream

/** OPML subscriptions only. No includes, DTD expansion or network access while parsing. */
data class Outline(val title: String, val url: String? = null, val children: MutableList<Outline> = mutableListOf())
object Opml {
    fun parse(input: InputStream): List<Outline> {
        val bytes = input.readBytesLimited(2 * 1024 * 1024)
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(bytes.inputStream(), null)
        val roots = mutableListOf<Outline>()
        val stack = mutableListOf<Outline>()
        var rootSeen = false; var bodySeen = false; var inBody = false; var nodes = 0
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.DOCDECL -> error("DOCTYPEを含むOPMLは読み込めません")
                XmlPullParser.START_TAG -> {
                    require(++nodes <= 10000 && parser.depth <= 34) { "OPMLが大きすぎます（最大32階層）" }
                    val name = parser.name.lowercase()
                    if (parser.depth == 1) { require(name == "opml") { "OPMLファイルではありません" }; rootSeen = true }
                    if (parser.depth == 2 && name == "body") { inBody = true; bodySeen = true }
                    if (inBody && name == "outline") {
                        val rawUrl = parser.getAttributeValue(null, "xmlUrl")?.trim()
                        val url = if (rawUrl == null) null else requireNotNull(webUrl(rawUrl)) { "OPMLに無効なフィードURLがあります" }
                        val title = (parser.getAttributeValue(null, "text") ?: parser.getAttributeValue(null, "title"))
                            ?.trim()?.take(120)?.ifBlank { null } ?: url?.let { java.net.URI(it).host } ?: "名称未設定"
                        val node = Outline(title, url)
                        (stack.lastOrNull()?.children ?: roots).add(node); stack.add(node)
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (inBody && parser.name.equals("outline", true) && stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                    if (parser.depth == 2 && parser.name.equals("body", true)) inBody = false
                }
            }
            parser.nextToken()
        }
        require(rootSeen && bodySeen) { "OPMLのbodyが見つかりません" }
        return roots
    }
    fun write(library: Library, output: OutputStream) {
        val xml = XmlPullParserFactory.newInstance().newSerializer()
        xml.setOutput(output, "UTF-8"); xml.startDocument("UTF-8", true)
        xml.startTag(null, "opml"); xml.attribute(null, "version", "2.0")
        xml.startTag(null, "head"); xml.startTag(null, "title"); xml.text("Stella RSS"); xml.endTag(null, "title"); xml.endTag(null, "head")
        xml.startTag(null, "body")
        val byId = library.feeds.associateBy { it.id }
        val children = library.folders.groupBy { it.parentId }
        val links = library.links.groupBy { it.folderId }
        val visited = mutableSetOf<Long>()
        fun feed(item: Feed) {
            xml.startTag(null, "outline"); xml.attribute(null, "type", "rss"); xml.attribute(null, "text", item.name)
            xml.attribute(null, "title", item.name); xml.attribute(null, "xmlUrl", item.url); xml.endTag(null, "outline")
        }
        fun folder(item: Folder, depth: Int) {
            require(depth <= 32 && visited.add(item.id)) { "フォルダ階層が深すぎるか循環しています" }
            xml.startTag(null, "outline"); xml.attribute(null, "text", item.name)
            children[item.id].orEmpty().forEach { folder(it, depth + 1) }
            links[item.id].orEmpty().mapNotNull { byId[it.feedId] }.sortedBy { it.name.lowercase() }.forEach(::feed)
            xml.endTag(null, "outline")
        }
        children[null].orEmpty().forEach { folder(it, 1) }
        val classified = library.links.map { it.feedId }.toSet()
        library.feeds.filter { it.id !in classified }.forEach(::feed)
        xml.endTag(null, "body"); xml.endTag(null, "opml"); xml.endDocument(); xml.flush()
    }
    private fun InputStream.readBytesLimited(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val size = read(buffer); if (size < 0) break
            require(out.size() + size <= limit) { "OPMLは2MB以下にしてください" }; out.write(buffer, 0, size)
        }
        return out.toByteArray()
    }
}
