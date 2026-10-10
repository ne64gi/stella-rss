# 設計と制約

## 所有境界

- `FeedParser.kt`: 名前空間対応Pull Parser。RSS 1.0（RDF）/ RSS 2.0 / Atom、相対URL・xml:base・XHTML画像・日付を正規化。RSS1ではchannelと同階層のitemを読み、rdf:Seq順・rdf:aboutによる識別・dc:dateを扱う。RDF/RSS1の名前空間URIで識別し、無関係なRDFは受理しない。DTD拒否、XML 4 MiB、階層48、ノード30000、取得ごとに250記事まで。
- `Database.kt`: Room schema v4のFeed / Article / WidgetConfig / Preference / Folder / FolderFeed。v1→v2でcornerDpを追加し、独自設定を保持。完全一致の旧factory配色のみ新defaultに移行。記事IDはフィードID＋GUID/Atom ID/rdf:about/記事URLのSHA-256。Feed削除時のみその記事をcascade。記事内容の更新で既読状態を上書きしない。
- `Organization.kt`: 階層folderの循環・32階層制限、多対多membership、再帰scopeのfeed ID集合でdedup。DB transactionで分類・移動・OPML mergeを原子的に実行。folder削除は分類だけcascade、Feedは残す。widget.folderIdは削除後も意図を残して空表示（FKによるNULL→全件への切替をしない）。v2→v3は既存カラムを変更せずカラム・テーブルを追加（articles.bookmarked、widgets.folderId、folders/junction）。ブックマーク済み記事はread trimmingから除外。
- `MainActivity.kt` / `LibraryDrawer.kt`: 高密度RecyclerView、AndroidX DrawerLayoutの左端操作、標準startDragAndDrop。記事は条件を適用してからLIMIT1000、未読集計は全記事。drawerの再生成はドラッグ中保留して対象Viewのdetachを防ぐ。親範囲は子孫全Feedのunionなので複数所属による二重表示・二重件数なし。
- `Opml.kt`: OPML2 nested outline/xmlUrl。2MiB/ノード10000/32階層、DTD・危険URL拒否、完全parse後に1transactionでimport。同URLは既存Feedを使い表示設定・既読等を保持。Exportは全serialize成功後にSAFの選択先を開く。外部includeの取得・ストレージ権限要求なし。
- `Repository.kt`: フィード変更と同期の排他制御、ETag / Last-Modified、304、失敗時の既存記事保持、既読変更。URL変更時はそのフィードだけ記事とvalidatorをリセット。
- `Network.kt`: OkHttpタイムアウト・応答上限。HTTP(S)のみ、埋込資格情報拒否。HTTPSからHTTPへのリダイレクト禁止。HTTP専用RSSを利用するためcleartext対応。
- `Images`: private disk LRUキャッシュ64 MiB、画像入力6 MiB、decode最大辺960px以下。ウィジェットには256×144px以内に比率を維持して縮小したbitmapのみを送信。ディスクはOSによる削除対象。
- WorkManager: 通信接続制約付きperiodic/manual取得、ネットワーク不要の描画Work。定期初期60分、最短15分。購読中のフィードが無ければperiodicを取消。手動と定期の重複実行はRepositoryのMutexで直列化。
- Activity: lifecycle coroutine / RecyclerView。ネットワーク・XML・画像処理・DBはIO上。安全化したRSS本文をWebViewで表示し、明示操作時のみHTTP(S)元記事を既定ブラウザーで開く。
- `Widgets.kt`: 標準AppWidgetProvider / RemoteViews。コンパクトはViewFlipper＋インジケーターのPendingIntentで切替。各appWidgetIdに独立したRoom設定。設定変更、記事更新、既読変更、削除、リサイズ、ID復元で再描画。

## 画像取得の順序

1. 名前空間URIで識別するmedia:content / media:thumbnail（画像）
2. RSS enclosure / Atom link rel=enclosure（画像）
3. content:encoded / Atom content のimg、次にdescription / summaryのimg
4. 記事ページのog:image
5. ローカルvectorフォールバック

先の候補が404、非画像、decode失敗なら後の候補へ進みます。直近記事を先行cacheし、表示された記事はオンデマンド取得。ネットワーク・電池消費を抑えるため先行画像取得は全フィード合計10記事・60秒の協調キャンセル予算（進行中HTTPは最大call timeoutまで）とし、画像無しの再探索は6時間抑制します。フィード設定の画像OFFはそのフィードの先行取得とリーダー表示を停止します。

## 横スワイプの調査結果

Android公式のAppWidget overviewは、ホームの横ページ移動との競合により、利用可能gestureをタップと縦スワイプに限定しています。RemoteViewsの許可ViewにViewPager/RecyclerView/custom Viewは含まれず、アプリ側のtouch listenerをホームへ渡すこともできません。

- https://developer.android.com/develop/ui/views/appwidgets/overview#limitations
- https://developer.android.com/reference/android/widget/RemoteViews
- https://developer.android.com/develop/ui/views/appwidgets/collections

代替採用: ViewFlipperで1枚ずつ表示し、小さいページインジケーターのタップで切り替える。左右ボタン・自動ページ送りは設置しない。6ページ目More（5記事ある場合）、下部に小さな位置インジケーター。5記事未満では空の架空ページを増やさない。

検討したStackView版はSOG06でカードが扇状に重なり、透明背景では背面の文字が透け、縦スワイプ試験も成立しませんでした。初回失敗を記録した上で単一カードのViewFlipper方式に変更。縦スワイプやスナップスクロールも製品機能とはしていません。

