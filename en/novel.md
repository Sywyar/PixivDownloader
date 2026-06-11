# Novel Download

PixivDownloader supports downloading Pixiv novels in TXT / HTML / EPUB formats, plus novel series compilations.

---

## Downloading a Single Novel

### Method 1: URL Batch Import

1. Open `pixiv-batch.html` → switch to the "**🎨 Batch Import Single Artworks**" tab
2. Paste a novel link:
   ```
   https://www.pixiv.net/novel/show.php?id=12345678
   ```
3. Or paste a bare ID with a `novel:` section header:
   ```
   novel:
   12345678
   ```
4. Select format (TXT / HTML / EPUB)
5. Start download

### Method 2: Quick Fetch (Your Own Novels / Bookmarks)

Open the "**⚡ Quick Fetch**" tab → click "My Bookmarks (Novels)" or "My Works (Novels)" and add to queue directly.

---

## Downloading a Novel Series

1. Open `pixiv-batch.html` → switch to the "**📚 Series Mode**" tab
2. Paste a series link:
   ```
   https://www.pixiv.net/novel/series/123456
   ```
3. Click "**Parse & Preview**" to display all chapters in the series
4. Choose "**Add All to Queue**" or select individual chapters
5. Start download

### Generating Series Compilation (EPUB)

Generate an EPUB compilation during series download:

- Expand download settings, check "**Generate series compilation**", choose compilation format (EPUB recommended)
- The compilation file is saved in the `novel-series-{seriesId}/` directory, filename format: `{Series Title}.epub` (AI-translated compilation: `{Series Title}_{language code}.epub`)

EPUB compilation features:
- EPUB3 spec, multi-level TOC (series → chapters)
- Embedded series cover image
- Book metadata (description, tags, source link)

?> EPUB format is recommended for reading — best chapter TOC and layout.

### Download Compilation Anytime from Series Page

On the novel series page (`pixiv-series.html?type=novel&seriesId=...`), the top-right action area has a new "**Download Merged Volume**" button:

- Clicking opens a dialog to select the language: **Original**, or any translation language already available for this series;
- Choosing a non-original language shows an inline warning: the compilation only uses the selected language for **already-translated** chapters, with **untranslated chapters falling back to original**; to get the full volume in the selected language, run "Translate Whole Series" first, then re-download;
- After confirmation, the backend generates the latest EPUB compilation from the current database state and triggers a browser download — no need to check "Generate series compilation" beforehand.

---

## Format Overview

| Format | Characteristics |
|--------|----------------|
| **TXT** | Plain text, best compatibility, no formatting |
| **HTML** | Preserves original formatting (Ruby furigana, headings, etc.), can open in browser |
| **EPUB** | Suitable for e-book readers (Kindle, Apple Books, etc.), supports TOC and cover |

---

## Local Reading

After downloading, browse in the novel gallery:

```
http://localhost:6999/pixiv-novel-gallery.html
```

The gallery supports search by title, author, series, and tag, plus **full-text body search** (based on a local FTS5 index — you can search text content within novels).

### Online Reading

Click a novel thumbnail to enter `pixiv-novel.html?id=<id>`:
- View text, tags, author, series chapter navigation
- Previous / Next chapter quick navigation

---

## Listen (Text-to-Speech)

Click the "**🎧 Listen**" button on the novel reading page to read the text aloud paragraph by paragraph.

Two voice engines:

| Engine | Characteristics |
|--------|----------------|
| **Browser engine** (default) | Fully offline, instant, no server resources consumed; voice quality depends on system voice packages |
| **Online engine (Edge neural)** | Microsoft online TTS, natural quality, rich language support; requires network (via backend proxy) |

Playback controls:
- Play/Pause, Previous/Next paragraph, Stop
- Current paragraph highlighted and auto-scrolled
- Progress bar for jumping to any position
- Voice selection and speed adjustment

Reading progress is automatically saved — reopening the same novel continues from where you left off.

---

## AI Multi-Voice Narration (beta · admin only)

> **This feature is currently beta — usable but not yet stable; synthesis failures or inconsistent timbre may occur.**

Click "**🎭 Multi-voice narration**" on the novel page to have an LLM attribute every sentence of the chapter to a speaker (narrator / individual characters); each character is synthesized line by line with a fixed voice profile and played continuously — different from the single-voice **Listen** above. Requires an LLM and a narration engine. **If either is not configured, this engine option won't appear under Listen.**

