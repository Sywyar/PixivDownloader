# 頁面編輯器與外掛元件

頁面編輯器（`page-editor` 外掛，需 SDK 1.4+）用於以視覺化方式編排登入頁等受支援頁面的版面、響應式樣式、自訂元件與表單驗證元件。安裝並啟用外掛後，以管理員身分從導覽進入「頁面編輯器」。

## 編輯、發布與還原頁面

1. 選擇目標頁面，從左側「新增元件」將版面、內容或外掛元件拖入畫布（鍵盤操作可將焦點移至元件，按 `Enter` / `Space` 開啟詳細資料，再選「新增至頁面」）。
2. 在左側「結構」或畫布中選取執行個體，在右側調整屬性、版面與外觀，支援容器巢狀配置、上移/下移、移出、刪除及窄檢視區響應式覆寫。
3. 點擊「儲存草稿」或「預覽」（預覽使用頁面真實樣式，但不提交登入或業務寫入請求；「返回編輯」會保留未儲存修改）。
4. 點擊「發布」以替換正式頁面（公開頁面上的元件設定與屬性預設值會隨之公開，請勿填入密碼或權杖）。
5. 頂部「更多」選單提供「重新載入草稿」「還原上一發布版本」（同時替換正式版與草稿）和「還原頁面預設版面」（僅替換未儲存畫布）。

正式頁面的執行與操作保護不依賴編輯器保持開啟。正式頁面上的「使用預設配置」僅改變目前瀏覽器的呈現，不會清除伺服器端驗證要求。當頁面包含缺少或版本不相容的外掛元件時，仍可儲存草稿，但須恢復對應外掛或完成設定遷移後才能發布。

## 表單驗證與資源授權

將驗證碼元件拖入頁面 owner 宣告的表單區域後，若只有一個相容目標便會自動綁定；存在多個目標時，需在右側「保護操作」中明確指定。同一表單綁定多個驗證元件時，全部策略均須完成。

發布前會列出受影響的操作、策略變更及元件所需的外部資源來源。確認發布後，伺服器端暫停目標操作的新請求、等待進行中動作完成，再以原子方式提交頁面、綁定與 CSP 資源權限（同時保留 `setup.login-verification-policies` 靜態登入保護要求）。

正式發布狀態儲存在 `data/web-publications/`，登記檔案為 `config/web-publications.enrolled`，切勿當作快取刪除。需離線修復時，先停止程式，在發行目錄查詢並核對候選檔案與 SHA-256 後執行還原：

```text
java -jar "<boot-jar>" --no-gui --web-publications
java -jar "<boot-jar>" --no-gui --web-publications --publication-restore=<candidate.json> --publication-sha256=<sha256>
```

## 頁首、頁尾與自訂 CSS

預設版面包含頁首、正文（垂直彈性延伸以填滿剩餘空間）與頁尾；新增備案編號等資訊時，選取「頁尾」並插入「文字」或「連結」即可。登入卡片內的標題、使用者名稱、密碼與按鈕為受控必要區域，可在控制項之間插入文字或元件，但不能刪除、複製或移出所屬父區域；勾選「顯示預設隱藏的區域」可編輯邀請碼卡片。

選取元件後，在右側「樣式 → 自訂 CSS」中使用內建 CodeMirror 編輯屬性宣告（支援行號、自動完成、錯誤定位與右上角全螢幕切換），按 `Ctrl/Cmd+Enter` 或點擊「套用樣式」生效：

```css
padding: 16px;
border-radius: 8px;
```

- 每個屬性值最長 128 個字元，作用於所選元件根元素及目前畫布寬度（窄螢幕可單獨設定覆寫，清空並套用即可移除覆寫）。
- 不接受選擇器、註解、`@media` / `@import`、外部資源 URL、`!important`、`display: none` 或自訂 CSS 變數。儲存、預覽或發布前須先套用或點擊「放棄修改」。
- 受支援屬性：`display`、`flex-direction`、`flex-wrap`、`justify-content`、`align-items`、`gap`、`padding`（及四個方向）、`margin`（及四個方向）、`width`、`min-width`、`max-width`、`min-height`、`font-size`、`font-weight`、`line-height`、`text-align`、`color`、`background-color`、`border`、`border-color`、`border-width`、`border-style`、`border-radius`、`box-shadow`、`grid-template-columns`、`flex`、`order`。

## 自訂 HTML / CSS / JavaScript 元件庫

