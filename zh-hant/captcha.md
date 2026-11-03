# 操作驗證碼

官方 `captcha` 外掛為登入等受保護表單提供六種驗證元件：Turnstile、reCAPTCHA v2、ALTCHA SHA-256、ALTCHA PBKDF2、字元驗證碼（離線）和滑塊拼圖（離線）。一般發行包未預先安裝，可依需求從外掛市場安裝或使用離線完整包。將元件拖入相容表單並發布頁面後，前後端會強制核驗挑戰證明。

驗證碼用於提高自動化提交成本，不能取代網路層 DDoS 防護、反向代理限流或存取控制。相關外部請求說明見[網路存取與第三方服務](/zh-hant/network-access)。

## 驗證方式比較

| 元件 | 使用者互動 | 驗證位置 | 事前準備 |
| --- | --- | --- | --- |
| ALTCHA SHA-256（離線） | 點擊後等待瀏覽器完成 SHA-256 工作量證明 | 本機簽發挑戰並驗證簽章，無需第三方服務 | 首次啟用會自動產生本地簽署金鑰 |
| ALTCHA PBKDF2（離線） | 點擊後等待瀏覽器完成 PBKDF2/SHA-256 金鑰衍生證明 | 本機簽發挑戰並驗證簽章，無需第三方服務 | 與 SHA-256 共用本地簽署金鑰 |
| 字元驗證碼（離線） | 輸入圖片中的 5 位字母或數字（不區分大小寫）並確認 | Hutool 在本機產生圖片，伺服器端於記憶體中核對 | 無額外設定 |
| 滑塊拼圖（離線） | 拖曳滑塊（或用方向鍵 + Enter）將拼圖對準缺口並確認 | Tianai 在本機產生拼圖，伺服器端於記憶體中核對 | 無額外設定 |
| Turnstile 線上驗證 | 託管模式下點擊「開始驗證」以載入 Cloudflare 控制項 | 瀏覽器載入 Cloudflare，伺服器端呼叫 Siteverify | Cloudflare 帳號、Sitekey、Secret key 與允許的主機名稱 |
| reCAPTCHA v2 線上驗證 | 勾選核取方塊，依 Google 策略可能出現圖片辨識題 | 瀏覽器載入 Google 控制項，伺服器端呼叫 Siteverify | Google reCAPTCHA v2 Checkbox 公鑰、密鑰與主機名稱 |

!> 通常每個表單選擇一種元件即可。**同一表單放入多個驗證碼元件時，必須全部完成才能提交**，不會在某個線上服務無法使用時自動退回離線元件。

## 安裝並綁定至表單

1. 在[外掛管理](/zh-hant/plugin-management)中安裝並啟用「操作驗證碼」與「頁面編輯器」。
2. 若使用 Turnstile 或 reCAPTCHA v2，先在桌面「設定 → 操作驗證碼」中完成下方金鑰與主機名稱設定並儲存（離線元件無需預先設定）。
3. 開啟[頁面編輯器](/zh-hant/page-editor)，選擇登入頁等目標頁面，從左側「新增元件 → 驗證碼」拖入所需元件，確認右側「保護操作」已綁定至目標表單。
4. 點擊「發布」並確認所需的外部資源權限（僅儲存草稿不會啟用正式頁面的驗證碼保護）。
5. 用未登入視窗開啟正式登入頁，填寫使用者名稱與密碼後完成驗證碼，再點擊「登入」。

修改使用者名稱、密碼或「保持登入狀態」會清空當次證明；密碼錯誤、驗證失敗或挑戰過期後需重新驗證。

## 離線元件設定與說明

