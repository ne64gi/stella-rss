package net.fuyumori.stellarss.translation

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** ML Kit only: no HTTP translation client and no developer/user API credentials. */
class MlKitTranslation : TranslationProvider {
    private val identifier = LanguageIdentification.getClient()
    private var translator: Translator? = null
    override suspend fun identify(sample: String): String = identifier.identifyLanguage(sample).awaitLocal()
    override suspend fun prepare(source: String, target: String, download: ModelDownload) {
        val from = TranslateLanguage.fromLanguageTag(source) ?: throw TranslationUnavailable("この言語は端末内翻訳に対応していません")
        val to = TranslateLanguage.fromLanguageTag(target) ?: throw TranslationUnavailable("翻訳先の言語に対応していません")
        val available = RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java).awaitLocal().map { it.language }.toSet()
        // English is built into translation; constructing a remote English model is invalid.
        val missing = setOf(from, to) - "en" - available
        if (missing.isNotEmpty() && download == ModelDownload.NONE) throw MissingModels(missing)
        translator?.close(); translator = null
        val client = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())
        translator = client
        if (missing.isNotEmpty()) {
            val conditions = DownloadConditions.Builder().apply { if (download == ModelDownload.WIFI) requireWifi() }.build()
            client.downloadModelIfNeeded(conditions).awaitLocal()
        }
    }
    override suspend fun translate(text: String): String = requireNotNull(translator).translate(text).awaitLocal()
    override fun close() { translator?.close(); translator = null; identifier.close() }
}

private suspend fun <T> Task<T>.awaitLocal(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
