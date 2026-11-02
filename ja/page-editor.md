# ページエディターとプラグインコンポーネント

ページエディター（`page-editor` プラグイン、SDK 1.4+ が必要）では、ログインページなど対応ページのレイアウト、レスポンシブスタイル、カスタムコンポーネント、フォーム検証コンポーネントを視覚的に配置できます。プラグインをインストールして有効にした後、管理者としてナビゲーションから「ページエディター」を開きます。

## ページの編集、公開、復元

1. 対象ページを選び、左側の「コンポーネントを追加」からレイアウト、コンテンツ、プラグインコンポーネントをキャンバスにドラッグします。キーボードではコンポーネントにフォーカスして `Enter` / `Space` で詳細を開き、「ページに追加」を選べます。
2. 左側の「構造」またはキャンバスでインスタンスを選び、右側でプロパティ、レイアウト、外観を調整します。コンテナーの入れ子、上下移動、親からの移動、削除、狭い画面向けのレスポンシブな上書きに対応します。
3. 「下書きを保存」または「プレビュー」をクリックします。プレビューは実際のページのスタイルを使用しますが、ログインや業務上の書き込みリクエストは送信しません。「編集に戻る」では未保存の変更を保持します。
4. 「公開」で正式ページを置き換えます。公開ページのコンポーネント設定とプロパティのデフォルト値も公開されるため、パスワードやトークンを入れないでください。
5. 上部の「その他」メニューには「下書きを再読み込み」「前の公開版に戻す」（正式版と下書きの両方を置換）、「ページのデフォルトレイアウトに戻す」（未保存のキャンバスだけを置換）があります。

正式ページの動作と操作の保護には、エディターを開いたままにする必要はありません。正式ページの「デフォルトレイアウトを使用」は現在のブラウザーの表示だけを変え、サーバー側の検証要件は解除しません。不在またはバージョン非互換のプラグインコンポーネントがページに含まれる場合も下書きは保存できますが、公開には該当プラグインの復旧または設定の移行が必要です。

## フォーム検証とリソース認可

ページの owner が宣言したフォーム領域に CAPTCHA コンポーネントを配置すると、互換性のある対象が 1 つなら自動的に関連付けられます。複数ある場合は、右側の「保護する操作」で明示的に選んでください。同じフォームに複数の検証コンポーネントを関連付けた場合、すべてのポリシーを満たす必要があります。

公開前に、影響を受ける操作、ポリシーの変更、コンポーネントが必要とする外部リソースの取得元が表示されます。公開を確認すると、サーバーは対象操作の新規リクエストを一時停止し、進行中の処理が完了するのを待って、ページ、関連付け、CSP のリソース権限を原子的に反映します。`setup.login-verification-policies` による静的なログイン保護要件も維持します。

正式な公開状態は `data/web-publications/`、登録ファイルは `config/web-publications.enrolled` に保存されます。キャッシュとして削除しないでください。オフライン修復が必要な場合はプログラムを停止し、配布ディレクトリで候補ファイルと SHA-256 を照会・確認してから復旧を実行します。

```text
java -jar "<boot-jar>" --no-gui --web-publications
java -jar "<boot-jar>" --no-gui --web-publications --publication-restore=<candidate.json> --publication-sha256=<sha256>
```

## ヘッダー、フッター、カスタム CSS

デフォルトのレイアウトは、ヘッダー、残りの高さを縦方向に伸びて埋める本文、フッターで構成されます。届出番号などの情報を加えるには、「フッター」を選んで「テキスト」や「リンク」を挿入します。ログインカード内のタイトル、ユーザー名、パスワード、ボタンは管理された必須領域です。コントロール間にテキストやコンポーネントを挿入できますが、削除、複製、所属する親領域の外への移動はできません。「デフォルトで非表示の領域を表示」を選ぶと招待コードカードを編集できます。

コンポーネントを選び、右側の「スタイル → カスタム CSS」で組み込みの CodeMirror を使ってプロパティ宣言を編集します。行番号、補完、エラー位置の表示、右上の全画面切り替えに対応し、`Ctrl/Cmd+Enter` または「スタイルを適用」で反映します。

```css
padding: 16px;
border-radius: 8px;
```

- 各プロパティ値は最大 128 文字で、選択したコンポーネントのルート要素と現在のキャンバス幅に適用されます。狭い画面では別の上書きを設定でき、空にして適用すると上書きを解除できます。
- セレクター、コメント、`@media` / `@import`、外部リソース URL、`!important`、`display: none`、カスタム CSS 変数は使用できません。保存、プレビュー、公開の前に、適用するか「変更を破棄」をクリックしてください。
- 対応プロパティ：`display`、`flex-direction`、`flex-wrap`、`justify-content`、`align-items`、`gap`、`padding`（四方向を含む）、`margin`（四方向を含む）、`width`、`min-width`、`max-width`、`min-height`、`font-size`、`font-weight`、`line-height`、`text-align`、`color`、`background-color`、`border`、`border-color`、`border-width`、`border-style`、`border-radius`、`box-shadow`、`grid-template-columns`、`flex`、`order`。

## カスタム HTML / CSS / JavaScript コンポーネントライブラリ

「コンポーネントを追加」で「新しいコンポーネント」または「コンポーネントライブラリを管理」をクリックします。別タブで開くため、元のページの下書きには影響しません。

