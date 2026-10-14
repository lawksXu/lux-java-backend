# Local S3 Object Storage

The user approved SeaweedFS deployment and Java integration on 2026-09-25. New asset bytes use the AWS SDK for Java 2.x against a local SeaweedFS S3 endpoint. MySQL continues to own asset metadata, user isolation, and task lifecycle.

## Components and compatibility

- Run pinned SeaweedFS 4.47 in single-node `mini` mode through Compose. Publish only the S3 endpoint on loopback port 8333, persist `/data`, require local credentials, and initialize private bucket `lux-assets`.
- Use the AWS SDK v2 S3 client with endpoint override, explicit region and credentials, path-style addressing, bounded timeouts and standard SDK retries. Spring Boot does not manage the AWS SDK version, so import its BOM explicitly.
- `AssetStore` provides stream writes, verified writes, ranged reads, integrity checks, deletion and maintenance. `S3AssetStore` implements it. Services no longer use filesystem paths for asset operations.
- Retain `LocalAssetStore` as a local test/compatibility implementation. In S3 mode, only a missing S3 object permits a read fallback to the existing local file. New writes and deletion target S3; legacy files are retained without automatic migration or deletion. Storage failure or denied access must not silently fall back.
- Use deterministic keys `{user_id}/{asset_id}` in the configured bucket. Existing AssetRecord and HTTP JSON remain unchanged; no database migration is required. Changing the bucket requires moving its objects separately.

## Write and read behavior

Upload and generated-result import share the same store contract. Stage incoming streams in private temporary files to bound memory, enforce size limits, calculate SHA-256, and validate persisted expected content before replacing an object. Upload the completed staging file with a replayable SDK request body, then remove staging files. S3 calls and checksums stay outside database transactions. An uncertain successful object write retains its stable key and is reconciled or cleaned up without resubmitting generation.

Downloads still resolve asset metadata under the selected user before opening content. Java proxies full and byte-range responses through S3 GetObject Range. Keep private response headers, suffix/open-ended ranges, multipart ranges and 416 behavior. Close every remote response stream, including on client disconnect. Credentials and backend URLs are not returned in asset JSON.

Task success still requires verified object bytes and committed metadata. Import failures retry transfer only. The default maximum asset size remains 100 MiB; a replayable staged PutObject is sufficient within that limit. Multipart-upload compatibility is verified separately with the actual SDK.

## Recovery and verification

Maintenance removes abandoned local staging files older than one day and S3 orphan objects older than two days. It preserves objects with asset rows and objects owned by users with unfinished imports. Paginate S3 listings and ignore keys outside the exact UUID/UUID namespace. In S3 mode do not delete legacy local assets.

Tests cover owner checks before I/O, SDK Range requests, missing objects versus backend failure, bounded uploads, verified replacement, replayable uploads, integrity checks, and conservative cleanup. A SeaweedFS integration test verifies real AWS SDK PUT/HEAD/GET/Range/LIST/DELETE and multipart operations. Existing MySQL/Kafka container acceptance remains deferred unless the user resumes it; a new storage-only container can be attempted without restarting Docker or touching existing services.

## References

- [SeaweedFS](https://github.com/seaweedfs/seaweedfs)
- [SeaweedFS S3 support](https://github.com/seaweedfs/seaweedfs/wiki/Amazon-S3-API)
- [AWS SDK endpoint configuration](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/endpoint-config.html)