- **ALTCHA（SHA-256 / PBKDF2）**：首次啟用會自動產生 32 位元組隨機簽署金鑰，由宿主加密儲存在 `config/credentials/captcha.properties`（無到期時間，日常無需修改；手動替換須至少 32 個字元，清除憑證後需重新啟用外掛才會產生新金鑰）。Worker 腳本內建於外掛，無需外部 CDN；瀏覽器計算依賴 [Web Crypto `SubtleCrypto`](https://developer.mozilla.org/en-US/docs/Web/API/SubtleCrypto)，請透過 `localhost`、`127.0.0.1` 或 HTTPS 存取（一般區域網路明文 HTTP 下，瀏覽器可能停用 Web Crypto）。
- **字元輸入與滑塊拼圖**：圖片在本機產生，挑戰有效期限為 120 秒、僅可使用一次；點擊「換一張」會重設目前表單的全部驗證狀態。這兩種方式僅提供基本防自動化能力（滑塊不含行為軌跡模型），建議搭配請求限流使用；無障礙情境優先使用無需辨識圖片的 ALTCHA。

## 設定 Cloudflare Turnstile

1. 登入 [Cloudflare 控制台](https://dash.cloudflare.com/)，進入 **Turnstile → Add widget**，新增實際存取主機名稱（如 `localhost` 或 `download.example.com`，不含通訊協定、連接埠或路徑），模式選 **Managed**，建立後取得 Sitekey 與 Secret key。
2. 在桌面「設定 → 操作驗證碼」選擇 Cloudflare Turnstile，填寫**網站公鑰**（Sitekey）、**驗簽密鑰**（Secret key）和**預期主機名稱**（須與瀏覽器實際存取的主機名稱精確一致，切換 `localhost` 與 `127.0.0.1` 時需同步修改），關閉「允許官方測試設定」並儲存。
3. 在頁面編輯器發布綁定了 Turnstile 的頁面（修改公鑰、預期主機名稱或測試開關後，必須重新發布頁面；僅輪替密鑰無需重發頁面）。

**官方測試金鑰（僅用於整合測試提交流程，不提供防自動化保護）**：公鑰填 `1x00000000000000000000AA`，密鑰填 `1x0000000000000000000000000000000AA`，預期主機名稱填 `example.com`，開啟「允許官方測試設定」並重新發布頁面。正式上線前務必換回真實金鑰、關閉測試設定並重新發布。

## 設定 Google reCAPTCHA v2

1. 在 [Google reCAPTCHA 管理控制台](https://www.google.com/recaptcha/admin/create)建立 **v2 →「我不是機器人」核取方塊** 網站（勿選 v3 或 Invisible），新增實際存取主機名稱（本機測試填 `localhost`）。
2. 在桌面「設定 → 操作驗證碼」選擇 Google reCAPTCHA v2，填寫**網站公鑰**、**驗簽密鑰**與**預期主機名稱**，關閉「允許 reCAPTCHA 官方測試設定」並儲存。
3. 在頁面編輯器拖入「reCAPTCHA v2 線上驗證」並發布頁面。

**官方測試金鑰（僅用於整合測試，不提供實際保護）**：公鑰填 `6LeIxAcTAAAAAJcZVRqyHh71UMIEGNQ_MXjiZKhI`，密鑰填 `6LeIxAcTAAAAAGG-vFI1TnRWxMZNFuojJ4WifJWe`，預期主機名稱可留空（核驗 `testkey.google.com`），開啟「允許 reCAPTCHA 官方測試設定」並重新發布頁面。

## 疑難排解

| 現象或日誌錯誤碼 | 檢查與處理 |
| --- | --- |
| 編輯器有元件，正式登入頁沒有 | 確認已點擊「發布」而非僅儲存草稿，隨後重新整理正式頁面 |
| `CAPTCHA_CREDENTIAL_MISSING` / `CAPTCHA_CREDENTIAL_INVALID` | 依日誌指出的鍵名檢查金鑰是否為空或長度不足（切勿將金鑰貼入日誌或 Issue） |
| `CAPTCHA_CONFIG_INVALID` | 檢查公鑰、預期主機名稱與測試模式開關是否相符（測試金鑰必須搭配測試開關） |
| `PUBLISHED_POLICY_CHANGED` | 修改驗證碼設定後，重新在頁面編輯器發布對應頁面 |
| `CAPTCHA_REMOTE_UNAVAILABLE` 或控制項未載入 | 檢查瀏覽器與後端至 Cloudflare / Google 的網路與代理設定 |
| `HOSTNAME_MISMATCH` | 核對瀏覽器網址列主機名稱、廠商後台允許的網域與外掛「預期主機名稱」三者一致 |
| 加入 ALTCHA 後仍提示策略無法使用 | 檢查表單是否同時殘留未設定的 Turnstile 或 reCAPTCHA 元件；移除多餘元件後重新發布 |

停用或解除安裝 `captcha` 外掛不會繞過已發布頁面的驗證要求（受保護操作會維持拒絕）；如需取消驗證碼，必須在頁面編輯器中移除元件並重新發布頁面。

登入與邀請碼兌換共用宿主挑戰和提交協定，直接呼叫 API 也必須符合正式要求。只設定實際使用的線上方案，未使用的 Turnstile 或 reCAPTCHA 不必填入測試金鑰。新表單的動作關係設定見[頁面編輯器](/zh-hant/page-editor)。
