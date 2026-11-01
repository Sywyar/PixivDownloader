# 操作驗證碼插件

官方 `captcha` 插件提供 Turnstile、ALTCHA SHA-256 與 ALTCHA PBKDF2 三種表單驗證元件。插件按需安裝，標準發行包不預先安裝，完整離線包包含。將元件放入相容表單並發佈後，伺服器會強制驗證證明。

驗證碼用於增加自動化提交的成本，不能取代網路層 DDoS 防護、反向代理限流或存取控制。外部連線見[網路存取與第三方服務](/zh-hant/network-access)。

## 選擇驗證方式

| 方式 | 使用者互動 | 驗證位置 | 所需設定 |
| --- | --- | --- | --- |
| Turnstile 線上驗證 | 託管模式下，點擊「開始驗證」後由 Cloudflare 控件決定是否需要進一步互動 | 瀏覽器載入 Cloudflare，伺服器向 Cloudflare 驗證票據 | Cloudflare 帳號、Sitekey、Secret key 及允許的主機名稱 |
| ALTCHA SHA-256 | 元件名為「ALTCHA 離線驗證」；點擊後等待計算，完成時顯示「已完成驗證」 | 瀏覽器完成 SHA-256 工作量證明，本機服務簽發挑戰並驗簽 | 首次啟用自動產生本機簽章金鑰，無需第三方帳號 |
| ALTCHA PBKDF2 | 元件名為「ALTCHA PBKDF2 離線驗證」；互動與 SHA-256 相同 | 瀏覽器透過 PBKDF2/SHA-256 金鑰衍生完成工作量證明，本機服務驗簽 | 與 SHA-256 共用本機簽章金鑰，無需第三方帳號 |

