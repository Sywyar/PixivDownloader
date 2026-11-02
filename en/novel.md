# Novels and narration

With the `novel` plugin enabled, you can save individual novels or entire series as TXT, HTML or EPUB, then read, search the full text, translate and listen to them in the local novel gallery. Translation requires the `translation` plugin; AI multi-character narration requires both the `ai` and `tts` plugins to be configured. The IDs below are format examples only. Replace them with actual works or series you can access.

## Download novels and series

### Individual novels

1. Open `http://localhost:6999/pixiv-batch.html` and select "Bulk Import Single Works".
2. Paste a novel URL:

   ```text
   https://www.pixiv.net/novel/show.php?id=12345678
   ```

   If you enter a numeric ID alone, put a `novel:` section header on a separate line above it:

   ```text
   novel:
   12345678
   ```

3. Choose a save format (TXT / HTML / EPUB) in the download settings, add the work to the queue and start downloading. You can also queue your own novels or bookmarks directly from [Quick access](/en/quick-access).

### Novel series and EPUB compilations

1. Select "Series mode" on the download page, paste a series URL and click "Parse and preview":

   ```text
   https://www.pixiv.net/novel/series/123456
   ```

2. Select "Queue all" or choose individual chapters to queue, then start downloading.

| Format | Features |
| --- | --- |
| TXT | Plain text without formatting |
| HTML | Preserves original formatting such as ruby annotations and headings; opens directly in a browser |
| EPUB | EPUB3 with a multilevel chapter table of contents, embedded cover and book metadata (description, tags and source URL) |

- **Create a compilation during download**: select "Generate series compilation" in the download settings and choose a format (EPUB is recommended). The file is saved as `novel-series-{seriesId}/{series title}.epub` (translated compilations use `{series title}_{language code}.epub`).
- **Create one at any time from the series contents page**: open `pixiv-series.html?type=novel&seriesId=...`, click "Download merged volume", and choose the **original text** or an existing **translation language** to generate and download an EPUB immediately from the current database state. When a translation language is selected, untranslated chapters fall back to the original text. To use one language throughout the book, complete "Translate whole series" first.

## Local reading and full-text search

Open the novel gallery:

```text
http://localhost:6999/pixiv-novel-gallery.html
```

You can filter by title, author, series or tags, and use **full-text search of the novel body** through the local index. Click a card to open `pixiv-novel.html?id=<id>`, read the text, view the series contents, and move to the previous / next chapter.

## Listen to novels (single-voice TTS)

Click "Listen" on the novel reader page to read the text aloud paragraph by paragraph, with automatic highlighting of the current paragraph, page scrolling and saved reading progress:

| Voice engine | Description |
| --- | --- |
| Browser engine (default) | Uses the browser / system speech service; available voices and network requirements depend on the browser and operating system |
| Online engine (Edge neural voices) | Calls Microsoft's online TTS through the backend proxy; requires network access |

## AI multi-character narration (administrator)

In desktop settings under "AI models", configure and enable both a "Text model" (an OpenAI-compatible language model) and a "TTS model" (local VoxCPM / CosyVoice or cloud TTS). The reader page can then use "Multi-character narration" (if either is disabled, this engine option is absent from "Listen"). First confirm that the model and TTS connections work. For analysis failures, check the text model response; for synthesis failures, check the TTS engine and reference audio settings.

- **Sentence attribution and playback**: the language model assigns each sentence in the entire chapter to the narrator or a character. Speech is synthesized with fixed character voices, played in order, and the next sentence is prefetched. The playback bar shows the current speaker and dialogue.
- **Lock narrator and character voices**: before the first analysis, you can preview and lock the narrator's base voice (a warm female voice by default). In the supporting-character panel, view or edit each character's gender, age and English voice description. Saving locks these settings so AI cannot overwrite them in later chapters; edits take effect for subsequent synthesis immediately.
- **Clone voices from character reference audio**: generate standard reference audio or upload WAV / MP3 files for any character, including the narrator, optionally with a transcript. This keeps voices consistent across sentences and chapters while retaining emotional variation. Changing reference audio automatically recalculates cached audio with the new voice. You can disable cloning or adjust generation limits in configuration.
- **Handle voice conflicts**: if new evidence in the text clearly conflicts with a locked character voice, the panel lists the conflict and offers "Accept suggestion / Keep current / Rewrite".
- **Segment length and cache**: `0` (the default) analyzes the entire chapter in a single request (overlong text is rejected with a prompt to reduce the scope). A positive integer batches analysis by accumulating paragraphs to approximately that many characters. Analysis results are cached locally, so replay does not require recalculation; use "Re-analyze" when needed.

