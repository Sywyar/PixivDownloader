# ページエディターとプラグインのコンポーネント

SDK 1.4 以降が必要です。任意の `page-editor` プラグインをインストールして起動し、管理者でログインしてナビゲーションからページエディターを開きます。コアはログインページを提供します。他のページは、その所有者が編集対象として登録します。

## 編集・公開・復元

1. ページを選び、「コンポーネントを追加」からキャンバスにドラッグします。ボタンにフォーカスして Enter または Space でも追加できます。
2. レイヤーでインスタンスを選び、プロパティとスタイルを編集します。コンテナーは入れ子にでき、移動・入れ子・外へ移動・削除はボタンでも操作できます。モバイル幅で設定したスタイルはレスポンシブ表示に使われます。
3. 下書きを保存・再読み込みし、プレビューで表示を確認します。プレビューでは業務操作を実行しません。公開すると現在の編集内容を保存して公開版を置き換えます。公開ページのコンポーネント設定も公開されます。
4. 「前の公開版を復元」は公開版と下書きを置き換えます。他のウィンドウが先に保存した場合は競合となるため、再読み込み後に変更してください。

左側で「構造」と「コンポーネントを追加」を切り替えます。キャンバスまたは構造リストで内容を選び、右側でプロパティやスタイルを編集します。移動と削除も右側から操作できます。「ページ設定」のタブタイトルはブラウザーのタブだけを変更します。「プレビュー」では実際のレイアウトを中央に表示し、「編集に戻る」で未保存の変更を保持したまま戻れます。ログインページ自身のコントロールとスタイルを使い、ログインや業務操作のリクエストは送信しません。

「下書きを再読み込み」と「前の公開版を復元」は、上部の「その他」メニューにあります。前の公開版がない場合、復元は選択できません。

ログインフォーム、アカウント認証、招待コード、リダイレクトはコアが保持します。公開ページと操作の保護はエディター停止後も有効です。「既定のレイアウトを使用」は現在のブラウザー表示だけを変え、サーバーの検証要求を削除しません。正式記録、必須コンポーネント、ポリシーが利用できない場合、保護対象の操作は拒否されます。ログイン済み利用者のリダイレクトは維持されます。

欠落・非互換のコンポーネントは型、設定バージョン、インスタンス ID、設定を保持します。ほかのレイアウト変更は保存できますが、不完全なページは公開できません。対応するプラグインを再開するか、そのプラグインの設定移行を明示的に実行してください。コミット前の失敗は前の正式版を保持します。コミット記録の保存後に有効化が失敗した場合は操作を拒否したまま、そのリビジョンから復旧します。

## フォーム検証とリソース承認

ページ owner が宣言したフォーム領域に検証コンポーネントをドラッグします。互換対象が一つなら自動選択されます。複数ある場合は保護対象の操作を選択してください。一致する対象がなければ下書きのみ保存でき、保護は公開できません。同じ対象に参加するポリシーはすべて必須で、重複は拒否されます。コピー、移動、取り消しではインスタンスを分離し、使用中の proof は複製しません。

公開前に、影響する操作、必須ポリシーの変更、必要なリソースの接続元を表示します。確認後、サーバーは現在のカタログとリビジョンを再検証し、対象操作の新規受付を止め、実行中の処理が終了してからページ、関連付け、権限を一緒にコミットします。下書きとプレビューは正式な保護を変えず、実際のチャレンジも生成しません。同じ操作を参照する複数ページの設定は一致させてください。静的な `setup.login-verification-policies` は引き続きログインの必須要求です。

HTML、タイトル、投影、ETag、CSP は同じ正式リビジョンを使います。古い文書は変更を検出すると再読み込みし、バックエンドは古い proof を拒否します。コンポーネントは Worker などのインスタンス資源を解放します。カスタム HTML/JS のサンドボックスは変更されず、CAPTCHA の外部スクリプト権限を取得しません。

正式状態は `data/web-publications/`、登録記録は `config/web-publications.enrolled` にあります。キャッシュとして削除しないでください。エディター停止や主データベース障害でも保護は消去されません。オフライン修復ではインスタンスを停止し、同じ実行ディレクトリで次のローカルコマンドを使います。照会で候補ファイルと SHA-256 を確認し、完全性を確認した候補だけを復元します。更新時刻だけで選ばないでください。

```text
java -jar "<boot-jar>" --no-gui --web-publications
java -jar "<boot-jar>" --no-gui --web-publications --publication-restore=<candidate.json> --publication-sha256=<sha256>
```

