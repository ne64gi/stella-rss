# リポジトリの独立

2026-10-13、StellaShell配下の`stella-rss/`を独立Gitリポジトリへ切り出しました。

- 公開先：[ne64gi/stella-rss](https://github.com/ne64gi/stella-rss)（`main`）
- 元リポジトリ：[ne64gi/stellashell](https://github.com/ne64gi/stellashell)
- 切り出し時点：`2b8ac6533bf68be033313a6b7af304da49ef294b`
- RSSの公開済み元コミット：`9869a44d6260c5b3104978c885f4b8cb219a1b9c`
- `git subtree split --prefix=stella-rss`で得た独立履歴：`883336bc1116c222d89048e6c23cee915b3bdd13`

RSS部分の履歴・著者・日時を引き継ぎ、StellaShell固有のファイルや履歴は新しいリポジトリへ持ち込みません。元リポジトリの公開履歴も書き換えません。

StellaShellから継承していた`.gitattributes`、Git除外ルール、GPL-3.0-or-laterのライセンス本文を独立ルートへ引き継いでいます。第三者依存ライブラリのライセンスを変更するものではありません。

アプリID、バージョン、データベース、AppWidget ID、署名設定はこの切り出しでは変更しません。切り出し時の未コミット変更は独立リポジトリ側へ引き継ぎ、ローカル検証資料はGit除外のまま保持します。データ移行や端末の再インストールは不要です。

## 開発上の境界

- このリポジトリ単独のGradle Wrapper・設定・CIでビルドします。
- StellaShell側にはRSSのGradle includeやsubmoduleを追加しません。
- 連携はAndroid標準のAppWidgetを通します。
- 独立リポジトリへのソース公開と、GitHub ReleasesでのAPK配布は別に扱います。この切り出しではAPK配布版を作成しません。

切り出し以前の`docs/VERIFICATION.md`などは当時の記録です。過去の`stella-rss/`という表記は、このリポジトリのルートに読み替えてください。
