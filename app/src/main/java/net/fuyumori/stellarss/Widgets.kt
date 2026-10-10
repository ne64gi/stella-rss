package net.fuyumori.stellarss

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.*
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.util.SizeF
import android.text.format.DateUtils
import android.view.View
import android.widget.RemoteViews
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

open class RssWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { Updates.render(context); Updates.refresh(context) }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) { Updates.render(context) }
    override fun onDeleted(context: Context, ids: IntArray) {
        val pending = goAsync()
        context.appScope.launch { try { ids.forEach { context.rss.store.deleteWidget(it); context.rss.store.deletePreference("page:$it") } } finally { pending.finish() } }
    }
    override fun onRestored(context: Context, oldIds: IntArray, newIds: IntArray) {
        val pending = goAsync()
        context.appScope.launch {
            try {
                oldIds.zip(newIds).forEach { (old, new) ->
                    context.rss.store.widget(old)?.let { context.rss.store.save(it.copy(id = new)) }
                    context.rss.store.preference("page:$old")?.let { context.rss.store.save(Preference("page:$new", it)) }
                    context.rss.store.deleteWidget(old)
                    context.rss.store.deletePreference("page:$old")
                }
                Widgets.updateAll(context)
            } finally { pending.finish() }
        }
    }
}
class NewsWidget : RssWidget()
class CompactWidget : RssWidget()
class RefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { Updates.refresh(context) }
}
object Widgets {
    private val lock = Mutex()
    fun background(config: WidgetConfig): Int = Color.argb(config.opacity.coerceIn(0, 100) * 255 / 100,
        Color.red(config.background), Color.green(config.background), Color.blue(config.background))
    fun select(rows: List<ArticleRow>, config: WidgetConfig): List<ArticleRow> {
        val selected = config.selectedFeeds()
        return rows.filter { (selected.isEmpty() || it.article.feedId in selected) && (!config.unreadOnly || !it.article.read) }.take(5)
    }
    suspend fun rows(context: Context, config: WidgetConfig): List<ArticleRow> = Organization(context.rss.db).widgetRows(config)
    suspend fun updateAll(context: Context) = withContext(Dispatchers.IO) {
        lock.withLock {
            val manager = AppWidgetManager.getInstance(context)
            for (type in listOf(NewsWidget::class.java, CompactWidget::class.java)) {
                for (id in manager.getAppWidgetIds(ComponentName(context, type))) {
                    val config = context.rss.store.widget(id) ?: WidgetConfig(id)
                    val data = rows(context, config)
                    manager.updateAppWidget(id, build(context, config, data, type == CompactWidget::class.java))
                }
            }
        }
    }
    private fun activity(context: Context, id: Int, action: String, intent: Intent, mutable: Boolean = false): PendingIntent {
        intent.data = Uri.parse("stellarss://widget/$id/$action")
        return PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) {
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            } else PendingIntent.FLAG_IMMUTABLE)
    }
    private fun articleIntent(context: Context, id: Int, row: ArticleRow?): PendingIntent = activity(context, id,
        "article/${row?.article?.id ?: "more"}", Intent(context, OpenArticleActivity::class.java).putExtra("article", row?.article?.id).putExtra("widget", id))
    /** Host-provided dimensions select dense/wide layouts; API 31+ selects exact size variants. */
    suspend fun build(context: Context, config: WidgetConfig, rows: List<ArticleRow>, compact: Boolean,
                      previewSize: SizeF? = null): RemoteViews {
        if (previewSize != null) return render(context, config, rows, compact, previewSize)
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(config.id)
        if (Build.VERSION.SDK_INT >= 31) {
            @Suppress("DEPRECATION")
            val sizes = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                .orEmpty().filter { it.width > 0 && it.height > 0 }.distinct().take(6)
            if (sizes.isNotEmpty()) {
                val variants = linkedMapOf<SizeF, RemoteViews>()
                for (size in sizes) variants[size] = render(context, config, rows, compact, size)
                return RemoteViews(variants)
            }
        }
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: if (compact) 280 else 320
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: if (compact) 120 else 300
        val portrait = render(context, config, rows, compact, SizeF(width.toFloat(), height.toFloat()))
        val wide = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
        val low = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
        return if (wide > 0 && low > 0 && (wide != width || low != height))
            RemoteViews(render(context, config, rows, compact, SizeF(wide.toFloat(), low.toFloat())), portrait) else portrait
    }
    private suspend fun render(context: Context, config: WidgetConfig, rows: List<ArticleRow>, compact: Boolean, size: SizeF): RemoteViews {
        val compactLimit = if (Build.VERSION.SDK_INT >= 31) 136f else if (size.height < 112) 96f else 120f
        val h = if (compact) size.height.coerceAtMost(compactLimit) else size.height
        val small = if (compact) h < 112 else h < 280 || size.width < 280
        val layout = if (compact) { if (small) R.layout.widget_compact_small else R.layout.widget_compact }
            else if (small) R.layout.widget_news_small else R.layout.widget_news
        val views = RemoteViews(context.packageName, layout)
        val radius = when { config.cornerDp <= 0 -> R.drawable.widget_background_0
            config.cornerDp <= 8 -> R.drawable.widget_background_8
            config.cornerDp <= 16 -> R.drawable.widget_background_16
            else -> R.drawable.widget_background_24 }
        views.setImageViewResource(android.R.id.background, radius)
        val border = when { config.cornerDp <= 0 -> R.drawable.widget_border_0
            config.cornerDp <= 8 -> R.drawable.widget_border_8
            config.cornerDp <= 16 -> R.drawable.widget_border_16
            else -> R.drawable.widget_border_24 }
        views.setImageViewResource(R.id.widget_border, border)
        views.setViewVisibility(R.id.widget_border, if (config.opacity in 1..84) View.VISIBLE else View.GONE)
        views.setInt(R.id.widget_border, "setImageAlpha", 50)
        views.setInt(android.R.id.background, "setColorFilter", config.background or 0xFF000000.toInt())
        views.setInt(android.R.id.background, "setImageAlpha", Color.alpha(background(config)))
        val muted = Color.argb(185, Color.red(config.textColor), Color.green(config.textColor), Color.blue(config.textColor))
        val accent = if (Color.red(config.background) + Color.green(config.background) + Color.blue(config.background) > 460)
            0xFF12658C.toInt() else 0xFF83C9ED.toInt()
        val scale = context.resources.configuration.fontScale
        val maxFont = if (compact) (h - 54) / (2.5f * scale)
            else (h - if (small) 137 else 161) / (4 * 1.2f * scale) - 3
        val font = minOf(config.fontSp.toFloat(), maxFont).coerceIn(12f, 24f)
        if (!compact) {
            views.setTextColor(R.id.brand, muted)
            views.setTextColor(R.id.refresh, muted); views.setTextColor(R.id.configure, muted)
            val refresh = Intent(context, RefreshReceiver::class.java).setData(Uri.parse("stellarss://refresh/${config.id}"))
            views.setOnClickPendingIntent(R.id.refresh, PendingIntent.getBroadcast(context, 0, refresh, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            views.setOnClickPendingIntent(R.id.configure, activity(context, config.id, "configure",
                Intent(context, WidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, config.id)))
            val lead = rows.firstOrNull()
            val lineHeight = maxOf(26f, (font - 1) * scale * 1.2f + 4)
            val heroHeight = h - (if (small) 73 else 81) - rows.drop(1).take(4).size * lineHeight
            val metadata = !small && heroHeight > 86 * scale
            views.setViewVisibility(R.id.hero_kicker, if (metadata) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.hero_meta, if (metadata && lead != null) View.VISIBLE else View.GONE)
            views.setTextColor(R.id.hero_kicker, accent); views.setTextColor(R.id.hero_meta, muted)
            views.setTextViewText(R.id.hero_meta, lead?.let { "${it.feedName} · ${DateUtils.getRelativeTimeSpanString(it.article.published, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}" } ?: "")
            views.setTextViewText(R.id.hero_title, lead?.article?.title ?: "フィードを追加して、ニュースをここに。")
            views.setTextColor(R.id.hero_title, config.textColor)
            views.setTextViewTextSize(R.id.hero_title, TypedValue.COMPLEX_UNIT_SP, font)
            views.setInt(R.id.hero_title, "setMaxLines", ((heroHeight - if (metadata) 36 else 8) / (font * scale * 1.25f)).toInt().coerceIn(1, 3))
            views.setViewVisibility(R.id.hero_image, if (config.images && lead?.showImages != false) View.VISIBLE else View.GONE)
            setImage(context, views, R.id.hero_image, lead)
            views.setOnClickPendingIntent(R.id.hero, articleIntent(context, config.id, lead))
            views.setContentDescription(R.id.hero, lead?.article?.title ?: "リーダーを開く")
            views.removeAllViews(R.id.headlines)
            rows.drop(1).take(4).forEachIndexed { index, row ->
                val line = RemoteViews(context.packageName, R.layout.widget_row)
                line.setTextViewText(R.id.row_number, "%02d".format(index + 2))
                line.setTextColor(R.id.row_number, accent)
                line.setTextViewText(R.id.row_title, row.article.title)
                line.setTextColor(R.id.row_title, if (row.article.read) muted else config.textColor)
                line.setTextViewTextSize(R.id.row_title, TypedValue.COMPLEX_UNIT_SP, font - 1)
                line.setInt(R.id.row_title, "setMinHeight", (lineHeight * context.resources.displayMetrics.density).toInt())
                line.setOnClickPendingIntent(R.id.row_root, articleIntent(context, config.id, row))
                line.setContentDescription(R.id.row_root, row.article.title)
                views.addView(R.id.headlines, line)
            }
            views.setTextColor(R.id.more, muted)
            views.setOnClickPendingIntent(R.id.more, articleIntent(context, config.id, null))
        } else {
            if (Build.VERSION.SDK_INT >= 31) views.setViewLayoutHeight(R.id.widget_panel, h, TypedValue.COMPLEX_UNIT_DIP)
            views.removeAllViews(R.id.cards); views.removeAllViews(R.id.widget_pages)
            val page = (context.rss.store.preference("page:${config.id}")?.toIntOrNull() ?: 0).coerceIn(0, rows.size)
            for (position in 0..rows.size) {
                val row = rows.getOrNull(position)
                val card = RemoteViews(context.packageName, if (small) R.layout.widget_card_small else R.layout.widget_card)
                card.setTextColor(R.id.card_title, config.textColor); card.setTextColor(R.id.card_dots, accent)
                card.setTextViewTextSize(R.id.card_title, TypedValue.COMPLEX_UNIT_SP, font)
                card.setTextViewText(R.id.card_title, row?.article?.title ?: "More news ↗")
                card.setTextViewText(R.id.card_dots, "${row?.feedName ?: "STELLA / NEWS"} · ${position + 1}/${rows.size + 1}")
                card.setInt(R.id.card_title, "setMaxLines", ((h - 54) / (font * scale * 1.25f)).toInt().coerceIn(1, 3))
                card.setViewVisibility(R.id.card_image, if (row != null && config.images && row.showImages) View.VISIBLE else View.GONE)
                setImage(context, card, R.id.card_image, row)
                card.setOnClickPendingIntent(R.id.card_root, articleIntent(context, config.id, row))
                card.setContentDescription(R.id.card_root, "${position + 1} / ${rows.size + 1}。${row?.article?.title ?: "More リーダーを開く"}")
                views.addView(R.id.cards, card)
                val dot = RemoteViews(context.packageName, R.layout.widget_page_dot)
                dot.setTextViewText(R.id.page_dot, if (position == page) "━" else "●")
                dot.setTextViewTextSize(R.id.page_dot, TypedValue.COMPLEX_UNIT_SP, if (position == page) 10f else 5f)
                dot.setTextColor(R.id.page_dot, if (position == page) accent else muted)
                dot.setContentDescription(R.id.page_dot, if (row == null) "More ページを表示" else "${position + 1} / ${rows.size + 1} ページを表示")
                val intent = Intent(context, PageReceiver::class.java).setData(Uri.parse("stellarss://page/${config.id}/$position"))
                    .putExtra("widget", config.id).putExtra("page", position)
                dot.setOnClickPendingIntent(R.id.page_dot, PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                views.addView(R.id.widget_pages, dot)
            }
            views.setDisplayedChild(R.id.cards, page)
        }
        return views
    }
    private suspend fun setImage(context: Context, views: RemoteViews, id: Int, row: ArticleRow?) {
        val source = context.rss.images.cached(row?.article?.imageUrl)
        // Square crop and rounded corners are prepared off the main thread. Five thumbnails < 330 KiB.
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val bounds = android.graphics.RectF(0f, 0f, 128f, 128f)
        val clip = android.graphics.Path().apply { addRoundRect(bounds, 12f, 12f, android.graphics.Path.Direction.CW) }
        canvas.clipPath(clip)
        if (source != null) {
            val side = minOf(source.width, source.height)
            val x = (source.width - side) / 2; val y = (source.height - side) / 2
            canvas.drawBitmap(source, android.graphics.Rect(x, y, x + side, y + side), bounds, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG))
        } else context.getDrawable(R.drawable.image_fallback)?.apply { setBounds(0, 0, 128, 128); draw(canvas) }
        views.setImageViewBitmap(id, bitmap)
    }

}
class PageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("widget", -1)
        val page = intent.getIntExtra("page", -1)
        if (page !in 0..5 || AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider != ComponentName(context, CompactWidget::class.java)) return
        WorkManager.getInstance(context).enqueueUniqueWork("page:$id", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<PageWorker>().setInputData(workDataOf("widget" to id, "page" to page)).build())
    }
}
class PageWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getInt("widget", -1)
        if (AppWidgetManager.getInstance(applicationContext).getAppWidgetInfo(id)?.provider != ComponentName(applicationContext, CompactWidget::class.java)) return@withContext Result.success()
        applicationContext.rss.store.save(Preference("page:$id", inputData.getInt("page", 0).coerceIn(0, 5).toString()))
        Widgets.updateAll(applicationContext)
        Result.success()
    }
}
