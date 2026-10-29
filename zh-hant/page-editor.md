# 頁面編輯器與外掛元件

頁面編輯器需要 SDK 1.4 或以上。安裝並啟動選用的 `page-editor` 外掛，以管理員登入後，從導覽進入頁面編輯器。核心目前提供登入頁；其他頁面由各自的 owner 宣告接入。

## 編輯、發布與復原

1. 選取頁面，從「新增元件」拖入畫布。也可以聚焦元件按鈕，按 Enter 或 Space 新增。
2. 在圖層選取實例，修改屬性與樣式。容器可以巢狀排列，移動、巢狀、移出與刪除都有按鈕可用鍵盤操作。窄螢幕模式用來設定響應式樣式。
3. 儲存、重新載入草稿，並用預覽檢查。預覽不執行業務操作。發布會儲存目前編輯內容並替換正式版本；公開頁面的元件設定也會公開。
4. 復原上一個發布版本會同時替換正式頁面與草稿。若其他視窗先儲存，會回報版本衝突；請重新載入後再套用修改。

左側可切換「結構」與「新增元件」。點選畫布內容或在結構清單中選取，再於右側調整屬性與樣式；移動與刪除操作也在右側。「頁面設定」中的「瀏覽器分頁標題」只修改分頁標題。點選「預覽」後，實際版面會在中央展開；「返回編輯」保留未儲存的修改。預覽使用登入頁本身的控制項與樣式，不提交登入或業務請求。

「重新載入草稿」和「還原上一發布版本」位於頂部的「更多」選單。沒有可還原的發布版本時，還原按鈕無法使用。

核心保留登入表單、帳號登入、邀請碼與回跳行為。「使用預設配置」可恢復原本外觀。元件缺席、發布內容無效或編輯器停用時，也會回退到預設登入頁。編輯不改變認證策略；已登入使用者仍按原有規則重新導向。

缺席或不相容的元件保留原始類型、設定版本、實例身分與設定。其他版面修改可以儲存，但不完整的頁面不能發布。請重新啟用相符的外掛，或明確執行該外掛提供的設定遷移。發布或復原失敗不改寫上次已儲存的發布版本。

## 頁首、頁尾與登入卡片

首次開啟會載入頁面 owner 提供的原始版面。選取既有頁首、正文或頁尾即可調整樣式，也可從「新增元件」拖入這些區域。容器的「區域類型」可切換為一般容器、頁首、正文或頁尾。

加入備案資訊時，先選取圖層中的「頁尾」，新增「文字」或「連結」，再填寫文字與網址。預設版面採用縱向彈性排列，正文的「彈性伸展」設為「填滿剩餘空間」：內容較短時頁尾位於底部，較長時隨正文延伸，不遮住內容。

登入卡片的標題、使用者名稱、密碼與按鈕均可選取。將文字或外掛拖到控制項之間，或選取控制項後聚焦元件按鈕並按 Enter，即可插入其後。必要控制項不能刪除、複製或移出宣告的父區域；一般容器仍可用於分組及樣式調整。「顯示預設隱藏的區域」可編輯邀請碼卡片，不改變正式頁面的切換行為。

「更多 → 還原頁面預設版面」只替換尚未儲存的編輯內容，儲存並發布才影響正式頁面。正式登入頁的預設版面按鈕只還原目前瀏覽器畫面，不刪除伺服器草稿或發布紀錄。草稿缺少必要區域時，會顯示說明與預設登入頁預覽，暫時無法儲存或發布。選取「以此版面開始」只替換未儲存的畫布；原草稿會保留到下一次明確儲存。

## 元件 CSS

選取元件，在右側「樣式」中展開「自訂 CSS」，即可使用 CodeMirror 編輯區。「配置」「外觀」和「自訂 CSS」可分別展開；收起「樣式」會收起整個群組，並保留尚未套用的 CSS。編輯區提供行號、語法高亮、支援屬性補全和錯誤定位。點擊問號可查看輸入格式與快捷鍵。右上角的對角箭頭可切換全螢幕；再次點選或按 Esc 返回側欄，輸入與復原記錄會保留。填寫屬性宣告後點選「套用樣式」，例如：

```css
padding: 16px;
border-radius: 8px;
```

