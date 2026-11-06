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

- **Create a compilation during download**: select "Generate series compilation" in the download settings and choose a format (EPUB is recommended). The file is saved as `novel-series-{seriesId}/{series title}.epub` (translated compilations use `{series title}_{language code}@{engine}.epub`).
- **Create one at any time from the series contents page**: open `pixiv-series.html?type=novel&seriesId=...`, click "Download merged volume", and select the reading language (original text or an existing translation) and translation engine separately to generate and download an EPUB immediately from the current database state. When a translation language is selected, untranslated chapters fall back to the original text. To use one language throughout the book, complete "Translate whole series" first.

## Local reading and full-text search

Open the novel gallery:

```text
http://localhost:6999/pixiv-novel-gallery.html
```

You can filter by title, author, series or tags, and use **full-text search of the novel body** through the local index. Click a card to open `pixiv-novel.html?id=<id>`, read the text, view the series contents, and move to the previous / next chapter.

The reader toolbar keeps Back, Listen, Translate and Favorite within reach. Open More actions to change the interface language or appearance, or open the original novel on Pixiv. Administrators can also export or delete the novel from this menu; deletion still requires confirmation.

Administrators can choose Export from the reader's More actions menu, select the reading language and translation engine separately, and download a single EPUB directly. Export creates no archive task or temporary archive and leaves downloaded source files unchanged.

For batch export, select works in the novel gallery. Language and engine are separate choices, alongside grouping and ZIP options. Translations use the formats of existing local documents, or EPUB when no local document exists. Only translation combinations saved for every selected novel are offered; untranslated fields retain the original text.

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

Youdao and LibreTranslate split long text using their own request settings and combine short segments into batches. Line breaks and Pixiv image, page and jump markers are preserved. Splits prefer sentence or whitespace boundaries; an overlong word is split by Unicode code points without breaking surrogate pairs. Titles and body text use separate requests, without AI term glossaries.

Desktop Settings > Translation shows request parameters for the selected engine. The table lists client defaults. Prefix each key with `translation.youdao.` or `translation.libretranslate.`. Save and restart the backend.

| Parameter | Youdao default | LibreTranslate default | Allowed range |
| --- | --- | --- | --- |
| `request-chars` | 5000 | 5000 | Youdao: 1–5000; LibreTranslate: 1–1,000,000 |
| `batch-size` | 50 | 10 | 1–100 |
| `request-interval-ms` | 1000 ms | 0 ms | 0–60,000 ms |
| `request-concurrency` | 1 | 1 | 1–32 |
| `max-retries` | 3 | 3 | 0–10 |
| `read-timeout-seconds` | 120 s | 120 s | 1–3600 s |
| `use-proxy` | false | false | true / false |


The character budget caps both each text and the batch total. Youdao documents a 5,000-character limit per query. LibreTranslate character, batch and request quotas depend on the deployment or API key; 5,000 is this client’s budget. Batch sizes of 50/10 and Youdao’s 1,000 ms interval are client presets, not account quotas. LibreTranslate charges request quota per text in a batch, so pacing multiplies the interval by the text count. Adjust the batch count to the actual quota of hosted services as well.

Manual, series and automatic translations share each engine’s request concurrency and pacing. Only Youdao `411` / `412` or HTTP `429` retries the current batch, with increasing waits and respect for `Retry-After`. Waits over 60 seconds return a rate-limit error; new tasks cannot send early during the cooldown. Authentication failures, timeouts and other errors are not replayed automatically. All selected fields must succeed before a chapter is saved; failures preserve existing translations. Resuming the same task reuses saved successful responses. Logs include batch, request, retry and translated-character counts, without source text, translations or credentials.
- AI credentials are managed by `ai`, Youdao and LibreTranslate credentials by `translation`, and speech credentials by `tts`.

The translation dialog shows the current engine. Youdao uses its official language table for the target-language dropdown; LibreTranslate reads supported targets from the configured service, with a reload option if loading fails. AI retains free-text input and term glossaries. Failure messages provide guidance for the reported cause. English backend logs record the execution ID, document ID, engine, stage and safe error codes without novel text or secrets.