在「新增元件」點擊「新增元件」或「管理元件庫」（開啟獨立分頁，不影響原頁面草稿）：

1. 在「原始碼」編輯 HTML 片段、CSS 與選用 JavaScript 函式主體；在「屬性定義」新增執行個體欄位（支援文字 `string`、數值 `number`、開關 `boolean`）：

   ```json
   {"title":{"type":"string","label":"Title","default":"Hello"}}
   ```

   例如 HTML 填 `<p></p>`，JavaScript 填 `root.querySelector('p').textContent = context.properties.title;`（接收 `root` 與含 `properties`、`mode` 的 `context`，可回傳 `update(context)` 與 `destroy()`）。
2. 點擊「執行預覽」檢查效果，再點擊「發布」儲存為不可變版本並加入元件面板。
3. 將元件拖入頁面並發布頁面。後續元件發布新版不會自動改動已發布頁面；需在頁面執行個體屬性中選擇新版本、點擊「使用所選版本並重設屬性」後重新發布頁面。
4. 「撤回元件」可停止現有執行個體渲染，並保留原始碼與歷史版本。

自訂元件在無 `allow-same-origin` 的沙箱 iframe 中執行，CSP 禁止 `fetch`/`XHR`、外部腳本與外部樣式，HTML 內禁止 `<script>`、`<style>`、`<form>`、內嵌頁面、外部資源與行內事件屬性。元件庫儲存在 `data/page-editor/components/`（單份原始碼上限 1 MiB，最多 128 個元件、單一元件最多 4,096 個版本與 64 個屬性）。

## 開發第三方外掛元件與接入頁面

外掛可透過公開契約貢獻元件與可編輯頁面，無需修改宿主或編輯器原始碼（完整驗收範例見主儲存庫 [`page-components` 測試夾具](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-app/src/test/resources/page-components)）：

| 擴充點 | 職責與限制 |
| --- | --- |
| 業務契約與 `sdk-tools` | 在獨立 API 套件宣告操作與設定 record，產生 JSON Schema、瀏覽器用戶端與 Java HTTP adapter |
| `PageContribution.components()` / `componentCategories()` | 宣告 `PageComponent`（穩定 `owner:type`、正整數設定版本、模組 URL、schema/defaults、`publicRenderable`、連接埠）與分類 `owner:local-id`；實作 `validate` 與選用的 `migrate` |
| 渲染與屬性編輯模組 | 匯出 `mount(root, context)`（及複雜屬性的 `mountProperties(root, context)`）；使用 `context.fetch`、`context.signal` 與 `context.onCleanup`，不暴露 GrapesJS 物件，禁止在 `edit`/`preview` 模式直接傳送業務寫入請求 |
| `PageContribution.pages()` | 宣告 `EditablePage`（頁面 ID、路徑、`regions`、`defaultProject`、`previewModuleUrl`、最多 16 份同 owner CSS）；前端呼叫 `/js/pixiv-pages.mjs` 的 `attachPage`（參考核心 `login/login-page.mjs`） |

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
        update(properties) { /* 更新顯示，不執行寫入操作 */ },
        destroy() { root.replaceChildren(); }
    };
}
```

每份頁面文件上限 1 MiB、512 個節點、24 層巢狀結構、256 條連接埠連線、標題 256 個字元。獨立瀏覽器與生命週期驗收命令：

```powershell
mvn -pl pixivdownload-plugin-page-editor,pixivdownload-app,pixivdownload-sdk-tools,pixivdownload-plugin-download-workbench -am package '-DskipTests' '-Dexec.skip=true'
node scripts/ci/page-editor-acceptance.mjs target/page-editor-acceptance
```

## 官方驗證碼外掛

官方 `captcha` 外掛提供 Turnstile、reCAPTCHA v2、ALTCHA SHA-256、ALTCHA PBKDF2、字元輸入和滑塊拼圖六種驗證元件。安裝、表單綁定、金鑰設定、官方測試金鑰與疑難排解見[操作驗證碼](/zh-hant/captcha)。

表單開發者透過 `OperationGuard.prepare(operation, subject, context)` 產生挑戰（不執行業務），前端透過 `createOperationTarget(operation, onChange, prepareRequest)` 註冊準備請求，待全部綁定元件完成後，透過 `target.request()` 取得證明，並在後端業務入口呼叫同一 owner 的 `OperationGuard.execute(...)` 驗證簽章並執行。
