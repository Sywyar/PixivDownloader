# Your first download

This tutorial covers fetching and downloading one Pixiv work, then confirming its local files and gallery entry. Complete [installation and startup](/en/installation) and [first-time setup](/en/first-setup) before starting. Work IDs below illustrate the format only; replace them with actual works you are allowed to access.

## 1. Open the download workbench

Open `http://localhost:6999/pixiv-batch.html` in your browser, or click Batch download on the desktop GUI's Status page, and sign in as the administrator. A new environment opens the new layout, `pixiv-batch-alt.html`, by default. You can switch between the new and old layouts at the top of the page; the preference is saved automatically.

The first administrator login starts an onboarding guide across pages. It introduces your display name, the Pixiv connectivity check, cookies and userscripts, queuing a single work for download, and browsing the gallery. The name you enter is used on the gallery sidebar card and in greetings in system notification emails; leaving it blank displays Administrator by default. You can skip the guide at any time or reopen it through Usage guide at the bottom right of the page.

## 2. Save a Pixiv cookie if needed

Public all-ages illustrations can be downloaded without a cookie. **R-18 / R-18G works, search downloads, Quick Fetch and works visible only after login require a cookie containing a valid `PHPSESSID`**; restricted works are skipped without one. Sign in to [Pixiv](https://www.pixiv.net/) in your browser, then obtain the cookie using one of these methods:

### Method A: browser developer tools (recommended; no extra extension permissions)

1. Press `F12` on a signed-in Pixiv page to open developer tools.
2. **Application panel**: expand `Storage → Cookies → https://www.pixiv.net` on the left, find `PHPSESSID`, copy its `Value`, and form `PHPSESSID=the_copied_value`.
3. Alternatively, in the **Network panel**, refresh the page, select any request to `www.pixiv.net`, and copy the full value of `Request Headers → Cookie`.
4. Expand Cookie on the download page, choose **Header String**, paste the content and click Save cookie.

### Method B: Cookie-Editor extension

1. Install [Cookie-Editor](https://chromewebstore.google.com/detail/cookie-editor/hlkenndednhfkekhgcdicdfddnkalmdm), then click its icon on a signed-in Pixiv page.
2. Click `Export → Netscape` to copy the exported content.
3. Choose **Netscape** in the download page's Cookie section, paste and save.

### Method C: the userscript's one-click cookie import

1. Install and enable the Experience Enhancement Toolbox script (see [userscripts](/en/userscripts)).
2. In Tampermonkey, open Settings → Config mode: Advanced → Security, and temporarily set Allow scripts to access cookies to `All`.
3. Click One-click cookie import in the download page's Cookie section. The script reads `PHPSESSID` and sends it back to the backend.

!> `PHPSESSID` is an HttpOnly cookie that Tampermonkey cannot read by default. Setting the permission to `All` lets every Tampermonkey script in your browser read your login credentials. Change it back to `All except HttpOnly` immediately after a successful import, or prefer the developer-tools method above. Do not include cookies in screenshots, logs or Issues.

## 3. Add a work and start downloading

1. Choose Bulk Import Single Works on the download page.
2. Paste a work URL or a numeric illustration ID, one per line:

   ```text
   https://www.pixiv.net/artworks/12345678
   ```

3. Click Fetch information and confirm the work's title and type in the preview.
4. Add the work to the queue and click Start download.
5. Check Download queue: waiting, downloading, completed, skipped because it already exists, and failed entries have separate status labels. Click a failed entry to see the specific reason.

## 4. Check local files and the gallery

Check the current Download directory on the desktop GUI's Status page. The default is `pixiv-download/` under the application's working directory, depending on the working directory at startup or the directory specified for a collection:

```text
pixiv-download/
└── 12345678/
    ├── 12345678_p0.jpg
    └── 12345678_p1.jpg
```

The download root is controlled by `download.root-folder` in `config/config.yaml`. Changing the setting does not move existing files; follow [storage and backup](/en/storage) to relocate a directory. With the `gallery` plugin enabled, open `http://localhost:6999/pixiv-gallery.html` to browse the newly registered work (see [artwork gallery](/en/gallery)).

## Troubleshoot common problems

- **A cookie is required or R-18 works are skipped**: confirm that you saved a cookie containing a valid `PHPSESSID` and enabled R-18 display for that Pixiv account on the website.
- **The proxy connection fails**: confirm that the proxy application is running and check `proxy.host` and `proxy.port` in desktop settings. Being able to open Pixiv in your browser does not prove the backend proxy is configured correctly.
- **The work is reported as missing**: check the original Pixiv site to see whether it was deleted, made private or restricted to My pixiv users.
- **The download completed but you cannot find the files**: check the actual working and download directories on the desktop GUI's Status page, or check whether you selected a local collection with a custom directory for that download.

To download more content, continue with [URL batch download](/en/batch-download), [artist batch download](/en/user-download), [search download](/en/search) or [novels and narration](/en/novel).
