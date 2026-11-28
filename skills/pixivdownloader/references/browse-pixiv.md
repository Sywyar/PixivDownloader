# Browse Pixiv

The `pixiv-content` plugin reads Pixiv through the instance without creating download tasks. Every operation is a `GET` under `/api/pixiv-content/`. Parameters and response fields are in the catalog:

```bash
jq '.paths | with_entries(select(.key | startswith("/api/pixiv-content/")))' pixiv-openapi.json
```

## Operations and permissions

Artworks, creators and discovery need `pixiv-content:metadata.read`:

- `GET /api/pixiv-content/search` searches artworks by keyword.
- `GET /api/pixiv-content/rankings` reads one page of a ranking.
- `GET /api/pixiv-content/discover` and `GET /api/pixiv-content/latest` list recommended and new works.
- `GET /api/pixiv-content/tags/suggest`, `GET /api/pixiv-content/tags/search` and `GET /api/pixiv-content/search/users-and-tags` look up tags and creators.
- `GET /api/pixiv-content/artworks/{id}` returns one work, including its pages and image paths. `GET /api/pixiv-content/artworks/{id}/related`, `GET /api/pixiv-content/artworks/{id}/comments` and `GET /api/pixiv-content/artworks/{id}/comments/{commentId}/replies` read related works and comments.
- `GET /api/pixiv-content/users/{id}` reads a creator. `GET /api/pixiv-content/users/{id}/artworks`, `GET /api/pixiv-content/users/{id}/series`, `GET /api/pixiv-content/users/{id}/bookmarks` and `GET /api/pixiv-content/users/{id}/collections` list their works, series, public bookmarks and collections. `GET /api/pixiv-content/users/search` finds creators.
- `GET /api/pixiv-content/series/{id}` reads a manga series. `GET /api/pixiv-content/collections/search` and `GET /api/pixiv-content/collections/{collectionId}/works` search and read collections.

Novels need `pixiv-content:novel.read`:

- `GET /api/pixiv-content/novels/search`, `GET /api/pixiv-content/novels/rankings` and `GET /api/pixiv-content/novel-series/search` search and rank novels.
- `GET /api/pixiv-content/novels/{id}` returns the text with Pixiv page, chapter and image markers. `GET /api/pixiv-content/novels/{id}/related`, `GET /api/pixiv-content/novels/{id}/comments` and `GET /api/pixiv-content/novels/{id}/comments/{commentId}/replies` read related novels and comments.
- `GET /api/pixiv-content/users/{id}/novels` lists a creator's novels. `GET /api/pixiv-content/novel-series/{id}` lists a series in reading order.

Images need `pixiv-content:image.read`:

- `GET /api/pixiv-content/artworks/{id}/pages/{pageIndex}/image` streams one page. `pageIndex` starts at 0. Use `size=preview` (the default) or `size=original`.
- `GET /api/pixiv-content/users/{id}/avatar`, `GET /api/pixiv-content/novels/{id}/cover` and `GET /api/pixiv-content/novels/{id}/images/{imageId}` stream avatars, covers and novel illustrations.

Ugoira animations need `pixiv-content:animation.read`. `GET /api/pixiv-content/artworks/{id}/animation` streams a looping `image/apng`. Only works whose details include `animationPath` have one.

The account's own feed needs `pixiv-content:personal.read` and a Pixiv account (see the next section):

- `GET /api/pixiv-content/me` checks which account is in use.
- `GET /api/pixiv-content/me/bookmarks` and `GET /api/pixiv-content/me/bookmark-tags` read the account's bookmarks. `rest=show` reads public bookmarks and `rest=hide` reads private ones.
- `GET /api/pixiv-content/me/follow-latest`, `GET /api/pixiv-content/me/following`, `GET /api/pixiv-content/me/recommendations` and `GET /api/pixiv-content/me/watchlist` read the feed, followed creators, recommendations and watch list.
- `GET /api/pixiv-content/me/collections` and `GET /api/pixiv-content/me/collections/{collectionId}/works` read the account's collections.

## Paging and other conventions

- `page` starts at 1 for searches, rankings and lists.
- Rankings take a `mode` such as `daily` (the default), `weekly`, `monthly`, `rookie`, `original`, `daily_ai`, `male` or `female`. Omit `date` for the latest edition. Restricted modes need an eligible account.
- `xRestrict` values 0, 1 and 2 mean all ages, R-18 and R-18G.
- Image and animation responses are binary. Save them to a file instead of printing them.

## Choose the Pixiv account

Operations under `/api/pixiv-content/me` need to know which Pixiv account to use. Other operations can return more with an account, for example restricted rankings. Send one of these headers:

- `X-Acquisition-Credentials: <id>[,<id>...]` uses credentials saved on the instance, in order of preference. List them with `GET /api/acquisition/credentials`, which needs `core:credentials.read` and returns names and status without secrets. Using a saved credential also needs `core:credentials.use`.
- `X-Acquisition-Credential: <value>` sends a one-off credential. Use it only when the user supplies one. Never ask the user to paste a Pixiv cookie.

Without an account, these operations return `400 ACQUISITION_CREDENTIAL_REQUIRED`.

## Errors from Pixiv

| Response | Meaning |
| --- | --- |
| `404 PIXIV_CONTENT_NOT_FOUND` | The work, creator or series does not exist or is not visible. |
| `403 PIXIV_ACCESS_DENIED` | The Pixiv credential has expired or does not grant access to the resource. |
| `403 PIXIV_PREMIUM_REQUIRED` | Filtering by bookmark count needs a Pixiv Premium account. |
| `422 PIXIV_TAG_SEARCH_TOO_BROAD` | A tag filter on a creator's works matches too many works. Use a narrower tag or one tag at a time. |
| `410 PIXIV_RECOMMENDATION_EXPIRED` | The recommendation cursor expired. Start again from the first batch. |
| `429 PIXIV_RATE_LIMITED` | Stop and report. Do not retry in a loop. |
| `503 PIXIV_ANIMATION_BUSY` | Try the animation again later. |
| `502 PIXIV_UNAVAILABLE`, `PIXIV_RESPONSE_INVALID`, `PIXIV_RESPONSE_TOO_LARGE` | Pixiv could not be reached or returned an unusable response. |