1. 「ソース」で HTML フラグメント、CSS、任意の JavaScript 関数本体を編集し、「プロパティ定義」でインスタンスのフィールドを追加します。テキスト `string`、数値 `number`、スイッチ `boolean` に対応します。

   ```json
   {"title":{"type":"string","label":"Title","default":"Hello"}}
   ```

   たとえば HTML を `<p></p>`、JavaScript を `root.querySelector('p').textContent = context.properties.title;` とします。`root` と、`properties`・`mode` を含む `context` を受け取り、`update(context)` と `destroy()` を返せます。
2. 「プレビュー実行」で確認し、「公開」で変更不可のバージョンとして保存してコンポーネントパネルに追加します。
3. コンポーネントをページに配置してページを公開します。その後にコンポーネントの新バージョンを公開しても、公開済みページは自動変更されません。ページ上のインスタンスのプロパティで新バージョンを選び、「選択したバージョンを使用してプロパティをリセット」をクリックしてから、ページを再公開してください。
4. 「コンポーネントを撤回」すると、ソースと過去のバージョンを保持したまま、既存インスタンスの描画を停止できます。

カスタムコンポーネントは `allow-same-origin` のないサンドボックス iframe で動作します。CSP は `fetch`/`XHR`、外部スクリプト、外部スタイルを禁止し、HTML 内では `<script>`、`<style>`、`<form>`、埋め込みページ、外部リソース、インラインイベント属性を禁止します。ライブラリは `data/page-editor/components/` に保存されます。ソース 1 件の上限は 1 MiB、最大 128 コンポーネント、コンポーネントごとに最大 4,096 バージョン・64 プロパティです。

## サードパーティープラグインのコンポーネントとページを接続する

プラグインは公開契約を通じてコンポーネントと編集可能なページを提供でき、ホストやエディターのソース変更は不要です。完全な検証例は主リポジトリの [`page-components` フィクスチャ](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-app/src/test/resources/page-components)を参照してください。

| 拡張点 | 責任と制約 |
| --- | --- |
| 業務契約と `sdk-tools` | 独立した API パッケージで操作と設定 record を宣言し、JSON Schema、ブラウザークライアント、Java HTTP adapter を生成 |
| `PageContribution.components()` / `componentCategories()` | `PageComponent`（安定した `owner:type`、正の整数の設定バージョン、モジュール URL、schema/defaults、`publicRenderable`、ポート）と分類 `owner:local-id` を宣言。`validate` と任意の `migrate` を実装 |
| 描画・プロパティ編集モジュール | `mount(root, context)`、複雑なプロパティでは `mountProperties(root, context)` をエクスポート。`context.fetch`、`context.signal`、`context.onCleanup` を使用し、GrapesJS オブジェクトは公開しない。`edit`/`preview` で業務上の書き込みリクエストを直接送信しない |
| `PageContribution.pages()` | `EditablePage`（ページ ID、パス、`regions`、`defaultProject`、`previewModuleUrl`、同じ owner の CSS 最大 16 件）を宣言。フロントエンドは `/js/pixiv-pages.mjs` の `attachPage` を呼ぶ。コアの `login/login-page.mjs` を参照 |

```js
import {createClient} from './generated/my.contract.mjs';

export function mount(root, context) {
    const client = createClient(context.fetch);
    const button = document.createElement('button');
    button.textContent = context.t('run');
    button.disabled = context.mode !== 'display';
    context.on(button, 'click', async () => {
        try {
            const result = await client.run(context.properties, {signal: context.signal});
            if (!context.signal.aborted) context.emit('result', result);
        } catch (error) {
            if (!context.signal.aborted) root.dataset.failed = 'true';
        }
    });
    root.append(button);
    return {
        update(properties) { /* 表示を更新し、書き込み操作は実行しない */ },
        destroy() { root.replaceChildren(); }
    };
}
```

ページ文書ごとの上限は 1 MiB、512 ノード、入れ子 24 階層、ポート接続 256 本、タイトル 256 文字です。独立したブラウザー・ライフサイクル検証のコマンド：

```powershell
mvn -pl pixivdownload-plugin-page-editor,pixivdownload-app,pixivdownload-sdk-tools,pixivdownload-plugin-download-workbench -am package '-DskipTests' '-Dexec.skip=true'
node scripts/ci/page-editor-acceptance.mjs target/page-editor-acceptance
```

## 公式 CAPTCHA プラグイン

公式の `captcha` プラグインは、Turnstile、reCAPTCHA v2、ALTCHA SHA-256、ALTCHA PBKDF2、文字入力、スライドパズルの 6 種類の検証コンポーネントを提供します。インストール、フォームへの関連付け、キー設定、公式テストキー、トラブルシューティングは[操作 CAPTCHA](/ja/captcha)を参照してください。

フォームの開発者は `OperationGuard.prepare(operation, subject, context)` でチャレンジを生成します。この時点では業務処理を実行しません。フロントエンドは `createOperationTarget(operation, onChange, prepareRequest)` で準備リクエストを登録し、関連付けられたすべてのコンポーネントが完了してから `target.request()` で証明を取得します。バックエンドの業務入口では、同じ owner の `OperationGuard.execute(...)` を呼んで署名を検証し、実行します。