- **Line-by-line playback with follow-along**: sentences are synthesized and played in order with prefetching; the **paragraph of the current sentence is highlighted and auto-scrolled**, and the bar shows a "Speaker: sentence" subtitle.
- **Narrator voice**: the settings dialog before the first analysis lets you pick a voice for this work's narrator (warm female / calm male / neutral, with preview); once confirmed the narrator voice is **locked** so the AI won't rewrite it, avoiding cross-chapter drift. If you don't pick one, the work's existing / default narrator is used (default is warm female).
- **Cast & voices**: click "🎭" on the bar to open a panel listing each character's gender / age / English voice profile; you can **edit a character's voice and save** it (saving marks it "Locked" so the AI won't overwrite) and preview individually. Edited voices take effect on subsequent synthesis **immediately** without re-analysis.
- **Character reference-voice cloning**: in the panel you can **generate a "standard voice" with one click** or **upload a real reference clip** (wav/mp3, with an optional transcript) for any character. Once a reference exists, that character's lines are synthesized by **cloning that reference's timbre** — more consistent across sentences and chapters while per-line emotion still applies; the reference can be previewed and deleted, and changing it automatically recomputes cached audio with the new voice. Cloning can be turned off or capped in the configuration.
- **Voice conflicts**: when the text clearly contradicts a locked voice, the panel lists the conflict with **Adopt suggestion / Keep current / Rewrite** choices.
- **Segment size**: same meaning as in AI translation — `0` (default) analyzes the whole chapter in one call (fullest context, most accurate attribution); a number batches by paragraph up to roughly that many characters (fewer tokens per call).
- The per-sentence attribution is **cached** so replays don't re-analyze; use "Re-analyze" in the settings to recompute. Very long text is rejected for one-shot analysis with a prompt to narrow the scope.

---

## AI Translation (Admin)

Click "**AI Translation**" to the left of the "Listen" button on the novel reading page to translate the body into a specified language and save locally — view directly afterwards without re-translating.

> Requires configuring and enabling an LLM (OpenAI-compatible protocol) in the GUI "AI Model" config page under the "Text Model" tab. **If not configured, the "AI Translation" button won't appear on novel / series detail pages.**

Translation dialog:

- **Target language**: pre-filled based on the current UI language (Chinese UI → "Simplified Chinese", English UI → "english"); freely editable to any language; the AI checks if the language exists and prompts if not.
- **Segment size**: `0` means translate the whole piece in one go; a number (e.g. `5000`) splits long text into segments by accumulating paragraphs up to about that many characters, translating segment by segment and concatenating — good for very long texts.
- **Already translated to that language**: choose "Overwrite & re-translate" or "Skip."

After confirming, a progress dialog appears: shows real-time elapsed time, with "Hide" and "Cancel" buttons at the bottom. "Hide" only closes the dialog while translation continues in the background — clicking "AI Translation" again while in progress re-shows the current progress; "Cancel" aborts the current request and discards frontend results (the backend may still write to the database and cannot be intercepted). After a chapter is successfully translated, if that novel belongs to a series, the language-variant compilation is automatically regenerated, consistent with the "Translate Whole Series" flow below.

### Glossary (Ensuring Consistent Terminology)

To avoid proper nouns being translated differently across paragraphs / chapters, the translation dialog includes a "**Glossary**" option (below "Already translated to that language").

- A glossary is a set of "**source → target language translation**" mappings. The default is the work's glossary: **novels in a series share the same glossary** (default named after the series), **standalone novels each have their own glossary** (default named after the novel name); you can also choose "Don't use", pick another existing glossary, or "+ New glossary."
- With a glossary selected, its translations are sent along with the translation request for the AI to follow, ensuring consistent terminology.
- When the AI encounters new proper nouns not yet in the glossary, it reports back the translations and they are **automatically merged into the selected glossary** for reuse by subsequent segments, subsequent chapters, and future retranslations — the glossary grows as you translate, becoming more and more consistent.
- Click "**Edit**" next to the option to open three dialogs in sequence: first rename the glossary and select or add target languages, then for each selected language use "**source ↔ translation**" paired input boxes to add / remove entries.

?> When translating an entire series, all chapters share the same glossary, and newly discovered translations accumulate in real time for subsequent chapters, keeping the whole series's terminology consistent.

### Switching Content Language

The novel reading page and series page have a new "**Content Language**" dropdown to switch between original and existing translation languages for viewing the body. This switch is **independent of the UI language** (it only changes the body display, not the interface) and is remembered; "Listen" will also read the currently selected language's translation.

When switching to a translation language, titles also switch to the translated versions:

- Novel detail page: the novel title switches to the translated novel title;
- Series page: the top series name + each chapter card title all switch to translated titles;
- Language-variant compilation (EPUB): the series name + each chapter title also use the translated versions.

Chapters / series names not yet translated fall back to the original title.

**Title translation context strategy:**

- Chapter titles are translated in the **same AI request** as the chapter body (shared context + same glossary), ensuring character names / location names / setting terms in the title use the same translation as the body.
- Series name translation is a standalone request (no "body" to attach), but the request carries: the series's already-translated chapter title pairs (original → translated) in the same target language as naming / style samples, plus the currently selected glossary, keeping the series name consistent with chapter title terminology.

### Translate Whole Series

Click "**Translate Whole Series**" on the series page to translate chapter by chapter into a specified language (dialog same as single-chapter, with option to skip already-translated chapters). After completion, an independent language-specific series compilation `{Series Title}_{language code}.epub` is generated: already-translated chapters use the translation, untranslated chapters keep the original — **the original compilation is unaffected**.

The whole process shows a progress dialog: three tips at the top ("Don't close this tab / Individual chapters may take a long time / Cancel behavior explanation"), with current chapter index, success / skipped / failed stats for this run, and elapsed time. You can "Hide" to let translation continue in the background, or "Cancel" to stop subsequent chapters; after canceling, already-translated chapters remain in the local database, and the language compilation is still generated. In "Skip already-translated" mode, a small AI request first resolves the entered target language name to a canonical language code (dialog shows "Identifying language code" status), then chapters with existing translations in that language are directly skipped — avoiding a full translation just for language identification.

?> Regenerating the series compilation also refreshes all existing translation language compilations, replacing already-translated chapters within them.

### Download-and-Auto-Translate (Batch / Scheduled Task · Admin)

Check "**Auto-translate newly downloaded novels**" under the "**Novel Settings**" section on the batch download page (`pixiv-batch.html`) or in scheduled tasks, with a **target language** and **segment size** set alongside (same meaning as the "AI Translation" dialog above): each newly downloaded novel will then be automatically translated into the target language — no need to manually click "AI Translation" per novel.

- Auto-translation always uses that novel / series's **default glossary** (auto-created on demand); you cannot specify a different glossary here. For fine-grained terminology control, use the "AI Translation" dialog on the novel reading page to select a glossary.
- During series download, if "Generate series compilation" is also checked, the translation compilation is auto-regenerated after each book is translated.
- Translation happens in the background and **does not block downloads**: the download queue and scheduled task "Current round queue details" show statuses like "**AI Translating**", "**Waiting for previous series novel translation to finish, N remaining**" (same series translates sequentially by chapter order to keep terminology consistent).
- **Download success counts as success**: translation failures only note "complete (translation failed)" on that entry without affecting the downloaded novel file.
- Admin only; requires the LLM to be configured and enabled in the GUI "AI Model" config page (OpenAI-compatible protocol). Translation results can be viewed via the "Content Language" dropdown on the novel reading page.

---

## Deleting Novels (Admin)

Like the artwork gallery, admins can delete downloaded novels: click the red "**Delete**" button on the novel reading page, or use "**Batch Management**" in the novel gallery to multi-select and batch-delete via the "Actions" menu. Deletion removes the local body / cover files and derived data; files are **unrecoverable**. Like artworks, deleted novels keep a [deletion mark](/en/gallery?id=deleting-artworks) — invisible to the gallery, but "skip downloaded" will skip them as "downloaded before, but deleted" unless "Allow re-downloading deleted works" is checked in download settings.

The novel gallery's batch management is identical to the artwork gallery: supports "Select all results" for whole-filter selection, and an "Actions" menu with **Export** (packaging options, archive format, optional delete-after-export; packaged in background with progress tracked in the "Tasks" panel and downloadable), **Add to collection**, and **Delete**. See [Artwork Gallery](gallery.md) for details.

---

## Related Pages

- Novel gallery: `http://localhost:6999/pixiv-novel-gallery.html`
- Single novel reading: `http://localhost:6999/pixiv-novel.html?id=<id>`
