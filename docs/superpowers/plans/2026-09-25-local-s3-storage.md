# Local S3 Storage Implementation Plan

> Use test-driven-development and dispatching-parallel-agents for the independent HTTP and service adaptations after fixing the shared storage contract. Use requesting-code-review before completion. User approval already covers implementation; do not ask for execution permission again.

**Goal:** Deploy local SeaweedFS configuration and route asset bytes through AWS SDK v2 while retaining resource isolation, generated-result integrity, Range downloads and legacy read access.

**Architecture:** Feature-local AssetStore boundary, S3 implementation plus local compatibility implementation, unchanged database metadata and public routes. Durable business changes remain separate from object transfers.

**Tech Stack:** Java 17, Spring Boot 4.1.1, AWS SDK for Java 2.x, SeaweedFS 4.47, existing MySQL/Flyway/Kafka.

## Constraints

- No instruction-file changes, Git branches/commits, real model calls, or restarts of Docker/MySQL/Kafka.
- Preserve old files and V1/V2 schemas. No public JSON changes.
- Use real storage for integration checks; clearly separate authored tests from executed tests if Docker remains unavailable.
- Keep secrets in environment configuration; bind local services to loopback.

## Shared contract

Create `asset/AssetStore.java` with these signatures:

```java
StoredFile write(UUID user, UUID asset, InputStream input, long maxBytes) throws IOException;
StoredFile writeVerified(UUID user, UUID asset, InputStream input, long maxBytes,
                         long expectedSize, String expectedSha256) throws IOException;
InputStream open(UUID user, UUID asset, long offset, long length) throws IOException;
boolean exists(UUID user, UUID asset) throws IOException;
boolean matches(UUID user, UUID asset, long size, String sha256) throws IOException;
void delete(UUID user, UUID asset) throws IOException;
int cleanupTemporaryFiles(Duration age) throws IOException;
void cleanupOrphans(Duration age, BiPredicate<UUID, UUID> retain) throws IOException;
record StoredFile(long sizeBytes, String sha256) {}
```

The interface also owns `AssetTooLargeException extends IOException`. Streams returned by open belong to the caller. Offsets/lengths are nonnegative and reads are bounded to the requested length; absent content raises FileNotFoundException. Cleanup applies the retain predicate before deleting eligible object identities.

## Task 1: Store and configuration

- [x] Add storage contract tests before production implementation; verify expected missing capability failures.
- [x] Add AWS SDK BOM/S3/URL connection client dependencies and AssetStore. Adapt LocalAssetStore without removing existing integrity/private-file tests.
- [x] Implement S3AssetStore staging, SDK operations, missing-object-only legacy fallback and cleanup. Add S3StorageConfiguration with explicit credentials and finite timeout values.
- [x] Add SeaweedFS to Compose, required env examples, application settings and wiring test isolation.

Files: `pom.xml`, `compose.yml`, `.env.example`, `application.yml`, `AssetStore.java`, `LocalAssetStore.java`, `S3AssetStore.java`, `S3StorageConfiguration.java`, `S3AssetStoreTests.java`, `ApplicationWiringTests.java`.

## Task 2: Business store consumers

- [x] Replace LocalAssetStore-specific types/path deletion in AssetService and TaskResultService with AssetStore, preserving database retry boundaries.
- [x] Move filesystem iteration out of AssetMaintenance; retain business decisions in its predicate: `assets.exists(owner,id) || tasks.hasImporting(owner)`.
- [x] Keep tests for failed import/replacement, duplicate upload cleanup, unchanged old asset metadata, and database retries not reopening streams.

Files: `AssetService.java`, `AssetMaintenance.java`, `TaskResultService.java`, their existing tests. Independent from HTTP after the contract above is fixed.

## Task 3: HTTP content access

- [x] Test ranged downloads through a store with no Path, denied ownership without store calls, missing objects, suffix/multiple/invalid ranges and HEAD.
- [x] Replace FileSystemResource use with bounded store reads and standard HTTP range responses, retaining existing routes and private headers.

Files: `AssetController.java`, `AssetControllerTests.java`; a focused content response helper may be introduced if needed to keep HTTP parsing separate.

## Task 4: Verification and documentation

- [x] Add SeaweedFS-only integration checks with real AWS SDK, including multipart and range behavior, private buckets, restart persistence where practical.
- [x] Update README/API/architecture/storage docs with configuration, legacy fallback, ownership, cleanup and verification commands.
- [x] Run focused tests, then `./mvnw -B -ntp '-Dtest=*Tests,!*IntegrationTests,!LuxBackendApplicationTests' verify`.
- [x] Attempt storage-only integration if Docker is usable; do not run the previously deferred MySQL/Kafka suite or restart Docker.
- [x] Independently review, resolve findings, and report actual results and remaining limits.

## Verification outcome — 2026-09-25

- Focused tests: 59 passed, including S3 fallback, integrity, HTTP ranges, disconnect cleanup, service transaction boundaries and Spring wiring.
- Container-independent verification: `./mvnw -B -ntp '-Dtest=*Tests,!*IntegrationTests,!LuxBackendApplicationTests' verify` passed all 98 tests and packaged the executable JAR.
- Independent review identified SDK translation of missing-bucket HEAD responses to NoSuchKey. A regression test first failed, then passed after requiring HeadBucket success before legacy fallback. Follow-up review found no outstanding issue.
- Storage deployment was attempted with `docker compose up -d seaweedfs`. The pinned image downloaded and the named volume/container were created, but Docker remained in `created` state with PID 0. Direct port 8333 connections were refused. The waiting Compose client was interrupted; the container and volume were retained.
- `S3IntegrationTests` was attempted against that endpoint. Setup failed after the bounded 45-second readiness window with Connection refused; its two real-storage scenarios have not executed successfully. No test bucket or test objects were created.
- MySQL/Kafka acceptance remains deferred as previously instructed. No existing services or Docker Desktop were restarted. Full workflow tests now configure isolated SeaweedFS as well when that suite resumes.
- Restart persistence and the real S3 compatibility assertions remain unverified until Docker can start the service. Resume with the storage-only command in README; no provider/model calls are needed.