A novel can keep translations into the same language from different engines. Overwrite and skip apply only to the current engine. The reader and series pages group separate Reading language and Translation engine controls, offering only combinations with saved translations. The engine shows Not applicable for the original text. Merged downloads and novel exports also offer separate language and engine choices; narration reads the selected translation. Existing translations without an engine record remain labeled "Unknown source" and keep their original language-only filename suffix. All AI models share the `ai` engine identity, so switching AI models does not create another translation version.

The backend saves translation progress. Closing or hiding the page lets the task continue. Open Translate again to view the existing task, pause, resume or cancel it. Cancellation waits for the active request to exit and retains successful checkpoints and completed chapters. Discarding a task requires confirmation and removes only its task record and checkpoints, leaving published translations intact.

After a backend restart, unfinished tasks stay paused until you resume them. Resuming checks the source, existing translation, engine address, model and segmentation settings. If they changed, restore the settings or discard the task and translate again. AI terminology context is retained with checkpoints. Truncated model output is never published as a complete translation. Discard the task and start again with smaller segments, or adjust the output limit in the model service, if supported, before resuming.

If an AI response omits required fields, reports a different target language or contains an invalid language code, the task pauses. Resuming requests the invalid part again and reuses the other saved successful responses.

Up to 256 task records and 256 Mi UTF-16 code units of checkpoints are retained. Creating a task removes completed records older than 7 days or, if capacity is full, the oldest completed record. Paused and cancelled tasks require manual discard. If the external service processed a request but its response was not saved locally, resuming may send that request again and incur another charge.

## AI translation and term mapping tables (administrator)

After configuring and enabling a text model, click "Translate" on the novel reader or series contents page:

- **Target language**: prefilled from the current interface language by default; you can change it to any natural-language name.
- **Segment length**: `0` inherits `translation.ai-segment-size` from Settings (default `0`, the whole chapter). A positive value groups paragraphs to approximately that length. It counts UTF-16 code units and is a paragraph-grouping target, not a strict request limit; a single long paragraph may exceed it. Model context and output token limits remain in AI model settings; there is no fixed character-to-token conversion. For machine translation, `0` inherits the service character budget and a positive value can only reduce individual segment size.
- **Existing translations**: choose "Overwrite (re-translate)" or "Skip".
- **Term mapping table**: stores "original term ↔ translations in each language" to keep proper names consistent. Works in a series share a same-named table by default; individual works use their own tables. You can also choose no table, switch to an existing table, or create / edit one. New proper names found by AI during translation are added automatically to the selected table for later chapters (the model may still deviate from the mappings, so check the translation).
- **Switch content language**: the Reading language and Translation engine controls on reader and series pages switch between the original and existing translations independently of the interface language. Body text, chapter titles, series names and translated EPUB titles switch together; untranslated items fall back to the original. Listening also uses the selected language. Chapter titles and body text are translated in the same AI request. Series-name translation includes pairs of original and translated chapter titles in the same language, along with the term mapping table.
- **Translate an entire series**: translate chapters in order from the series page. You can hide the task in the background or cancel it (completed chapter translations are retained), and `{series title}_{language code}@{engine}.epub` is generated when it finishes. With "Skip translated" selected, one lightweight request first resolves the target language name to a normalized language code; chapters with an existing translation are then skipped directly. This translation updates only the compilation for the selected language and engine.
- **Automatic translation after download**: under "Novel settings" on the download page or in a scheduled task, select "Auto-translate newly downloaded novels" and specify a target language and segment length. Automatic translation always uses the work's / series' default term mapping table and runs in series chapter order in the background without blocking downloads. A successful download is marked complete; translation failure only adds "Completed (translation failed)" and does not affect the saved original text.

## Delete, manage in batches and check results

Administrators can delete an individual novel from the reader page, or use "Manage" in the novel gallery to select all filtered results, export a ZIP in batches (optionally deleting source files after export; progress appears in the sidebar's "Tasks"), add works to collections or delete them in batches. Deletion of application-managed files cannot be undone from the interface, but [deletion markers](/en/gallery#delete-works) remain to prevent downloading them again by default. Read-only source files registered through [Import external downloads](/en/pixiv-batch-downloader-import) are retained. The complete batch management rules are the same as those for the [Work gallery](/en/gallery).

After downloading, check both the local TXT / HTML / EPUB files and the novel gallery records. If files exist on disk but not in the gallery, first check that the current database and download directory belong to the same instance. Do not delete the database to try to rebuild it.
