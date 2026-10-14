package net.fuyumori.stellarss

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import net.fuyumori.stellarss.translation.*
import java.util.Locale

/** Scroll/layout events drive a single cancellable viewport batch. No unread queue or polling. */
class HeadlineTranslation(private val screen: Screen, private val list: RecyclerView,
                          private val rows: () -> List<ArticleRow>,
                          private val show: (Map<String, String>) -> Unit) {
    private data class Request(val items: List<HeadlineSource> = emptyList(), val original: Boolean = false,
                               val download: ModelDownload = ModelDownload.NONE, val revision: Int = 0)
    private val requests = MutableStateFlow(Request())
    private val translator = HeadlineTranslator(TranslationStores(screen).headlines, ::MlKitTranslation)
    private var eligible = emptySet<Long>()
    private var original = false
    private var missing = emptySet<String>()
    private var dialog: AlertDialog? = null
    private val action = screen.label("見出しを原文に", 12f, ACCENT).apply {
        gravity = Gravity.CENTER_VERTICAL; isClickable = true; isFocusable = true
        setOnClickListener {
            if (missing.isNotEmpty() && !original) offerOptions()
            else { original = !original; refresh() }
        }
    }
    private val badge = ImageView(screen).apply {
        contentDescription = "Powered by Google Translate"; scaleType = ImageView.ScaleType.FIT_CENTER
        setImageResource(R.drawable.google_translate_white); visibility = View.GONE
        setOnClickListener {
            try { screen.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://translate.google.com/"))) }
            catch (_: Exception) { screen.toast("ブラウザーが見つかりません") }
        }
    }
    val view = screen.row().apply {
        visibility = View.GONE; gravity = Gravity.CENTER_VERTICAL; setPadding(screen.dp(16), 0, screen.dp(16), 0)
        addView(action, LinearLayout.LayoutParams(0, screen.dp(48), 1f))
        addView(badge, LinearLayout.LayoutParams(screen.dp(154), screen.dp(32)))
    }
    init {
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) { refresh() }
        })
        list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refresh() }
        screen.lifecycleScope.launch {
            screen.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    requests.collectLatest { request ->
                        missing = emptySet(); action.text = if (request.original) "見出しを日本語に" else "見出しを原文に"
                        if (request.original || request.items.isEmpty()) { show(emptyMap()); badge.visibility = View.GONE; return@collectLatest }
                        // Debounce only scroll/layout events; no work is scheduled while idle.
                        delay(200)
                        val translated = linkedMapOf<String, String>()
                        val result = withContext(Dispatchers.IO) {
                            translator.translate(request.items, request.download) { id, title ->
                                withContext(Dispatchers.Main.immediate) {
                                    translated[id] = title; show(translated.toMap())
                                    badge.visibility = if (request.items.any { translated[it.id]?.let { title -> title != it.title } == true }) View.VISIBLE else View.GONE
                                }
                            }
                        }
                        show(result.titles)
                        badge.visibility = if (request.items.any { result.titles[it.id]?.let { title -> title != it.title } == true }) View.VISIBLE else View.GONE
                        missing = result.missingModels
                        if (missing.isNotEmpty()) action.text = "翻訳オプション ▾"
                    }
                } finally { dialog?.dismiss(); dialog = null }
            }
        }
    }
    fun eligibleFeeds(ids: Set<Long>) {
        if (ids == eligible) return
        eligible = ids; show(emptyMap()); badge.visibility = View.GONE
        view.visibility = if (ids.isEmpty()) View.GONE else View.VISIBLE
        refresh(force = true)
    }
    fun refresh(force: Boolean = false) {
        val manager = list.layoutManager as? LinearLayoutManager ?: return
        val values = rows()
        val first = manager.findFirstVisibleItemPosition()
        val last = manager.findLastVisibleItemPosition()
        val items = visibleSources(values, first, last, eligible)
        val old = requests.value
        if (force || items != old.items || original != old.original)
            requests.value = Request(items, original, revision = old.revision + 1)
    }
    private fun offerOptions() {
        dialog?.dismiss()
        dialog = AlertDialog.Builder(screen).setTitle("見出しの翻訳")
            .setItems(arrayOf("見出しを原文に", "言語データを取得")) { _, which ->
                if (which == 0) { original = true; refresh() } else offerModels()
            }.setNegativeButton("閉じる", null).create().also { it.show() }
    }
    private fun offerModels() {
        val names = missing.sorted().joinToString("・") { Locale.forLanguageTag(it).getDisplayLanguage(Locale.JAPANESE) }
        fun prepare(download: ModelDownload) {
            val old = requests.value
            requests.value = old.copy(download = download, revision = old.revision + 1)
        }
        dialog = AlertDialog.Builder(screen).setTitle("見出しの翻訳を準備")
            .setMessage("$names の言語データをダウンロードします（1言語あたり約30MB）。取得後は表示中の見出しだけを端末内で翻訳します。\n\n画面を閉じても、開始済みの言語データ取得は続く場合があります。")
            .setPositiveButton("Wi-Fiで取得") { _, _ -> prepare(ModelDownload.WIFI) }
            .setNeutralButton("モバイル通信も許可") { _, _ -> prepare(ModelDownload.ANY_NETWORK) }
            .setNegativeButton("今はしない", null).create().also { it.show() }
    }
    companion object {
        internal fun visibleSources(rows: List<ArticleRow>, first: Int, last: Int, eligible: Set<Long>): List<HeadlineSource> {
            if (first !in rows.indices || last < first) return emptyList()
            return (first..minOf(last, rows.lastIndex, first + HeadlineTranslator.MAX_VISIBLE - 1))
                .map { rows[it] }.filter { it.article.feedId in eligible }.map {
                    HeadlineSource(it.article.id, it.article.title, it.article.title + "\n" + it.article.summary.take(1500))
                }
        }
    }
}