`<boot-jar>` は実際の起動 JAR、`<candidate.json>` と `<sha256>` は照会して確認した値に置き換えてください。

## ヘッダー、フッターとログインカード

新規の下書きにはページ owner が提供する元のレイアウトが表示されます。既存のヘッダー、メインコンテンツ、フッターを選んでスタイルを変更するか、追加パネルから挿入します。コンテナーの「領域の種類」で通常のコンテナーと各領域を切り替えられます。

登録情報を載せる場合はレイヤーでフッターを選び、テキストまたはリンクを追加して文面と URL を入力します。標準レイアウトは縦方向の flex 配置で、メインコンテンツが残りの空間を埋めます。短いページではフッターが下端に配置され、長いページでは本文の後に続き、内容を覆いません。

ログインカード内のタイトル、ユーザー名、パスワード、ボタンを選択できます。コントロール間にテキストやプラグインをドラッグするか、コントロールを選び、追加ボタンで Enter を押すとその後に挿入できます。必須コントロールの削除、複製、指定された親領域の外への移動はできません。通常のコンテナーでグループ化とスタイル調整ができます。「初期状態で非表示の領域を表示」で招待カードを編集しても、公開ページの切り替え動作は変わりません。

「その他 → ページの標準レイアウトに戻す」は未保存の編集内容だけを置き換えます。公開ページに反映するには保存して公開します。公開ログインページの標準表示ボタンは、そのブラウザーの表示だけを戻し、サーバーの下書きと公開履歴は削除しません。必須領域が不足している下書きには説明と既定のログインページのプレビューが表示され、保存と公開はできません。「このレイアウトで始める」は未保存のキャンバスだけを置き換えます。明示的に保存するまで元の下書きは保持されます。

## コンポーネントの CSS

コンポーネントを選択し、右側の「スタイル」で「カスタム CSS」を展開すると、CodeMirror エディターを利用できます。「レイアウト」「外観」「カスタム CSS」は個別に開閉できます。「スタイル」を閉じるとグループ全体が折りたたまれ、未適用の CSS は保持されます。エディターでは行番号、構文の色分け、対応プロパティの補完、エラー位置の表示を利用できます。疑問符ボタンで入力形式とキーボードショートカットを確認できます。右上の対角線矢印で全画面に切り替え、もう一度押すか Esc でサイドバーに戻ります。入力と編集履歴は保持されます。次のように記述し、「スタイルを適用」を選びます。

```css
padding: 16px;
border-radius: 8px;
```

スタイルは選択したコンポーネントのルート要素と現在のキャンバス幅に適用されます。文字などの継承可能なプロパティは CSS の継承規則に従います。狭い画面に切り替えると、その幅の上書きを設定できます。空の入力を適用すると現在の幅の指定を解除します。適用は元に戻せます。スタイルは下書きに保存され、プレビューと公開ページで使用されます。別の `.css` ファイルは保存しません。

Ctrl/Cmd+Enter で適用し、Tab で次のコントロールへ移動します。Esc は補完候補やヘルプを先に閉じ、その後に全画面表示を終了するか編集欄を折りたたみます。折りたたんでも未適用の入力は残り、「変更を破棄」で現在のスタイルを読み直します。保存・プレビュー・公開の前に、CSS の変更を適用または破棄してください。対象やキャンバス幅を変えても入力は残りますが、別の対象には適用できません。

パネルの開閉、コンポーネントの移動、操作結果の表示には中断可能なアニメーションを使います。システムで視差効果を減らす設定を有効にすると、アニメーションを止め、状態と結果は引き続き表示します。

対応プロパティは以下のとおりです。各値は最大 128 文字です。セレクター、コメント、`@media` / `@import`、外部リソース、`!important`、`display: none`、カスタムプロパティの定義には対応していません。無効な入力は保持され、エラーが表示されます。

`display`, `flex-direction`, `flex-wrap`, `justify-content`, `align-items`, `gap`, `padding`, `padding-top`, `padding-right`, `padding-bottom`, `padding-left`, `margin`, `margin-top`, `margin-right`, `margin-bottom`, `margin-left`, `width`, `min-width`, `max-width`, `min-height`, `font-size`, `font-weight`, `line-height`, `text-align`, `color`, `background-color`, `border`, `border-color`, `border-width`, `border-style`, `border-radius`, `box-shadow`, `grid-template-columns`, `flex`, `order`.

## コンポーネントの追加

