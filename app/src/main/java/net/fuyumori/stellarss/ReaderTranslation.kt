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
import kotlinx.coroutines.*
import net.fuyumori.stellarss.translation.*
import java.util.Locale

/** Foreground reader session: one job, no observers, no background translation queue. */
class ReaderTranslation(private val screen: Screen, private val render: suspend (TranslatedArticle?) -> Unit) {
    private val engine = TranslationEngine(TranslationStores(screen).articles, ::MlKitTranslation)
    private var source: ArticleHtml.Source? = null
    private var job: Job? = null
    private var generation = 0
    private var dialog: AlertDialog? = null
    var result: TranslatedArticle? = null
        private set
    private val action = screen.label("Googleで翻訳", 13f, ACCENT).apply {
        id = R.id.reading_translate; gravity = Gravity.CENTER_VERTICAL
        minHeight = screen.dp(48); isClickable = true; isFocusable = true
        setOnClickListener {
            when {
                job != null -> stop()
                result != null -> {
                    val ticket = generation
                    job = screen.lifecycleScope.launch {
                        try { render(null); ensureActive(); if (ticket == generation) result = null }
                        finally { if (ticket == generation) { job = null; update() } }
                    }
                }
                else -> start(false)
            }
        }
    }
    private val badge = ImageView(screen).apply {
        contentDescription = "Powered by Google Translate"
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageResource(R.drawable.google_translate_white)
        isClickable = true; isFocusable = true
        setOnClickListener {
            try { screen.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://translate.google.com/"))) }
            catch (_: Exception) { screen.toast("ブラウザーが見つかりません") }
        }
    }
    val view = screen.row().apply {
        setPadding(screen.dp(16), 0, screen.dp(16), 0); gravity = Gravity.CENTER_VERTICAL
        addView(action, LinearLayout.LayoutParams(0, screen.dp(48), 1f))
        addView(badge, LinearLayout.LayoutParams(screen.dp(176), screen.dp(48)))
    }
    init { update() }
    fun bind(value: ArticleHtml.Source, automatic: Boolean) {
        clear(); source = value; update()
        if (automatic) start(true)
    }
    fun clear() { stop(); source = null; result = null; update() }
    fun stop() {
        generation++; job?.cancel(); job = null
        dialog?.dismiss(); dialog = null; update()
    }
    fun theme(light: Boolean) {
        action.setTextColor(if (light) android.graphics.Color.parseColor("#166681") else ACCENT)
        badge.setImageResource(if (light) R.drawable.google_translate_color else R.drawable.google_translate_white)
    }
    private fun update() {
        action.isEnabled = source != null
        action.text = when { job != null -> "翻訳を中止"; result != null -> "原文に戻す"; else -> "Googleで翻訳" }
        badge.visibility = if (result == null) View.GONE else View.VISIBLE
    }
    private fun start(automatic: Boolean, download: ModelDownload = ModelDownload.NONE) {
        val input = source ?: return
        if (!screen.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        stop()
        val ticket = generation
        job = screen.lifecycleScope.launch {
            try {
                val translated = withContext(Dispatchers.IO) { engine.translate(input, download) }
                ensureActive()
                if (ticket != generation) return@launch
                render(translated)
                ensureActive()
                if (ticket == generation) result = translated
            } catch (e: CancellationException) { throw e }
            catch (e: MissingModels) {
                // Automatic article-open translation must never initiate a model download or a modal prompt.
                if (!automatic && ticket == generation) offerModels(e.languages)
            } catch (e: TranslationUnavailable) {
                if (!automatic && ticket == generation) screen.toast(e.message ?: "翻訳できませんでした")
            } catch (_: Exception) {
                if (!automatic && ticket == generation) screen.toast("翻訳できませんでした。言語データの取得時は通信状態もご確認ください。")
            } finally {
                if (ticket == generation) { job = null; update() }
            }
        }
        update()
    }
    private fun offerModels(languages: Set<String>) {
        val names = languages.sorted().joinToString("・") { Locale.forLanguageTag(it).getDisplayLanguage(Locale.JAPANESE) }
        dialog = AlertDialog.Builder(screen).setTitle("端末内翻訳を準備")
            .setMessage("$names の言語データをダウンロードします（1言語あたり約30MB）。取得後は記事の文章を送信せず、この端末でGoogleの翻訳を利用できます。\n\n翻訳を中止しても、開始済みのダウンロードは続く場合があります。")
            .setPositiveButton("Wi-Fiで取得") { _, _ -> start(false, ModelDownload.WIFI) }
            .setNeutralButton("モバイル通信も許可") { _, _ -> start(false, ModelDownload.ANY_NETWORK) }
            .setNegativeButton("今はしない", null).create().also { it.show() }
    }
}
