# 2026-10-14 — Foreground headlines and interaction polish

- Displayed headlines are translated sequentially; no unread-wide queue or prefetch. Scroll changes and screen stop cancel pending work. Article and headline caches expire seven days after creation, without extending on read.
- Missing language data no longer removes access to the original-headline display: Translation options offers both original display and explicit model preparation. Title payload updates target only changed rows, not the whole article list.
- **65 JVM tests passed, 0 failures/errors/skips; lint 0 errors / 97 warnings; debug APK build passed**. Regression coverage includes TTL, cancellation, viewport limits, missing-model behavior, and per-row title updates in a 266-row list.
- Debug APK SHA-256: `71d4539b7913e852e312ba3c5b5a8640b54536e422b3a2419696d4895c36530c` (0.3.3/code8). Foreground native interaction, actual translation quality and active-use battery impact remain separate verification; automated results do not establish those outcomes.
- Installed on Sony SOG06/API34 with an in-place update; installed APK hash matches. Subscriptions, containers, widget settings and existing read/bookmark flags were retained. Launch returned `Status: ok`, but the device was locked, so foreground original/translation switching, scrolling, stopping and cache reuse remain unverified.
- This checkpoint publishes source changes only; no version bump, release tag or APK release. Earlier dated entries below describe their own build-time verification limits.

---

# 2026-10-13 — optional reading containers and local translation (unreleased)

- Containers are OFF by default under **⋮ → 拡張機能**. OFF merges the ordinary browsing library without deleting container organization; ON restores selection. Existing Room v1–v4 migrations preserve feeds, folders, memberships, article bodies, read/bookmarked flags and widget appearance into v5.
- Container scoping is applied in SQL before LIMIT for article lists, widgets and bulk-read selection. Tests cover stale feed IDs after moves, saved/unread counts, empty/deleted scopes, folder-branch moves, duplicate OPML rollback, independent per-feed translation overrides and retained widget scope while the extension is OFF.
- Local translation uses ML Kit translate 17.0.3 + bundled language-id 17.0.6. Tests use a fake provider to verify text-only/escaped transformation, structure and code retention, exact-source cache identity, bounded/corrupt cache handling, explicit model consent, Japanese/unknown-language refusal and resource closure on cancellation. These do **not** measure real ML Kit quality or memory.
- Final full check: **57 JVM tests passed, 0 failures/errors/skips; lint 0 errors / 96 warnings; debug and AndroidTest APK builds passed**. The earlier new test incorrectly counted bookmarked articles as ordinary unread; corrected to preserve the existing Saved-inbox semantics. SDK transitive AppCompat lint incorrectly requested `app:tint` in four RemoteViews layouts; narrow `UseAppTint` annotations preserve the required framework `android:tint` without changing appearance. No blanket lint suppression.
- Main APK SHA-256: `e1f355db7c07f563b67fdbd717cfde21f28f8dd52b619b1ae72b5d4eed14fbe5`; 74,101,706 bytes (debug, bundled SDK/native architectures, excluding later language downloads). AndroidTest APK: `1cc274dae57a562bc4fa9f2aef9224df0d1f02adbfb587703f900565111cbe91`.
- **No device installation or native testing in this change.** Still unmeasured: first language-model download, actual offline translation, phone/desktop interaction and accessibility, translation latency, SDK/model storage, RSS process memory and battery. Model downloads are SDK-managed and may continue after the screen stops waiting. No runtime result from old RSS/Shell builds proves these new paths.
- Version remains 0.3.3/code8 until a release is separately requested. Changes are local/uncommitted; no push, tag, Release or Shell runtime change.

---

# 2026-10-11 Stella RSS 0.3.3 — sidebar management and direct folder subscriptions

