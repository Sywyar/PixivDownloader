# First Download

This tutorial walks through your first complete download using the most common scenario.

## Prerequisites

- PixivDownloader is installed and running
- [First-Time Setup](/en/first-setup) is complete

---

## New-User Onboarding (Auto, First Visit)

When you open the download page as an admin for the first time (Solo mode / logged-in admin in Multi mode), an interactive **cross-page onboarding** starts automatically and walks you through the entire flow from downloading to browsing:

1. First asks for your **name** and checks **network connectivity** from the backend to Pixiv (shows round-trip latency; if unreachable it suggests checking proxy / network)
2. Introduces the download page region by region — the **Cookie area / userscripts area / download modes (tabs)** — using "Batch Import Single Artworks" as the example
3. Guides you to **paste the example link → add to queue → review extra filters and download settings → start downloading**, highlighting the status bar and download queue so you can watch progress live
4. When the download finishes, jumps to the **gallery** (walking through views / search / filter / the work grid), then into the **artwork detail page** (images / actions / description / author / series & related)

You can **skip** the guide at any time, or replay it via the "**Guide**" button at the bottom-right of the download page (when a name is already set, it skips the name step and starts from the connectivity check). Guests in Multi mode never trigger it.

?> The **name** you enter in the guide is saved and used for the user card at the bottom of the gallery / novel gallery sidebar and for the greeting in system emails (scheduled-task notifications, mail tests); when left empty it falls back to "administrator."

The steps below are the manual version of the same flow — whether you follow the guide or do it yourself, you'll complete your first download.

---

## Step 1: Open the Download Page

In your browser, visit:

```
http://localhost:6999/pixiv-batch.html
```

In **Solo mode** a login dialog will appear. Enter the admin credentials you set during first-time setup.

?> If you don't remember the address, open GUI → Status page → click the "Batch Download" shortcut button.

---

## Step 2: Get Pixiv Cookie (Recommended, Not Required)

A cookie is your Pixiv login credential. With it configured you can:

- Download R-18 / R-18G content (without it such works are skipped)
- Use "Search Mode" and "Quick Fetch" features
- Download content that requires login

**You can download all-ages public works without a cookie.** If you just want to try things out, skip this step and configure it later.

### How to Get It

Pick any method below — all of them yield a usable result. Just choose whichever you're most comfortable with. Regardless of which method, first log in to [Pixiv](https://www.pixiv.net/) in your browser.

?> The backend only actually needs `PHPSESSID` (a normal logged-in Pixiv `PHPSESSID` looks like `12345678_xxxxxxxx`). A full cookie works too, but `PHPSESSID` alone is enough.

#### Method 1: Cookie-Editor Extension (Simplest)