新しいコンポーネントでは、そのプラグインと独立した公開業務契約だけを変更します。ホスト、中央 SDK、エディターの型一覧に追記する必要はありません。[独立したチェックリストの例](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-app/src/test/resources/page-components)は、入れ子の `items[]`、専用プロパティエディター、2 インスタンス、生成された業務クライアントを示す検証用プラグインです。

| ファイル・入口 | 開発者の作業 |
| --- | --- |
| 業務契約と値の型 | 操作と設定型を定義し、SDK ツールで JSON Schema、ブラウザークライアント、Java HTTP adapter を生成 |
| `PageContribution` Bean | `PageComponent` を返し、業務検証と必要な設定移行を実装 |
| 描画モジュール | `mount(root, context)` を export し、`update(properties)` と `destroy()` を返す |
| プロパティ編集モジュール | 複雑な設定用の `mountProperties(root, context)` を export。`context.change(value)` に設定全体を渡す |
| 設定クラス・記述子 | Bean、生成 adapter、service imports/exports、契約依存、最低 SDK を宣言 |
| 静的リソース・ルート・i18n | 同一オリジンのモジュールと辞書を登録。公開描画モジュールには実際の PUBLIC ルートが必要 |

`PageComponent` は安定した `owner:type`、正の設定バージョン、ラベル key/namespace、描画 URL と任意の編集 URL、schema/defaults JSON、`publicRenderable`、入出力ポート名を宣言します。型の owner 接頭辞は実際のプラグイン owner と一致する必要があり、型の重複は拒否されます。カタログは複数 owner を集約します。単一 provider 用の `PluginServices` key を集合として使いません。

`PageContribution.componentCategories()` で `PageComponentCategory(id, labelKey, namespace, descriptionKey, components)` を返します。ID は `owner:local-id`、タイトルと任意の説明は i18n key、メンバーは表示順の自プラグインのコンポーネント型です。空カテゴリ、重複 ID、重複所属、未知または別プラグインの型は拒否されます。未分類の型はプラグイン表示名にまとめられます。カテゴリはプラグインと同時に撤回され、ページのバインドや操作権限は変更しません。カテゴリ数はコンポーネント数以下で、ページとコンポーネントの宣言と合わせて UTF-8 で 1 MiB 以内です。

Schema は現在の SDK generator とサーバー validator の対応範囲に従います。任意の JSON Schema draft には対応しません。標準 inspector は文字列・真偽値・数値を扱い、配列やオブジェクトには専用エディターを用意します。業務条件を `validate(type, json)` で検証し、`migrate(type, fromVersion, json)` で移行結果を返します。移行は既定で非対応であり、結果はサーバーが再検証します。

Plugin API JAR の `META-INF/pixivdownload/web/page-components.d.mts` がブラウザー契約です。context は ID、`edit/preview/display`、純粋な値のプロパティ、キャンセル、翻訳、fetch、後処理、宣言済みポートの接続を提供し、GrapesJS オブジェクトは公開しません。生成クライアントへ `createClient(context.fetch)` を渡し、呼び出しには `context.signal` を指定します。購読・イベント・タイマーを `context.onCleanup` で解除し、キャンセル後の結果を反映しないでください。旧 publication の要求は新しいプラグインに転送されません。サーバー認証は引き続き有効であり、ブラウザーの切断だけではサーバー処理の終了を証明できません。

プラグインコードは信頼済みのインストール済みコードです。iframe はセキュリティ sandbox ではありません。編集・プレビュー時に native fetch で業務書き込みを実行しないでください。設定に関数、スクリプト、実行時ハンドル、認証 token を保存しません。ポートの payload と検証は業務契約が定義し、ページは任意 URL、反射メソッド名、スクリプトを介さず宣言済みポートを接続します。

## ページの接続と保存

`EditablePage` は `regions`、`defaultProject`、`previewModuleUrl`、`stylesheets` も宣言します。各 `PageRegion(id, labelKey, parent, container, previewHidden)` は必ず 1 回配置します。`parent` は最も近い管理対象の親領域で、空文字列はほかの管理対象領域内への配置を禁止します。子ノードを置けるのは `container=true` の領域だけです。サーバーはページ JSON の自己申告ではなく owner の宣言を検証します。

`defaultProject` は純粋な値の `{"format":1,"project":{...}}` です。空文字列なら領域の親子関係から最小レイアウトを生成します。`page-box`（`role` は `section/header/main/footer`）、`page-text`、`page-link`、`page-region`、`page-component` を使用します。完全な例はコアの `login/login-layout.json` にあります。リンクの `href` はサイト内の絶対パスまたは HTTP(S) URL で、2048 文字までです。リンクを業務操作の呼び出しには使いません。