## Translation engines (administrator)

Install and enable `translation` (its `novel-translation-contract` dependency is installed with it). Select an engine on the "Translation" page in desktop settings, save, and restart the backend when prompted. Manual translation in the reader, series translation and automatic translation after download all use the selected engine:

| Engine | Configuration and limits |
| --- | --- |
| AI model | Reuses the model, base URL and credentials saved by the `ai` plugin (supports local OpenAI-compatible services) |
| Youdao | Requires an application ID and application secret for the Youdao Zhiyun text translation API (a Youdao Dictionary client account does not work) |
| LibreTranslate | Requires the base URL of a self-hosted service (without `/translate`) and an optional API Key; you must provide the service and language models |

- Youdao and LibreTranslate accept target language codes (such as `en`, `ja` and `zh-CN`). They send batches by paragraph with at most 1,000 Unicode characters per request (a segment length of `0` uses 1,000; a positive setting uses the smaller value). They preserve Pixiv markers for images, page breaks and jumps, request titles and body text separately, and neither use nor learn from AI term mapping tables.
- AI credentials are managed by `ai`, Youdao and LibreTranslate credentials by `translation`, and speech credentials by `tts`.

## AI translation and term mapping tables (administrator)

After configuring and enabling a text model, click "Translate" on the novel reader or series contents page:

- **Target language**: prefilled from the current interface language by default; you can change it to any natural-language name.
- **Segment length**: `0` translates the entire text in one request. For long works, enter a positive integer (such as `5000`) to split at paragraph boundaries, translate each segment and join the results.
- **Existing translations**: choose "Overwrite (re-translate)" or "Skip".
- **Term mapping table**: stores "original term ↔ translations in each language" to keep proper names consistent. Works in a series share a same-named table by default; individual works use their own tables. You can also choose no table, switch to an existing table, or create / edit one. New proper names found by AI during translation are added automatically to the selected table for later chapters (the model may still deviate from the mappings, so check the translation).
- **Switch content language**: the "Content language" dropdown on reader and series pages switches between the original and existing translations independently of the interface language. Body text, chapter titles, series names and translated EPUB titles switch together; untranslated items fall back to the original. Listening also uses the selected language. Chapter titles and body text are translated in the same AI request. Series-name translation includes pairs of original and translated chapter titles in the same language, along with the term mapping table.
- **Translate an entire series**: translate chapters in order from the series page. You can hide the task in the background or cancel it (completed chapter translations are retained), and `{series title}_{language code}.epub` is generated when it finishes. With "Skip translated" selected, one lightweight request first resolves the target language name to a normalized language code; chapters with an existing translation are then skipped directly. Regenerating a series compilation also refreshes compilations for every existing translation language.
- **Automatic translation after download**: under "Novel settings" on the download page or in a scheduled task, select "Auto-translate newly downloaded novels" and specify a target language and segment length. Automatic translation always uses the work's / series' default term mapping table and runs in series chapter order in the background without blocking downloads. A successful download is marked complete; translation failure only adds "Completed (translation failed)" and does not affect the saved original text.

## Delete, manage in batches and check results

Administrators can delete an individual novel from the reader page, or use "Manage" in the novel gallery to select all filtered results, export a ZIP in batches (optionally deleting source files after export; progress appears in the sidebar's "Tasks"), add works to collections or delete them in batches. Deletion of application-managed files cannot be undone from the interface, but [deletion markers](/en/gallery#delete-works) remain to prevent downloading them again by default. Read-only source files registered through [Import external downloads](/en/pixiv-batch-downloader-import) are retained. The complete batch management rules are the same as those for the [Work gallery](/en/gallery).

After downloading, check both the local TXT / HTML / EPUB files and the novel gallery records. If files exist on disk but not in the gallery, first check that the current database and download directory belong to the same instance. Do not delete the database to try to rebuild it.
