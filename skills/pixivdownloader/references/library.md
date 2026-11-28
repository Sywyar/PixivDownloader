# Find downloaded works

The library holds the works this instance has downloaded or imported. Illustrations and novels have separate operations, provided by the `gallery` and `novel` plugins.

## Illustrations

- `GET /api/gallery/artworks` needs `gallery:read` and lists works page by page. `page` starts at 0 and `size` defaults to 24. `search` filters by text; `sort` and `order` change the order. Filters such as `format`, `collectionIds` and `tagIds` take comma-separated values, for example `?format=jpg,png&collectionIds=12,34`. The catalog lists every filter and its allowed values.
- `GET /api/gallery/artwork/{artworkId}` needs `gallery:read` and returns the gallery metadata of one work. `404` means the work is not in the gallery or not visible.
- `GET /api/gallery/tags` needs `gallery:read` and lists tags for the `tagIds` filter.
- `GET /api/library/artworks/{artworkId}` needs `core:library.read` and returns the download record, with optional file verification. An empty `400` means there is no record, so check the status before parsing the body.

## Novels

- `GET /api/gallery/novels` needs `novel:library.read` and lists saved novels. Paging and filters follow the same pattern as illustrations.
- `GET /api/gallery/novel/{novelId}` needs `novel:library.read` and returns one saved novel.
- `GET /api/gallery/novel/{novelId}/content` needs `novel:content.read` and returns the saved text.
- `GET /api/gallery/novels/series`, `GET /api/gallery/novels/authors` and `GET /api/gallery/novels/tags` need `novel:library.read` and list series, authors and tags.

## Field conventions

- Gallery `artworkId` is a JSON integer such as `42`. Download task `workId` is a string such as `"42"`. Join records by work ID, not by file path.
- Gallery `time` is in Unix milliseconds. Download task `updatedAt` is an ISO 8601 timestamp.
- Gallery `folder` is the registered directory. A task's `outputPath` is the planned output directory and can be null.

Deleting from the library removes local files and records permanently. Do it only when the user asks for it explicitly, after reading the delete operation in the catalog.