1. Install the [Cookie-Editor](https://chromewebstore.google.com/detail/cookie-editor/hlkenndednhfkekhgcdicdfddnkalmdm) browser extension (Chrome/Edge)
2. Click the Cookie-Editor extension icon
3. Bottom right click **Export** → select **Netscape** format
4. Click "Copy Export" to copy all content
5. When pasting, set format to **Netscape**

#### Method 2: DevTools → Application → Cookies

1. On a Pixiv page press **F12** to open DevTools
2. Switch to the **Application** panel → left sidebar Storage → **Cookies** → select `https://www.pixiv.net`
3. In the right-side list, find the row whose **Name** is `PHPSESSID` and copy its **Value**
4. Assemble one line as `PHPSESSID=<the copied value>` (e.g. `PHPSESSID=12345678_xxxxxxxx`)
5. When pasting, set format to **Header String**

?> To bring more cookies at once, join multiple rows as `Name=Value; Name2=Value2` and still use **Header String** format. `PHPSESSID` is an HttpOnly cookie, but the Application panel still shows its value, so this method needs no userscript permission changes.

#### Method 3: DevTools → Network Request Header

1. On a Pixiv page press **F12** → switch to the **Network** panel
2. Refresh the page, then click any request to `www.pixiv.net` in the list (e.g. the top document request or an ajax request)
3. Under **Headers** find **Request Headers → `Cookie`** and copy its full value
4. When pasting, set format to **Header String**

?> The request-header `Cookie` is already in `name=value; name2=value2` form and contains `PHPSESSID`, so just paste the whole string.

#### Method 4: One-Click Cookie Import (Enhancement Toolbox Userscript)

Requires the "**Enhancement Toolbox**" userscript:
1. In `pixiv-batch.html`, click "**One-Click Import Cookie**" in the Cookie card
2. The script auto-opens a Pixiv page to read cookies and sends them back to the server — no manual copy-paste or format selection needed

!> **This method needs Tampermonkey to grant HttpOnly cookie access.** Pixiv's login token `PHPSESSID` is an HttpOnly cookie that userscripts cannot read by default, so "One-Click Import" will fail with a "PHPSESSID not detected" message. Go to **Tampermonkey → Settings → set Config mode to `Advanced` → Security → set "Allow scripts to access cookies" to `All`** before using it.

!> **Keeping this setting on `All` permanently is NOT recommended**: once on, **any other userscript you have installed can also read your login credentials**, risking account theft. Unless you trust every userscript you have installed, **only set it to `All` temporarily while using "One-Click Import", then change it back to `All except HttpOnly` right after a successful fetch**. If you'd rather not touch this permission, prefer Methods 2 or 3 (DevTools) — they also obtain the HttpOnly `PHPSESSID` and require no userscript permission changes.

### Pasting the Cookie

> The manual paste steps below apply to Methods 1/2/3; Method 4 (One-Click Import) sends it back automatically.

1. On `pixiv-batch.html`, locate the "**Cookie**" card (gray collapsible card) at the top, click to expand
2. Set the format to match how you obtained it: Method 1 (Cookie-Editor export) → **Netscape**; Methods 2, 3 (DevTools) → **Header String**
3. Paste the copied content
4. Click "**Save Cookie**"

?> Cookies are long-lived — typically only need re-fetching every few months. Logging out of Pixiv or changing your password invalidates the cookie immediately.

---

## Step 3: Download an Artwork

### Find an Artwork on Pixiv

Find an artwork you want to download on Pixiv and copy the URL from the browser address bar, e.g.:

```
https://www.pixiv.net/artworks/12345678
```

Or just copy the numeric ID part (`12345678`).

### Paste and Download

1. On `pixiv-batch.html`, switch to the "**🎨 Batch Import Single Artworks**" tab at the top
2. Paste the URL in the text box (one per line, can paste multiple at once)
3. Click "**Fetch Info**" — wait a few seconds, the artwork preview will appear
4. After confirming the info is correct, click "**Start Download**"

---

## Step 4: Check Download Progress

Once the download starts, task entries appear in the "**Download Queue**" area below:

| Status | Meaning |
|--------|---------|
| ⏳ Pending | Queued, waiting to start |
| 🔄 Downloading | Files are being downloaded |
| ✅ Completed | Download succeeded |
| ⏩ Skipped, already exists | This artwork was downloaded before, no need to repeat |
| ❌ Failed | Download error; click the entry to see the reason |

---

## Step 5: Find the Downloaded Files

After the download finishes, files are saved in the `pixiv-download/` folder under the **program working directory**:

```
(program directory)/
└── pixiv-download/
    └── 123456/          ← subdirectory named by artwork ID
        ├── 123456_p0.jpg    ← first image
        ├── 123456_p1.jpg    ← second image (multi-image artwork)
        └── ...
```

**Where is the program directory?**

- **Windows installer**: the directory chosen during installation, default `C:\Users\<Username>\PixivDownload\`
- **JAR package**: the folder where you ran the `java -jar` command
- **GUI mode**: displayed in GUI → Status page → "Download Directory" field

?> You can change the save location by modifying `download.root-folder` in `config.yaml`; absolute paths are supported.

---

## Step 6: Browse in the Gallery

Open the gallery page to view all downloaded artworks (supports search, filter, collections, etc.):

```
http://localhost:6999/pixiv-gallery.html
```

See [Artwork Gallery](/en/gallery) for detailed usage.

---

## FAQ

### "Fetch Info" gives an error

- **"Cookie required"**: This artwork requires login to view; configure your cookie first (see Step 2)
- **"Proxy connection failed"**: Check if the proxy is running and verify the proxy address and port in `config.yaml`
- **"Artwork does not exist"**: The artwork may have been deleted or set to private by the author

### Download completed but can't find the files

Check the `download.root-folder` config item in `config.yaml` — it determines where files are saved. If set as a relative path, the actual location is "program working directory + that path."

### Want to download more in bulk

- Paste multiple URLs at once → see [Batch Download](/en/batch-download)
- Download all works by an artist → see [User Download](/en/user-download)
- Search and download by keyword → see [Search](/en/search)