横スワイプを必須とするなら、通常のActivity内pager、または独自ランチャーとの専用連携が必要です。後者は標準AppWidget互換を越えるため実装していません。StellaShell本体の改造なし。

## 残る境界

- ウィジェットサイズ・配置・タッチ仲裁・電池最適化はホーム／OS／OEMにも依存。独立テストホストの成功を全ランチャー互換やStellaShellの全配置モードの成功には広げない。
- 有料壁・認証必須フィード、JavaScript実行でのみ得られる画像、全サイトのbot制限は対応保証外。画像取得失敗は記事本文の取得失敗とは別扱い。
- 記事一覧は最新1000件を表示。widgetは対象フィード／未読条件でDB絞込後に最新5件を選択、1取得250記事まで。各フィードの最新500件を越えた既読記事を整理し、未読は勝手に消さない。
- 記事状態のバックアップ転送、検索、サイト全文の抽出、公開配布署名は範囲外（購読OPMLと保存済みRSS本文のオフライン閲覧は対応）。

## UI再設計（0.1.2）

- 余白8〜10dp、見出し15sp基準、補助記事14sp。newsは左64/80dpの固定サムネイル、右最大3行の見出し、4件の番号付き1行、控えめな更新/設定/More。
- compactは標準260×120dp、最小200×96dp。見た目の高さは最大136dpに抑える。既存ホーム上の配置枠は勝手に変更せず、余った枠の中で中央に小カードを表示する。既存の大きい枠そのものを縮めるにはホームのリサイズ操作が必要。
- API31以降はホストのOPTION_APPWIDGET_SIZESからRemoteViews size map、従来APIは縦横RemoteViewsとoptions変更通知。小サイズでは余白・画像・補助情報・行数・文字サイズを調整。長い見出しは末尾省略し、全文はタップ先で表示。
- 背景はGradientDrawable resourceをImageViewでtint＋imageAlpha。角丸0/8/16/24dpは任意配色・背景透過と両立し、本文は透過させない。ガラスpresetは48%＋薄い縁。壁紙の実blurは標準AppWidget内で実装しない。
- サムネイルはIOで128px四方へcrop・角丸化。元画像cacheを変更せず、RemoteViewsへ渡す各ページbitmapを小さく保つ。

## フォルダ・ドロワーの参照仕様

- [AndroidX DrawerLayout](https://developer.android.com/reference/androidx/drawerlayout/widget/DrawerLayout)
- [標準Viewのドラッグ＆ドロップ](https://developer.android.com/develop/ui/views/touch-and-input/drag-drop/view)
- [OPML 2.0 specification](https://opml.org/spec2.opml)

本体Activityの左スワイプとAppWidgetの横スワイプは別機構です。前者にはDrawerLayoutを使用し、後者の標準RemoteViews制約は変更しません。

## アプリ内記事画面（0.3.0）

- Room v3→v4は記事bodyHtml/summaryHtml/bodyTruncated、Feed.contentVersionを追加するだけ。Feedを初めて本文対応で取得する時はvalidatorsを送らず、成功200のtransaction後にversion=1。以後ETag/Last-Modified/304を維持。旧記事がもう配信されていなければ既存概要を使い、存在しない本文は補作しない。
- FeedParserはRSS本文と概要を分離、Atom text/html/xhtmlを区別。XHTML混在テキストの順序とxml:baseを維持。外部srcのAtom contentを自動取得しない。HTML各256Ki文字、超過は表示フラグ。保存時と表示時の二段階でJsoup許可リストを適用。
- ArticleHtmlはタグ/属性/HTTP(S)を限定。script/style/frame/object/svg/math/form/media/meta/base等を除去。配信HTMLのスタイルを使わず、アプリ自身のCSSだけで描画。CSPはdefault-src none / script-src none、画像は専用https originのみ。
- OpenArticleActivityはJS/DOM storage/file/content access/外部windowを無効。全requestをinterceptし、ページごとのUUID付き画像mapにあるURLだけImages cacheへ委譲（worker thread、入力上限・bitmap化してWebViewへ返す）。未知resourceは403。cookie付きWebViewで外部ページを読む経路はない。リンクはmainFrame＋user gestureから確認、原文はnative buttonで明示外部起動。
- 読む順序は一覧最大1000件のID snapshot（HTMLをIntentに入れない）。widgetからはそのwidgetの5件、Moreは一覧へ。既読化で未読queueを縮めず、削除された記事は欠落を明示。前後端は停止、縦scroll/長押し/multitouchは横送りと区別。recreationはqueue/current/scrollを復元。
- 一覧とwidgetのRoom queryは本文HTMLをprojectしない。1記事を開く時だけ本文を読み込み、sanitization/renderをDefault dispatcherへ。表示設定はPreference(reader_display)でwidget外観・旧Preferenceと独立。
- 本体の本文抽出は未実装。既存のog:image画像探索のみ従来どおり。キャッシュを失った画像、配信から消えた旧記事の全文、Web埋込/動画/スクリプト前提の表現は復元しない。

参照: [Android WebView URI loading](https://developer.android.com/privacy-and-security/risks/unsafe-uri-loading)、[file inclusion](https://developer.android.com/privacy-and-security/risks/webview-unsafe-file-inclusion)、[jsoup Safelist](https://jsoup.org/apidocs/org/jsoup/safety/Safelist.html)。