- Replaced the large feed-card management list with a compact, searchable library tree and a single-subscription detail form. RSS/folder icons, muted counts, separate disclosure arrows, contextual edit/move/multiple-classification/delete, one top add entry; long-press drag retained.
- Folder menu → **このフォルダにフィードを追加**; top **＋** inherits the selected folder. Destination is visible/changeable in the form. Subscription + membership save atomically; an existing URL adds a classification without overwriting settings/articles. A deleted destination cannot leave an orphan subscription.
- Main/test APK builds passed. JVM **43 passed**, lint **0 errors / 81 warnings**. Final APK `3bd9cec7bf421a4f83d5d12736b6e1cca1b1b5820151d699c4a35221e1a200d4` installed with `-r` on Sony SOG06/API34; installed hash matches.
- **LibraryManagementChecks: 2 passed, 27.61s** on the final APK. 60 fixture feeds/scroll, long-name ellipsis, search with ancestor paths/no matches, 3-level hierarchy, disclosure vs selection, direct folder add, selected-folder plus, details, delete cancel/confirm, actual injected long-press drag preserving other memberships, draft/destination across Activity recreation and cancellation without saving. Native tree/search/add/delete screenshots visually reviewed.
- All 17 existing feed settings, 6 folders/17 memberships, preferences and widget117 glass65% configuration retained exactly. All 511 pre-existing article read/bookmark row hashes retained; normal refresh added 6 articles (517 total). One bookmark/one widget retained; Room stays v4. Fixtures cleaned; test APK removed; main retained. No device setting, StellaShell modification, commit or push.
- Initial JVM fixture lacked WorkManager initialization. Native fixtures initially operated a closed drawer and timed out before serialized refresh/deletion finished. Corrected visibility/window synchronization and refresh-aware timeout; retained failed logs separately, not counted as final passes. Confirmed deletes now use app scope (survive Activity recreation) and announce waiting behind refresh.
- Reused unchanged parser/article/Undo/widget evidence; this does not close the earlier full row-swipe regression or lift standard AppWidget horizontal-swipe/OEM/API limits. Save/delete remain serialized behind an ongoing refresh. Private screenshots stay in ignored `verification-local/library-ui/`.

---

# 2026-10-11 Stella RSS 0.3.2 — scoped mark-all-read and separate Saved inbox

- Added toolbar ✓ and overflow “すべて既読にする”. Applies to all matching unread articles in the current feed/folder (including descendants), all feeds, or Saved. No 1,000-row display limit; chunked transactional ID updates; empty folders never become global selections.
- Saved articles no longer appear in ordinary All/Unread/feed/folder lists or their unread counters. Removing Save restores them without changing read status. Widget filtering is unchanged.
- Undo restores only IDs changed by that command; existing read/bookmark state and later arrivals are preserved. Refresh pruning is deferred during the read Undo window. No schema migration (Room remains v4).
- Final APK SHA-256 `6492b0f77440d15eceedad370c968090abbd43ca9aa6ad982b7ef9d14ad86766`, installed with `-r` on Sony SOG06/API34; installed SHA matches. Build **3m41s**, JVM **41 passed**, lint **0 errors / 91 warnings**.
- **BulkReadChecks passed on final APK**: recursive/deduplicated folder scope, pre-read/outside/saved preservation, unread list empty after bulk, Undo after recreation, actual Saved removal menu and inbox return, muted read styling and native screenshot reviewed. 1,205-row / refresh-during-Undo / new arrival behavior covered by Room tests.
- Before/after final install and native tests: 17 subscriptions, 441 articles, 6 folders, 17 memberships, 1 bookmark, 1 widget. Exact row hashes for read/bookmark state, feed settings, organization, widget configuration and preferences preserved. No user-data clear, device settings or StellaShell modifications.
- Gesture regression remains a separate pending check: the test-only window flag update made instrumentation input untouchable; switched to View.keepScreenOn. A default null Typeface is valid normal text, not a styling failure. Saved Undo can restore a row above the retained viewport, so native swipe checks now locate and scroll to the actual article ID before injecting input. Physical `sec_touchscreen` input also collided with injected events. Final test-only build passed (59s), but the full swipe run awaits a quiet device window; mixed runs are **not** reported as all-green. Test APK removed; main app retained.
- Screenshots are ignored local artifacts, not published evidence. Changes are not committed or pushed.

---

# 2026-10-11 Stella RSS 0.3.1 — pull refresh, row actions, Undo, article rendering fix

