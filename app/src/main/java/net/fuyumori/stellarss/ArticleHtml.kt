package net.fuyumori.stellarss

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Safelist

/** A bounded, structural allowlist. Raw publisher markup is never sent to a WebView. */
object ArticleHtml {
    const val MAX_HTML = 256 * 1024
    const val ORIGIN = "https://reader.stellarss.invalid/"
    private fun rules() = Safelist.none()
        .addTags("p", "br", "div", "span", "h1", "h2", "h3", "h4", "h5", "h6", "strong", "b", "em", "i", "u", "s",
            "ul", "ol", "li", "blockquote", "pre", "code", "hr", "a", "img", "figure", "figcaption", "table", "thead", "tbody", "tr", "th", "td", "sup", "sub", "dl", "dt", "dd")
        .addAttributes("a", "href", "title").addAttributes("img", "src", "alt", "title")
        .addProtocols("a", "href", "http", "https").addProtocols("img", "src", "http", "https")
    fun text(value: String): String = Element("span").text(value).html()
    fun sanitize(html: String, base: String): String {
        val doc = Jsoup.parseBodyFragment(html.take(MAX_HTML), base)
        doc.select("script,style,iframe,frame,frameset,object,embed,svg,math,form,input,button,textarea,select,meta,link,base,template,noscript,video,audio,source").remove()
        doc.select("a[href],img").forEach { node ->
            val key = if (node.tagName() == "img") "src" else "href"
            val raw = node.attr(key).ifBlank { if (key == "src") node.attr("data-src") else "" }
            val safe = webUrl(raw, base)
            if (safe == null) { if (key == "src") node.remove() else node.removeAttr(key) }
            else node.attr(key, safe)
        }
        val clean = Jsoup.clean(doc.body().html(), base, rules(), Document.OutputSettings().prettyPrint(false))
        return clean
    }
    fun hasContent(html: String) = Jsoup.parseBodyFragment(html).let { it.text().isNotBlank() || it.select("img").isNotEmpty() }
    data class Page(val html: String, val images: Map<String, String>, val notice: String)
    fun page(row: ArticleRow, settings: ReaderSettings, date: String, token: String): Page {
        val a = row.article
        val body = sanitize(a.bodyHtml, a.url)
        val summary = sanitize(a.summaryHtml, a.url).ifBlank { if (a.summary.isBlank()) "" else "<p>${text(a.summary)}</p>" }
        val hasBody = hasContent(body)
        val usingBody = hasBody && (!settings.summaryFirst || !hasContent(summary))
        val selected = if (usingBody) body else summary
        val notice = when {
            !hasContent(selected) -> "本文・概要が配信されていません。元記事でご確認ください。"
            !usingBody -> "RSSの概要を表示しています。続きは元記事でご確認ください。"
            Jsoup.parseBodyFragment(body).text().length < 400 -> "RSSに含まれる短い本文を表示しています。続きは元記事でご確認ください。"
            else -> "RSSに含まれる本文を表示しています。全文とは限りません。"
        } + if (a.bodyTruncated) " 長い配信内容のため、保存できた範囲を表示しています。" else ""
        val doc = Jsoup.parseBodyFragment(selected)
        if (settings.images && row.showImages) {
            val hero = webUrl(a.imageUrl) ?: a.imageCandidates.lines().firstNotNullOfOrNull { webUrl(it) }
            if (hero != null && doc.select("img").none { it.attr("src") == hero }) doc.body().prependElement("figure").appendElement("img").attr("src", hero).attr("alt", "記事の代表画像")
        } else doc.select("img").remove()
        val imageMap = linkedMapOf<String, String>()
        doc.select("img").forEachIndexed { i, img ->
            if (i >= 40) img.remove() else {
                val remote = img.attr("src")
                val local = "${ORIGIN}image/$token/$i"
                imageMap[local] = remote
                img.attr("src", local)
            }
        }
        val bg = if (settings.light) "#FAFAF7" else "#0B1220"
        val ink = if (settings.light) "#202936" else "#E9EEF5"
        val muted = if (settings.light) "#5A6675" else "#A5B4C8"
        val accent = if (settings.light) "#166681" else "#9BD6EC"
        val divider = if (settings.light) "#D9DFE5" else "#293448"
        val html = """<!doctype html><html lang="ja"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src $ORIGIN; style-src 'unsafe-inline'; script-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; connect-src 'none'">
            <style>html{background:$bg;color:$ink}body{font-family:system-ui,sans-serif;font-size:${settings.fontSp}px;line-height:${settings.lineHeight};padding:${settings.margin}px;margin:0 auto;max-width:760px;overflow-wrap:anywhere}h1{font-size:1.4em;line-height:1.4;margin:.6em 0 1em}h2{font-size:1.2em}h3,h4,h5,h6{font-size:1.05em}p{margin:.8em 0}a{color:$accent}img{display:block;max-width:100%;height:auto;max-height:380px;object-fit:contain;margin:1em auto;border-radius:8px}figure{margin:0}figcaption,.meta,.notice{font-size:.76em;color:$muted}blockquote{border-left:3px solid $accent;padding-left:1em;margin:1em 0}pre{white-space:pre-wrap}table{display:block;overflow-x:auto;border-collapse:collapse}td,th{border:1px solid $divider;padding:.4em}hr{border:0;border-top:1px solid $divider}.notice{border-top:1px solid $divider;padding-top:1em;margin-top:2em}ul,ol{padding-left:1.5em}</style></head>
            <body><div class="meta">${text(row.feedName)} · ${text(date)}</div><h1>${text(a.title)}</h1><main>${doc.body().html()}</main><p class="notice">${text(notice)}</p></body></html>""".trimIndent()
        return Page(html, imageMap, notice)
    }
}

data class ReaderSettings(val light: Boolean = false, val fontSp: Int = 18, val lineHeight: Float = 1.7f,
                          val margin: Int = 20, val images: Boolean = true, val summaryFirst: Boolean = false) {
    fun encode() = listOf(light, fontSp, lineHeight, margin, images, summaryFirst).joinToString("|")
    companion object {
        const val KEY = "reader_display"
        fun decode(value: String?): ReaderSettings {
            val parts = value?.split('|').orEmpty()
            return ReaderSettings(parts.getOrNull(0) == "true", parts.getOrNull(1)?.toIntOrNull()?.coerceIn(14, 28) ?: 18,
                parts.getOrNull(2)?.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(1.3f, 2.2f) ?: 1.7f,
                parts.getOrNull(3)?.toIntOrNull()?.coerceIn(8, 36) ?: 20, parts.getOrNull(4) != "false", parts.getOrNull(5) == "true")
        }
    }
}
