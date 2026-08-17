# 初回ダウンロード

ここでは、最も一般的な方法で最初の作品をダウンロードします。

## 前提

- PixivDownloader がインストールされ、起動している
- [初回セットアップ](/ja/first-setup) が完了している

## 初回アクセス時の自動ガイド

管理者が初めてダウンロードページを開くと、名前の入力、Pixiv への接続確認、Cookie と各ダウンロードモードの説明、作品のキュー追加、フィルター・設定の確認、ダウンロード、ギャラリー閲覧までを横断的に案内します。いつでもスキップでき、ダウンロードページ右下の「ガイド」から再実行できます。マルチモードのゲストには表示されません。

入力した名前はギャラリーのユーザーカードとシステムメールの挨拶に使われます。空欄の場合は「administrator」になります。

## 1. ダウンロードページを開く

```text
http://localhost:6999/pixiv-batch.html
```

ソロモードでは、初回セットアップで設定した管理者情報でログインします。GUI の「ステータス」ページにある「一括ダウンロード」ショートカットからも開けます。

## 2. Pixiv Cookie を取得（推奨）

Cookie を設定すると、R-18 / R-18G、検索、クイック取得、ログイン必須の作品を利用できます。全年齢の公開作品だけなら Cookie なしでも試せます。

まずブラウザーで [Pixiv](https://www.pixiv.net/) にログインしてください。バックエンドが必要とするのは通常 `PHPSESSID` だけです（例：`12345678_xxxxxxxx`）。

### 取得方法

- **Cookie-Editor**：拡張機能で Pixiv の Cookie をエクスポートし、Netscape 形式で貼り付ける
- **DevTools の Application**：`F12` → Application → Cookies → `https://www.pixiv.net` で `PHPSESSID` の Value を取得し、`PHPSESSID=値` として Header String 形式で貼り付ける
- **DevTools の Network**：Pixiv のリクエストを選び、Headers → Request Headers → `Cookie` の値全体を Header String 形式で貼り付ける
- **機能拡張ツールボックス**：「Cookie をワンクリック登録」を使う（HttpOnly Cookie の読み取り許可が必要）

Tampermonkey の Cookie アクセスを一時的に「All」にするとワンクリック登録を使えますが、他のスクリプトにも認証情報が読めるようになります。完了後は「All except HttpOnly」に戻すか、DevTools の方法を使ってください。

`pixiv-batch.html` 上部の「Cookie」カードを開き、取得方法に合う形式（Netscape または Header String）を選び、貼り付けて「Cookie を保存」をクリックします。Pixiv からログアウトしたりパスワードを変更したりすると Cookie は無効になります。

## 3. 作品をダウンロード

Pixiv の作品 URL または数値 ID を用意します。

```text
https://www.pixiv.net/artworks/12345678
```

`pixiv-batch.html` の「**🎨 作品 URL 一括インポート**」タブで URL を貼り付け、「**情報を取得**」をクリックします。プレビューを確認したら「**ダウンロード開始**」をクリックしてください。

## 4. 進捗を確認

ダウンロードキューには次の状態が表示されます。

| 状態 | 意味 |
|--------|---------|
| ⏳ 待機中 | キューに入り開始待ち |
| 🔄 ダウンロード中 | ファイルを取得中 |
| ✅ 完了 | 取得成功 |
| ⏩ スキップ | すでに取得済み |
| ❌ 失敗 | エラー。項目をクリックして理由を確認 |

## 5. ファイルを確認

既定では、プログラムの**作業ディレクトリ**にある `pixiv-download/` に保存されます。

```text
pixiv-download/
└── 123456/
    ├── 123456_p0.jpg
    ├── 123456_p1.jpg
    └── ...
```

Windows インストーラーでは選択したインストール先、JAR 版では `java -jar` を実行したフォルダー、GUI では「ステータス」ページの「ダウンロードディレクトリ」で確認できます。`config.yaml` の `download.root-folder` で変更できます。

## 6. ギャラリーで閲覧

```text
http://localhost:6999/pixiv-gallery.html
```

検索、絞り込み、コレクション管理などの詳細は [作品ギャラリー](/ja/gallery) を参照してください。

## よくある問題

- **Cookie が必要**：Cookie を設定してください
- **プロキシ接続失敗**：プロキシの起動状態、`config.yaml` のアドレスとポートを確認してください
- **作品が存在しない**：削除または非公開化された可能性があります
- **保存先が見つからない**：`config.yaml` の `download.root-folder` と作業ディレクトリを確認してください

複数 URL は [URL 一括ダウンロード](/ja/batch-download)、作者単位は [ユーザーダウンロード](/ja/user-download)、キーワード検索は [検索](/ja/search) を参照してください。