- Implemented: pull-to-refresh at the top/empty list; left swipe marks read, right swipe saves, without toggling. Bottom Undo restores the last changed field for 8 seconds (accessibility timeout respected), survives Activity recreation, and restores rows in unread selection. Refresh execution/queued states are distinct.
- Fixed the article reader's blanket request denial: the exact current document is served as in-memory HTML, mapped images still use the native cache, and all other resources/navigation remain blocked. No JavaScript or publisher page is loaded automatically.
- Final main APK `810f7ec48ce0b3ab0f791e832189d1e5b2dbff33beaea755099c81005891f48d` installed with `-r` on Sony SOG06/API34. JVM **39 passed**, lint **0 errors / 91 warnings**, debug main/test APK build successful.
- Article UI verified on device: JPCERT/CC (short body), BBC (summary/image), CAPA (full RSS body), Android Developers (Atom content), dark/light/settings, real horizontal next/previous and vertical scrolling, recreation/scroll/order/Back. Two focused native checks passed in 29.833s. Native Chromium text search also proved the actual cached document rendered without fetching original/script URLs. Article-rendering code is identical in the final APK.
- Reader/tree/D&D/OPML/counts, unread selection → reader → Back, and standard widget → reader → explicit original browser flow passed in the earlier 4-success subset on the same reader/repository code before Undo. These are not claimed as a single all-green run: initial fixture assumptions and lock/timing failures were investigated separately.
- Physical list testing found a retained swiped holder staying off-screen with `itemAnimator=null`. Fixed by bounded DefaultItemAnimator change animation plus explicit item rebind, including no-op repeated swipes. **Final row-swipe/Undo/pull regression pending at this checkpoint.**
- Original data retained across both earlier installs: all431 existing article read/bookmark row hashes,16 feed settings,6 folders/16 memberships, widget111's custom glass76%, preferences and Room schema4. No app data clear, device setting change, StellaShell edit, or home-widget rearrangement.
- Private/fixture screenshots remain under ignored `verification-local/`, not this repository's tracked documents. Standard AppWidget horizontal-swipe limitation, glass-without-blur, other OEM/API and elapsed Doze scheduling limitations are unchanged.

---

# 2026-10-10 Stella RSS 0.3.0 — in-app article reading (UI verification pending)

- Implemented: RSS/Atom body/summary persistence, safe HTML reader, horizontal previous/next, stable selection queue, independent display settings, explicit original link.
- JVM 37 passed (Parser15, Repository7, Organization5, Migration3, ArticleReader7); lint 0 errors / 88 warnings. Main and test APK build succeeded. Initial instrumentation compilation error (MotionEvent metaState argument) fixed.
- Installed on SOG06 Android14/API34 with existing app data retained.
- Native backend 3 passed, 99.551s: BBC28 / Atom25 / JPCERT21; actual Room/ETag/304/read/image fallback/cache; production refresh of all 16 existing subscriptions.
- After migration schema3→4 and refresh: 16 feeds, 6 folders, 16 memberships, 418 articles (415 before), 60 with body HTML. All16 feeds have contentVersion1 and no fetch error. Existing feed settings, folder/membership content, widget selection/custom glass appearance, preferences, and all415 old read/bookmark hashes preserved.
- JPCERT has both body-bearing and summary/missing-body entries. DTM currently provides summary only; planned live full-RSS UI coverage uses CAPA, plus Android Developers Atom and BBC summary.
- **Not yet device-verified:** the new WebView's visible article layout/screenshots, physical horizontal swipe, reader settings controls, reader/list back-stack and changed widget→reader→explicit browser route. Device is securely locked; an unlock request is pending. No lock/PIN bypass or device settings change.
- Native UI tests are implemented in ArticleReadingChecks (3), ReaderChecks (1), and DeviceChecks#standardHostRenderingUpdateIndependentSettingsAndGestures (1); these are pending, not passes. Do not reuse older release UI results as validation of this release.
- Final-APK recheck: native JPCERT/production sync **1 passed (34.208s)**. A background refresh while light-idle briefly had DNS/network errors; cached data survived and wake + explicit sync recovered all16 feeds (424 articles after this final refresh). Device settings untouched; cause not proven.
- A final UI-only lifecycle correction makes read-state writes survive quick page-turn/Back (app scope). Backend proof predates that final artifact but its tested parser/repository/schema code is unchanged. Final build/APK status is tracked in local evidence and CURRENT-STATE.

Resume UI validation on an unlocked device:

