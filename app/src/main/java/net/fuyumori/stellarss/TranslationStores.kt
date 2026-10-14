package net.fuyumori.stellarss

import android.content.Context
import net.fuyumori.stellarss.translation.TranslationCache
import java.io.File

/** Originals live in Room; both translation stores are disposable, seven-day derived data. */
class TranslationStores(context: Context) {
    val articles = TranslationCache(File(context.cacheDir, "translations"))
    val headlines = TranslationCache(File(context.cacheDir, "headline-translations"), 512, 2L * 1024 * 1024)
    fun prune() { articles.prune(); headlines.prune() }
}