兩種 ALTCHA 都不要求辨識圖片或輸入字元。「離線」指不依賴第三方驗證碼服務，瀏覽器仍需連線至目前的 PixivDownloader 實例。耗時取決於演算法、參數與裝置，不能僅憑 PBKDF2 名稱判斷速度或防護較佳。機制見 [ALTCHA 工作量證明說明](https://altcha.org/docs/integration/proof-of-work-captcha/)。

通常每個表單選擇一種方案。**同一表單放入多種驗證碼時，全部都必須完成**，並非擇一使用；Turnstile 無法使用時不會自動改用 ALTCHA。

## 安裝並綁定表單

1. 在[插件管理與市場](/zh-hant/plugin-management)安裝並啟用「操作驗證碼」及「頁面編輯器」。
2. 離線方案保留自動產生的金鑰即可；Turnstile 請先依下文設定。
3. 開啟[頁面編輯器](/zh-hant/page-editor)，選擇登入頁等相容頁面，從「驗證碼」分類將元件拖入表單。確認其保護操作已綁定目標表單；若放在表單外或綁定有歧義，先修正再發佈。
4. 發佈頁面並核對要求的資源權限。僅儲存草稿不會改變正式頁面的驗證要求。
5. 以未登入視窗開啟正式頁面。登入時先填寫使用者名稱、密碼，再點擊「開始驗證」；全部驗證完成後點擊登入。

編輯與預覽中的按鈕不執行真實驗證。正式頁面在證明未完成前不提交登入請求，伺服器也會在檢查密碼及建立工作階段前驗證證明。修改使用者名稱、密碼或保持登入選項會清除證明；密碼錯誤、驗證失敗或過期後須重新驗證。

插件支援熱載入，正常安裝與啟用後重新整理頁面即可。若更新開發環境中已載入的 Java 類別或宿主程式碼，須重新啟動開發實例後再整理頁面。

## 設定離線驗證

兩種 ALTCHA 共用「ALTCHA 簽章金鑰」。首次啟用自動產生 32 位元組隨機值，由宿主加密儲存在目前實例的 `config/credentials/captcha.properties`；若自訂設定根目錄，路徑會隨之變更。重新啟用或啟動沿用已有金鑰，沒有自動到期時間，通常不需手動修改。

手動替換至少需要 32 個字元；密碼欄位留空儲存會保留原值。清除已儲存憑據後，執行中的離線驗證會拒絕操作，下次重新啟用插件才產生新金鑰。已有無效值、儲存損壞或寫入失敗不會改用暫時金鑰。

Worker 隨插件提供，無需外部 CDN。計算依賴 Web Crypto，請透過 HTTPS 或瀏覽器認可的本機安全來源存取；普通 HTTP 的區域網路 IP 可能無法使用該 API。參見 [Web Crypto 安全環境要求](https://developer.mozilla.org/en-US/docs/Web/API/SubtleCrypto)。

## 申請與設定 Turnstile

### 取得 Sitekey 與 Secret key

1. 登入 [Cloudflare 控制台](https://dash.cloudflare.com/)，開啟 **Turnstile → Add widget**。
2. 填寫控件名稱，例如 `PixivDownloader`。
3. 在 Hostname management 加入實際存取的網域，例如 `download.example.com`。
4. 選擇 **Managed（託管）**，由 Cloudflare 決定是否需要使用者互動。
5. 點擊 Create，複製該控件的 Sitekey 與 Secret key。Sitekey 是公開識別碼，Secret key 僅供伺服器驗簽，請妥善保管。[官方建立教學](https://developers.cloudflare.com/turnstile/get-started/widget-management/dashboard/)

網站不必經過 Cloudflare 代理；瀏覽器控件及伺服器驗簽仍須連線 Cloudflare。[官方整合說明](https://developers.cloudflare.com/turnstile/get-started/)

### 填入軟體設定

開啟「設定 → 操作驗證碼」：

| 設定項目 | 填寫內容 |
| --- | --- |
| Turnstile 網站公鑰 | 控件的 Sitekey |
| Turnstile 驗證金鑰 | 同一控件的 Secret key |
| Turnstile 預期主機名稱 | 實際存取的主機名稱，例如 `download.example.com` |
| 允許官方測試設定 | 使用自行申請的金鑰時關閉 |

主機名稱不含協定、連接埠或路徑，不能填 `https://download.example.com:6999/login.html`。Cloudflare 的允許主機與插件的預期主機都須設定；插件要求與驗證回應中的主機名稱精確一致。[主機名稱要求](https://developers.cloudflare.com/turnstile/additional-configuration/hostname-management/)

儲存後，將 Turnstile 元件放入表單並發佈。變更網站公鑰、預期主機名稱或測試模式後，已有頁面也須重新發佈。僅更換驗證金鑰會使舊挑戰失效，須重新驗證。一般設定位於 `config/plugins/captcha.properties`，金鑰位於加密憑據檔案，頁面文件不儲存金鑰。

### 在本機使用自行申請的金鑰

另外建立開發用控件，依瀏覽器位址填寫：

| 瀏覽器位址 | Cloudflare 允許主機 | 插件預期主機 |
| --- | --- | --- |
| `http://localhost:6999` | `localhost` | `localhost` |
| `http://127.0.0.1:6999` | `127.0.0.1` | `127.0.0.1` |
| `https://download.example.com` | `download.example.com` | `download.example.com` |

變更連接埠不影響主機名稱；在 `localhost` 與 `127.0.0.1` 之間切換則須調整設定並重新發佈。**本機執行不需開啟「允許官方測試設定」。** Cloudflare 允許開發控件加入本機主機名稱，建議正式控件不允許這些位址。[官方本機測試說明](https://developers.cloudflare.com/turnstile/troubleshooting/testing/)

### 使用官方測試金鑰檢查整合

若只檢查元件及提交流程，可使用 Cloudflare 公開的「始終通過」測試值：

| 設定項目 | 測試值 |
| --- | --- |
| Turnstile 網站公鑰 | `1x00000000000000000000AA` |
| Turnstile 驗證金鑰 | `1x0000000000000000000000000000000AA` |
| Turnstile 預期主機名稱 | `example.com`，本插件的官方測試約定 |
| 允許官方測試設定 | 開啟 |

儲存並重新發佈後再測試。此處 `example.com` 用來核對測試回應，不需擁有或開啟該網域，頁面仍可在 `localhost` 執行。測試公鑰須搭配測試金鑰。這些值來自 [Cloudflare 測試文件](https://developers.cloudflare.com/turnstile/troubleshooting/testing/)，**不提供實際防自動化保護**。正式使用前，換成自己的金鑰及實際主機名稱、關閉測試設定並重新發佈。

## 無法驗證時的檢查項目

| 現象或日誌 | 處理方式 |
| --- | --- |
| 編輯器有元件，正式頁面沒有 | 確認發佈成功，而非僅儲存草稿，再重新整理 |
| `CAPTCHA_CREDENTIAL_MISSING` / `CAPTCHA_CREDENTIAL_INVALID` | 依日誌中的設定鍵檢查憑據；請勿把金鑰放入日誌或問題回報 |
| `CAPTCHA_CONFIG_INVALID` | 檢查指出的公鑰、主機名稱或測試模式，確保金鑰組合相符 |
| `PUBLISHED_POLICY_CHANGED` | 核對設定後重新發佈對應頁面 |
| `CAPTCHA_REMOTE_UNAVAILABLE` 或控件載入失敗 | 檢查瀏覽器及伺服器連線 Cloudflare 的網路、代理及攔截規則 |
| `HOSTNAME_MISMATCH` | 比對瀏覽器位址、Cloudflare 允許主機及插件預期主機 |
| 加入 ALTCHA 後仍有策略無法使用 | 檢查表單是否也要求未設定的 Turnstile；僅需離線驗證時移除它並重新發佈 |
| 完成後再次要求驗證 | 檢查是否修改輸入、提交錯誤密碼、挑戰過期或插件設定變更 |

必要登入驗證策略無法使用是彙總錯誤，應查看前面的原因與設定鍵。停用或移除插件不會取消已發佈的要求，受保護操作仍會被阻擋。若要取消要求，請修改頁面並重新發佈。