プレビューモジュールの `mount(root, context)` は `context.properties.region` で指定された葉領域の読み取り専用表示を描画し、コンテナーの子はエディターが組み立てます。リソースは現在の owner に属する必要があり、CSS は最大 16 件です。publication による検証と撤回が適用され、匿名投影にプレビューモジュール URL は含まれません。`attachPage.onMounted` で元の外枠を隠し、`onFallback` で戻せます。runtime は移動した領域の元の DOM、子ノード、スタイルを復元します。

ページ owner は `PageContribution.pages()` から `EditablePage`（ID、表示情報、実際のパス、公開可否、必須領域）を提供します。`/js/pixiv-pages.mjs` の `attachPage` に描画 root、既存領域 DOM、既定表示ボタン、タイトル callback を渡します。コアの `login/login-page.mjs` が例です。既定ページとイベントを保持し、領域 DOM は作り直さず移動します。公開可否は owner の実際の PUBLIC ルートと一致する必要があります。

カタログと下書きは ADMIN 専用です。匿名の `GET /api/web/page-publications/{pageId}` は宣言された公開ページの描画データだけを返し、下書き、schema、編集モジュール URL を含みません。正式ページは軽量 runtime と必要なコンポーネントだけを読み込み、GrapesJS は読み込みません。文書は editor owner の runtime data ディレクトリ内 `pages` に保存され、JAR やホスト設定を変更しません。

形式 1 は制限された GrapesJS project JSON、タイトル、設定、接続を保存し、HTML/CSS は派生物です。1 文書は 1 MiB、512 ノード、入れ子 24 階層、256 接続、タイトル 256 文字までです。状態ファイルは下書き・公開版・前の公開版を保持し、revision で競合を検出して同一ディレクトリ内で原子的に置換します。レイアウトと文字のスタイルを扱い、スクリプト、任意 HTML、外部リソース URL、CSS import は拒否します。

## 独立プラグインの検証

Java 17、Maven、Python、Node.js とインストール済みの Playwright/Chromium を使います。`PAGE_EDITOR_PLAYWRIGHT` に Playwright の `index.mjs` の絶対パス、必要なら `PAGE_EDITOR_CHROMIUM` に実行ファイルを指定します。

```powershell
mvn -pl pixivdownload-plugin-page-editor,pixivdownload-app,pixivdownload-sdk-tools,pixivdownload-plugin-download-workbench -am package '-DskipTests' '-Dexec.skip=true'
node scripts/ci/page-editor-acceptance.mjs target/page-editor-acceptance
```

最初のコマンドは対象成果物だけをビルドします。単体テストは別途実行してください。2 番目は未作成の出力先を必要とし、JAR を固定後に例を独立ビルドして、実際のインストール入口、ブラウザー、ライフサイクルと固定 hash を検証します。設定・DB は隔離され、実利用データは変更しません。配布パッケージや CAPTCHA の検証とは別です。

固定した GrapesJS Core のファイル・ライセンス・hash は `web/vendor/grapesjs/vendor.json` にあります。既定の CDN アイコンと telemetry は無効です。各コンポーネント自身のネットワークアクセスは、そのプラグインが宣言します。

## 公式 CAPTCHA プラグイン

公式 `captcha` プラグインは Turnstile、ALTCHA SHA-256、ALTCHA PBKDF2 のコンポーネントを提供します。インストール、フォームへの接続、キーの設定、ローカルテスト、問題の対処は[操作認証プラグイン](/ja/captcha)を参照してください。

フォーム所有者は `OperationGuard.prepare(operation, subject, context)` でチャレンジを取得します。この呼び出しは業務処理を実行しません。ブラウザーでは `createOperationTarget(operation, onChange, prepareRequest)` にフォーム自身の準備要求を登録します。認証コンポーネントは `context.interaction.prepare()` を呼び、チャレンジを受け取って証明を返します。フォームは `target.ready()` が true の場合だけ `target.request()` から送信データを取得できます。送信先は同じ所有者の `OperationGuard.execute(...)` を呼び、認証後のコールバック内で業務処理を行います。準備と送信では同じ主体とコンテキストを使ってください。入力変更時の `reset()` と認証無効時の `invalidate(id)` は証明を消去し、未完了の準備要求をキャンセルします。準備要求には認証に必要な機密でないコンテキストだけを含め、認証コンポーネントにパスワードや業務要求全体を渡しません。