樣式作用於所選元件的根元素及目前畫布寬度；文字等可繼承屬性仍遵循 CSS 繼承規則。切換至窄螢幕可單獨設定覆寫，清空輸入並套用會移除目前寬度的覆寫。套用後可復原，結果隨草稿儲存，並用於預覽和發布，不另存 `.css` 檔案。

Ctrl/Cmd+Enter 套用樣式，Tab 移到下一個控制項，Esc 先關閉補全清單或說明，再離開全螢幕或收起編輯區。收起會保留未套用的輸入；「放棄修改」會重新載入目前元件樣式。儲存、預覽或發布前，須先套用或放棄修改。切換元件或畫布寬度後，原輸入仍保留，但不能套用至其他目標。

面板展開、元件移動和操作回饋使用可中斷的轉場；系統啟用「減少動態效果」後停止這些動畫，狀態文字與結果仍會顯示。

接受下列屬性及其有效值。每個值最多 128 個字元；不接受選取器、註解、`@media` / `@import`、外部資源、`!important`、`display: none` 或自訂屬性定義。輸入無效時保留內容並顯示錯誤。

`display`, `flex-direction`, `flex-wrap`, `justify-content`, `align-items`, `gap`, `padding`, `padding-top`, `padding-right`, `padding-bottom`, `padding-left`, `margin`, `margin-top`, `margin-right`, `margin-bottom`, `margin-left`, `width`, `min-width`, `max-width`, `min-height`, `font-size`, `font-weight`, `line-height`, `text-align`, `color`, `background-color`, `border`, `border-color`, `border-width`, `border-style`, `border-radius`, `box-shadow`, `grid-template-columns`, `flex`, `order`.

## 提供元件

新增元件只需修改自身外掛與獨立公開業務契約，無須增加宿主、中央 SDK 或編輯器的元件類型清單。[獨立清單範例](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-app/src/test/resources/page-components)涵蓋巢狀 `items[]`、自訂屬性編輯器、兩個實例與生成的業務客戶端；它是驗收用外掛，不是預裝的正式功能。

| 檔案或入口 | 元件作者負責的內容 |
| --- | --- |
| 業務契約與值類型 | 宣告操作與設定類型，用 SDK 工具生成 JSON Schema、瀏覽器客戶端與 Java HTTP adapter |
| `PageContribution` Bean | 回傳 `PageComponent` 宣告，實作業務校驗與必要的設定遷移 |
| 渲染模組 | export `mount(root, context)`，回傳 `update(properties)` 與 `destroy()` |
| 屬性編輯模組 | 複雜資料使用 `mountProperties(root, context)`，透過 `context.change(value)` 提交完整設定 |
| 設定類別與描述檔 | 登記 Bean、生成 adapter、service imports/exports、契約依賴與最低 SDK |
| 靜態資源、路由與 i18n | 提供同源模組與詞典；公開渲染模組必須有實際 PUBLIC 路由，管理資源及業務操作維持各自權限 |

`PageComponent` 宣告穩定的 `owner:type`、正整數設定版本、標籤 key/namespace、渲染與選用編輯模組 URL、schema/defaults JSON、`publicRenderable` 及輸入輸出埠名稱。類型的 owner 前綴必須符合實際外掛 owner，重複類型會被拒絕。目錄聚合多個 owner，不把單一 provider 的 `PluginServices` key 當成元件集合。

Schema 使用目前 SDK 生成器與同一伺服器校驗器支援的語義，不支援任意 JSON Schema draft。基本 inspector 處理字串、布林與數字；陣列及物件由外掛提供專用屬性編輯器。`validate(type, json)` 校驗業務限制，`migrate(type, fromVersion, json)` 回傳遷移設定，伺服器會再次校驗。預設不支援遷移。

Plugin API JAR 的 `META-INF/pixivdownload/web/page-components.d.mts` 提供瀏覽器宣告。context 包含實例身分、`edit/preview/display` 模式、純值屬性、取消、翻譯、受控 fetch、清理與宣告埠連線，不暴露 GrapesJS 物件。將 `context.fetch` 傳給 `createClient`，操作使用 `context.signal`；訂閱、監聽器與計時器透過 `context.onCleanup` 清理，取消後不再套用遲到結果。舊 publication 的請求不會改投新外掛。後端鑑權仍有效；瀏覽器斷線不代表伺服器業務已排空。