```sh
adb -s 'DEVICE_IP:ADB_PORT' install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s 'DEVICE_IP:ADB_PORT' shell am instrument -w -e class 'net.fuyumori.stellarss.ArticleReadingChecks,net.fuyumori.stellarss.ReaderChecks,net.fuyumori.stellarss.DeviceChecks#standardHostRenderingUpdateIndependentSettingsAndGestures' net.fuyumori.stellarss.test/androidx.test.runner.AndroidJUnitRunner
```

Review native article screenshots from the app-private `files/article-*.png` under ignored verification-local only. Remove only test-owned fixture data/test APK after validation. Existing user feeds/settings and StellaShell must remain untouched.

---

# 2026-10-10 Stella RSS 0.2.0 — reader / folder tree

APK `5407219f4bad7d9637e327470a62dedb3b765db4f3087270055d4219c3944a00`; installed base SHA identical. Source 53 files fingerprint `51e4ca9b2f672d52b3c0045f835baa9528be252a5b9982463e629dd0b4df8b27`.

- Implemented/built/installed/device verified: all true.
- JVM 28 passed; lint 0 errors / 79 warnings.
- SOG06 Android14/API34: full 5 native tests PASS 17.554s; test-only refinement 2 PASS 13.847s on same main APK.
- Six synthetic rows visible, right72dp image/no-image, no title bounds overflow; physical edge drawer, bookmark, feed/folder drag and recursive widget scope. Real JPCERT screen also reviewed.
- Actual widget source selector changed feed → folder, saved and retained appearance. Standard host responsive/independent settings/updates/More/browser regression passed.
- OPML nested/multiple membership merge and private native file round-trip passed; SAF third-party provider differences not covered.
- Before install: Feed1, Article21, Widget1, Preference1, schema2. After: same counts, schema3, all old values/read hashes match (new folderId excluded from old-widget projection). Own fixture folders/memberships/bookmarks removed; existing widget111 76% glass retained. Earlier work-start snapshot had Feed2/Article60/Widget0; this changed before our install, so preservation compares the immediate pre-install snapshot. No feed removals or widget additions by this task outside our identified fixtures.
- Test APK removed, MainActivity opened. No StellaShell/source settings/data-clear/external sync changes. Screenshots excluded from this evidence.
- Limits: 32 levels, 2MiB OPML, selected newest1000 articles, alphabetical tree order; widget no horizontal swipe and glass no blur unchanged; other OEM/API, elapsed periodic/Doze unverified.

First build failed only lint WrongConstant on line-break constant; fixed before successful build. Native tests all passed on first run; follow-up adds deterministic mixed image fixture and actual widget configuration save.

## Local screenshots

`verification-local/reader-jpcert-installed.png` is the installed public JPCERT list. `reader-dense-device.png` and `reader-tree-device.png` are native fixture captures (synthetic articles / generated test thumbnails); not mock screenshots. They are gitignored and absent from Knowledge/evidence.

---

# 現行版の検証 — 2026-10-10 / Stella RSS 0.1.2 (code 3)

## 実装・導入

- JPCERTのRSS1/RDFを追加対応。rootと同階層のitem、rdf:Seq、rdf:about、dc:date、namespace URI、相対URL、URN識別子を扱い、RSS2/Atomは維持。
- ニュース一覧を小さな左サムネイル＋右最大3行見出し＋4行＋小Moreへ再設計。コンパクトは低い横長カードへ変更し、管理ボタンを撤去。
- ダーク98% / ガラス48%＋細い縁 / 紙 / 森のプリセット、色・透過・文字・角丸0/8/16/24dpをウィジェットごとに設定。0%では縁も非表示。画像と補助情報はサイズに応じて調整。
- Room v1→v2はcornerDp追加と完全一致の旧初期配色だけ移行。独自配色・Feed・既読・表示対象を維持。破壊的migrationなし。
- **Sony SOG06 / Android 14 / API34へ上書きインストール済み**。StellaShell本体・端末設定・既存ホーム配置は変更しない。

## 検証結果