外掛是受信任的已安裝程式碼，iframe 不是安全沙箱。編輯或預覽時不要繞過 context，用原生 fetch 執行業務寫入。設定不儲存函式、腳本、執行期控制代碼或授權 token。埠資料與校驗由業務契約定義；頁面只連接宣告埠，不透過任意 URL、反射方法名或腳本執行。

## 接入頁面與儲存

`EditablePage` 另宣告 `regions`、`defaultProject`、`previewModuleUrl` 與 `stylesheets`。每個 `PageRegion(id, labelKey, parent, container, previewHidden)` 必須出現一次；`parent` 表示最近的受控父區域，空字串表示不能位於其他受控區域內。只有 `container=true` 才能插入子節點。伺服器依 owner 的宣告校驗，頁面 JSON 不能自行授權。

`defaultProject` 使用純值格式 `{"format":1,"project":{...}}`；空字串會依區域父子關係產生最小版面。節點包含 `page-box`（`role` 為 `section/header/main/footer`）、`page-text`、`page-link`、`page-region` 與 `page-component`，完整範例見核心 `login/login-layout.json`。連結的 `href` 僅接受站內絕對路徑或 HTTP(S) 網址，最多 2048 字元；導覽連結不是業務操作入口。

預覽模組使用 `mount(root, context)`，由 `context.properties.region` 指定葉區域，只繪製唯讀外觀；容器的子節點由編輯器組合。資源必須屬於目前 owner，最多宣告 16 份 CSS，隨 publication 校驗與撤回。匿名投影不含預覽模組網址。`attachPage.onMounted` 可隱藏原頁面外殼，`onFallback` 還原外殼；執行時會還原移動區域的原 DOM、子節點與樣式。

頁面 owner 透過 `PageContribution.pages()` 提供 `EditablePage`，包含身分、顯示資料、實際頁面路徑、公開性與必需區域名稱。呼叫 `/js/pixiv-pages.mjs` 的 `attachPage`，傳入渲染 root、既有區域 DOM、預設配置按鈕與標題 callback。核心 `login/login-page.mjs` 是接入範例。保留 owner 的預設頁面與業務監聽器；區域 DOM 只移動、不重建。公開性必須符合 owner 的實際 PUBLIC 路由。

目錄與草稿限 ADMIN。匿名 `GET /api/web/page-publications/{pageId}` 只回傳已宣告公開頁面的正式渲染投影，不含草稿、schema 或屬性編輯模組地址。正式頁面只載入輕量 runtime 與必要元件，不載入 GrapesJS。文書儲存在 editor owner 執行期資料目錄的 `pages`，不修改 JAR 資源或宿主設定。

格式 1 儲存受限的 GrapesJS project JSON、標題、實例設定與連線，HTML/CSS 是派生結果。每份文書最多 1 MiB、512 個節點、24 層巢狀結構、256 條連線，標題最多 256 字元。狀態檔保留草稿、正式版與上一版，以 revision 檢查並行儲存，並在同目錄內原子替換。支援受控版面及文字樣式；拒絕腳本、任意 HTML、外部資源 URL 與 CSS import。

## 執行獨立外掛驗收

準備 Java 17、Maven、Python、Node.js 與已安裝的 Playwright/Chromium。將 `PAGE_EDITOR_PLAYWRIGHT` 設為 Playwright `index.mjs` 絕對路徑，必要時用 `PAGE_EDITOR_CHROMIUM` 指定瀏覽器執行檔。

```powershell
mvn -pl pixivdownload-plugin-page-editor,pixivdownload-app,pixivdownload-sdk-tools,pixivdownload-plugin-download-workbench -am package '-DskipTests' '-Dexec.skip=true'
node scripts/ci/page-editor-acceptance.mjs target/page-editor-acceptance
```

第一個命令只建置相關產物，單元測試另行執行。第二個命令需要全新的輸出目錄：先固定 JAR，再獨立生成及編譯範例，經真實宿主安裝入口執行瀏覽器與生命週期檢查，最後核對固定 hash。設定與資料庫均隔離，不修改真實使用者資料，也不代表發行套件或驗證碼已驗收。

固定的 GrapesJS Core 檔案、授權及 hash 記錄於 `web/vendor/grapesjs/vendor.json`。執行時關閉預設 CDN 圖示與 telemetry；元件自身的業務網路存取由各外掛宣告。