- JVM **22件成功**：Parser15、Repository6、実Room migration1。最終のwidget表示調整で変更していないRSS/Repository/migrationの回帰証跡。
- Android lint **エラー0、警告68**。文字列・小さい管理/indicatorタップ領域、依存推奨等。debug本体とAndroidTestのビルド成功。
- 同一本体APKで実機 **4件成功（10.505秒）**。その後、テストの撮影待ちだけを修正しUI試験を再実施、**1件成功（6.053秒）**。
- 実フィード：BBC RSS2 **39件**、Atom **25件**、JPCERT RDF **21件**。登録済みJPCERTもerror解消、Room保存・時系列・既読保持・widget問い合わせを確認。
- 標準AppWidgetHost：一覧320×300 / 240×240 / 320×360dp、compact280×120 / 200×96 / 320×136dp。文字24sp、透過0/45/48/98/100%、角丸/角形、4行、管理ボタン非表示、更新、ページdot、More→reader→実Firefoxの公開原文表示を確認。
- 導入前Feed2/記事36/Widget0→Feed2/記事60/Widget0。Feed設定・既存記事の既読状態・表示対象・preferenceの照合一致。初期読取ではwidget2だったが、導入直前に作業外で0となっており、こちらは既存widgetを削除していない。独自外観の移行保持は隔離Room試験で確認し、実機で既存配置を移行したとはしない。
- 最新の実機描画を目視確認。長い見出しの末尾省略は仕様であり、行の上下が切れる不具合とは区別する。

## 実機で見つけた問題と修正

- TextView.setHeightがmaxLinesを解除し、1行記事の2行目がはみ出した。wrap_content＋setMinHeightに変更し、1行制限を維持。
- 小サイズの管理アイコンが縦2px分収まらなかったため、16sp＋font paddingなしへ変更。
- RemoteViews再適用の途中はLayoutが未確定になる。試験をUI thread上の可視行・配置完了の検査へ変更。
- サイズ変更直後の空フレーム撮影を検出。実widget階層・alpha・Layoutの準備判定と撮影を同一UI操作にまとめて取り直し、200×96の2行見出しも目視確認。製品を変更せず撮影処理だけ修正した。
- 初期のschema testはindicesを持たないtableのfixture生成で失敗し、optional扱いへ修正。過去の失敗ログはverification-localに保持。

## 残る制約

- 標準AppWidgetは横スワイプによるページ送りを提供しない。今回も実機で非成立を確認し、小さい点のタップを代替とした。左右ボタンなし。
- ガラスは背景透過＋縁の表現。実際の壁紙ぼかしではない。
- 既存の大きな配置枠は勝手に変更しない。compactはその枠内で最大136dpの低いカードを描き、枠そのものを縮める操作はホーム側で行う。
- 専用の標準hostでの確認。StellaShell HOME/Hub/外部ディスプレイへの実配置、他OEM/OS、実時間の定期発火/Dozeは今回の確認外。ランチャーによってサイズ/角丸の扱いは異なる。

## 現行成果物と証跡

- APK: `app/build/outputs/apk/debug/app-debug.apk`
- 本体SHA-256（端末base.apkと一致）: `b13ef68854dfe7ea1975e111119e3613e176ec502a19c8e6bb148a566ec030bc`
- テストAPKのみ撤去済み。本体とデータを保持してMainActivityを起動。
- source inputs 46件のhash: `df304c3a96badd340a8051d48a0277384a5b7188f7e724aa0068540923fe8c9c`
- `verification-local/ui-summary.json` / `ui-source-inputs.json` / `ui-device-final.txt` / `ui-capture-verified.txt`。
- `verification-local/widget-redesign-device.png`: 実機全画面。`widget-redesign-showcase.png`: 同じ実機widget領域の描画。合成ニュース見出し＋参考記事画像であり、実ニュース本文との組合せではない。
- 画像はローカル・Git除外のみ。Knowledge/evidenceには個人画面や画像を保存しない。テスト用Feed/Widget IDだけ清掃。commit/push/外部同期なし。

---

# 初回0.1.0の検証履歴 — 2026-10-10

対象: Stella RSS 0.1.0 / package `net.fuyumori.stellarss`。独立Gradle root `stella-rss/`。

## 確定した範囲

- Kotlin実装、Room schema v1、WorkManager、2種類のAppWidget、個別設定、HTTP条件付き取得、画像cacheを実装。
- `testDebugUnitTest`: **16件成功、失敗/エラー/skip 0**。
- `lintDebug`: **エラー0、警告46**。主に依存更新案内、KTX/KSP推奨、日本語文字列、旧APIで無視される新widget属性、小さいインジケーター。
- debug本体・AndroidTest APKをビルド。SOG06への上書きインストール成功（新規作成したこのアプリのみ）。
- 端末: **Sony SOG06 / Android 14 / API 34**。接続先は公開記録に含めません。

## ローカル試験

RSS/Atom、名前空間、相対URL/xml:base、日付/文字コード、重複、危険なscheme/DTDの拒否、画像候補順序。Roomの既読維持、ETag/Last-Modified/304、解析失敗時のデータとvalidator保持、削除cascadeの範囲、独立widget設定、多数フィードで絞込をLIMIT前に適用、遅れて完了した画像取得による新しい記事の上書き防止。

## 実機試験の内訳

**最終AndroidTest: 3件成功、失敗0（6.843秒）。** 公開HTTPS本文のUI nodeと既読状態まで実機確認。

1. 実ネットワーク: BBC World RSS **35記事**、Android Developers Atom **25記事**の取得・解析。
2. 一時ローカルHTTPサーバー＋隔離Room: 304/validator、既読保持、失敗media→enclosure、og:image、ディスクcache。公開サイトの全画像成功を意味しない。
3. このアプリ専用の標準AppWidgetHost: 画像枠＋トップ記事＋4行＋More、DB更新反映、2個の独立設定（不透明度100% / 45%の実描画を検査）、6ページの切替、More→リーダー、記事→既定Firefoxと既読保存。ブラウザー本文は公開HTTPSの検証用ページで確認済み。
4. WorkManagerの定期Work登録と通信接続制約。実時間15分/60分経過やDoze解除後の周期発火は未観測。

テストは自分のwidget IDと合成フィードだけを削除し、既存HOME配置を変更しない。BIND_APPWIDGETはUiAutomationの一時的なshell permission identityでテストホストをbindし、直後にdrop。端末のbind許可設定を書き換えていない。

## 初回失敗と修正

- unit用AndroidX test-coreの依存不足を追加。
- API26非対応のテーマ属性を除去。画像fallback vectorのサイズ警告も修正。
- StackView版は、透過時に背面カードの文字が重なり、実機の縦送り試験も失敗。単一カードのViewFlipper＋小さなインジケーターのタップへ変更。
- ブラウザー起動の前面化をActivityのRESUMED以後＋NEW_TASKに修正。Firefox仮想nodeがplatformの文字検索で検出できないため、実UI tree読取りで本文の実在を確認し、試験の判定を限定的なnode走査へ変更。
- 初回失敗ログを消さず、`verification-local/`（gitignored）へ保存。過去の失敗を最終成功と混同しない。

## 仕様上の制約・未確認

- **横スワイプは標準AppWidgetで非対応**。実機でも横操作によるページ遷移なし。製品版の代替は**小さな点のタップ**。左右ボタン・自動送り・縦送りはない。最新5件＋More（5件未満は実在記事＋More）。
- 標準ホストのテストであり、既存StellaShellのHOME/Hub/外部モニターへの配置や、他OEMランチャーの個別試験ではない。StellaShell本体の改造はしていない。
- 対応下限API26〜33、API35以降、全画面密度/極端なリサイズ、実時間の周期更新、認証/有料壁/画像アクセス制限のある全サイトは未検証。
- 背景サイズはホームの長押しリサイズ。大きな文字には十分な高さが必要。
- 開発署名APK。commit/push/公開配布はしていない。

## 証跡

`verification-local/`: buildログ、JUnit集計、lint XML、instrumentation履歴、ソース入力hash、APK hash、合成画面だけのView描画画像。個人ブラウザー画面や資格情報をKnowledgeには保存しない。

## 0.1.0当時の成果物

- APK: `app/build/outputs/apk/debug/app-debug.apk`
- SHA-256: `9516f469e4b34ee1254864ff8c2181770b4933c4c3cb57f72777bd0fb2a174d4`
- 端末のインストール済み `base.apk` と同一hash。
- テストAPK `net.fuyumori.stellarss.test` のみアンインストール済み。本体アプリは保持し、通常のMainActivityを起動。
- ソース入力hash: `94a6e69ff22b4fd63fefd846af05ec45f77dcc21ac256c00e395de98a90d7bfd`（`verification-local/source-inputs.json`）。
