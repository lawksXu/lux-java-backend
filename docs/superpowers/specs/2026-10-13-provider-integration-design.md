# Lux OpenAI and BytePlus Provider Integration Design

Status: implemented locally according to the user's final boundary decision, including authorized multimodal observation, audited original-evidence attachment and Java reference-ratio admission. Component and three-process acceptance evidence is recorded separately in the linked readiness report. Real provider account acceptance waits for API keys.

## Ownership

| Component | Responsibility |
| --- | --- |
| Java | Business admission, capability catalog and exact routes, generation plans/Tasks, private Asset ownership/import/verification, projections and SSE |
| Agent | Direct `ChatOpenAI` through `ModelExecutor`, Session context/checkpoints, model/tool execution receipts, original-response reconciliation, Run/Turn decisions |
| Connector | BytePlus request translation, one original image/video submission, asynchronous video polling, retained output bytes and generation observation delivery |
| Utils | No new business responsibility in this integration |

OpenAI credentials and response identity belong in Agent. BytePlus credentials and external generation identities belong in Connector. Using the SDK changes the provider adapter; it does not transfer receipt persistence or recovery out of Agent. No Connector chat HTTP API, model broker topic or chat receipt table is introduced.

## Exact selected bindings

| Lux model | Provider model | Adapter | Default output |
| --- | --- | --- | --- |
| `seedream-4.5` | `seedream-4-5-251128` | `byteplus-seedream-v1` | JPEG, square 2048 |
| `seedream-5.0-lite` | `seedream-5-0-260128` | `byteplus-seedream-v1` | PNG, square 2048 |
| `seedream-5.0-pro` | `dola-seedream-5-0-pro-260628` | `byteplus-seedream-v1` | PNG, square 2048 |
| `seedance-2.5` | `dreamina-seedance-2-5-260628` | `byteplus-seedance-v1` | MP4, 720p 16:9, 5 seconds, no audio |

Each generation binding uses provider `byteplus`, profile `standard`, capability revision 1, request schema 1 and default account reference `byteplus-primary`. Lite and Pro remain separate selections. Account-specific endpoints require a deliberate coordinated registration revision; there is no runtime alias or model fallback. Public identifiers: [BytePlus image models](https://docs.byteplus.com/en/docs/modelark/seedream-4-0-5-0), [model releases](https://docs.byteplus.com/en/docs/modelark/model-release-announcement).

Java lists the four models disabled by default. `LUX_BYTEPLUS_GENERATION_ENABLED=true` enables both catalog admission and exact execution/verifier routes. Connector separately requires its provider-execution enable flag, persistence/broker/result-store configuration, BytePlus credentials and `ffprobe`. A catalog switch does not prove that the remote deployment or account is ready.

## Agent ChatOpenAI execution and recovery

Agent uses `langchain-openai==1.6.0` and `ChatOpenAI(model="gpt-6-luna", use_responses_api=True, max_retries=0)`. The complete original messages and current tool schemas are submitted; provider truncation and automatic previous-response context replacement are disabled. The current implementation uses stored foreground Responses streaming. It does not promise background execution will continue after a disconnect.

The original Agent execution receipt is committed before model execution. An additive `model_provider_receipt` table in the owned v2 SQLite database retains execution/user/Session/request hash and model/account/endpoint/organization/project bindings. Existing stores are extended without reset. Provider ownership is saved with `dispatch_started=false` before invocation-side media hydration, then switches atomically to true immediately before the SDK create. False-phase interruption recovers as confirmed `MODEL_NOT_SUBMITTED`; existing receipts default to true to preserve prior uncertainty. The first public `response.created` ID exposed by ChatOpenAI is committed before forwarding dependent live fragments. Complete text, reasoning summary and typed function-call observations preserve original call IDs and paired historical tool results. Original LangChain response messages remain in Agent context.

Recovery rules:

- With an original response ID, retrieve that original response through OpenAI on the saved binding. No second create request is issued.
- A completed response is decoded and committed through the existing model receipt/checkpoint flow. Failed, cancelled or incomplete original responses become confirmed failures.
- A missing response ID, unavailable/expired lookup or still-running response remains UNKNOWN. Local correlation labels do not create a provider lookup or idempotency guarantee.
- Definitive create HTTP 400/401/403 rejection before any accepted response ID becomes confirmed `MODEL_PROVIDER_REJECTED`. Transport failures and uncertain server outcomes remain UNKNOWN. No automatic paid retry is enabled.
- Account references are operator-maintained labels. Saved organization/project scope is checked, but a label cannot prove that a rotated key belongs to the same underlying account.

ASGI lifespan supplies the same adapter as executor and reconciler. `LUX_OPENAI_ENABLED` defaults false, and a missing `OPENAI_API_KEY` leaves execution disabled. `LUX_OPENAI_ACCOUNT_REF` defaults `openai-primary`; optional `OPENAI_ORG_ID` and `OPENAI_PROJECT_ID` participate in receipt binding. Credentials never enter model receipts, ToolResults, Kafka envelopes or checkpoints. [OpenAI model](https://developers.openai.com/api/docs/models/gpt-6-luna), [original response polling](https://developers.openai.com/api/docs/guides/background), [request correlation](https://developers.openai.com/api/reference/overview).

## Read-only Task recovery

The earlier P1 resulted from treating an unobserved read like an uncertain mutation. Timeout/503 marked `get_task` UNKNOWN, but the read had no original operation receipt to reconcile; the UNKNOWN barrier also prevented stop/input progression. Native receipts/checkpoints restored the same block after restart.

`get_task` timeout/temporary HTTP failure now produces a confirmed ToolError saying no snapshot was observed. Persisted IN_FLIGHT/UNKNOWN reads settle as interrupted unobserved reads without making a new query under the old call. Original call pairing is retained, stop completes and later input progresses. A new model call may independently request a fresh snapshot. Mutation and model uncertainty continue to require original-operation reconciliation.

The [offline recovery regression](../../../scripts/integration/reproduce_get_task_timeout.py) asserts one HTTP read, the original confirmed error, cancellation, later input and Store/checkpoint restart.

## BytePlus normalized generation

Seedream generation is submitted once through the existing synchronous provider API; Connector archives and preserves its original output before generation success. 4.5 supports JPEG; Lite/Pro support PNG/JPEG. 4.5/Lite send `sequential_image_generation="disabled"`; Pro sends `layer_decomposition=false` and omits sequential settings. One normalized Task expects one primary output. [Image API](https://docs.byteplus.com/en/docs/modelark/image-generation-api).

Seedance returns a typed asynchronous acceptance ID. Worker commits it into the existing `provider_external_id` before polling, including across a persistence failure and worker restart. Reconciliation receives that saved ID and queries only the original Task. Terminal normalized receipts retain typed original provider metadata; image Base64 is archived once with the primary outputs. Audited operator attachment adds an owned schema-v3 audit table; existing owned v2 schemas upgrade additively without resetting execution, receipt or retained-result records.

The initial video route uses fixed-duration text/reference generation with `omni_reference_task_type="reference"`, and auxiliary draft/final-frame outputs disabled. Editing, extension and adaptive sizing are outside this profile. [Video task creation](https://docs.byteplus.com/en/docs/modelark/create-video-generation-task-api), [original task query](https://docs.byteplus.com/en/docs/modelark/get-video-generation-task-api).

Initial supported product subset:

| Model | Sizes | Reference count / per-image bytes |
| --- | --- | --- |
| 4.5 / Lite | Square 2048, 4096 | 14 / 3 MiB |
| Pro | Square 1024, 1536, 2048 | 10 / 4 MiB |
| Seedance | 1280x720, 720x1280, 960x960 at 720p | 30 / 1.5 MiB |

All references are PNG/JPEG images. Conservative per-model byte limits ensure maximum reference count still fits inline Base64 transport plus JSON overhead. The adapter also checks the complete request body budget of 64 MiB. Prompts are limited to 4000 UTF-8 bytes. Video duration is an integer 4..30 seconds, MP4/MOV and explicit audio true/false, with 500 ms product tolerance.

Java keeps logical ratio and documented native pixel dimensions separate, allowing rounded provider presets without weakening exact output-pixel verification. The first registered presets have exact ratios. Seedance reference dimensions must each be 300..6000 and ratio 0.4..2.5. The optional typed `ReferenceMediaLimit.aspect_ratio_range` expresses inclusive rational bounds 2:5..5:2. Java checks actual Asset metadata using integer cross-multiplication before freeze/Task creation; Connector independently checks decoded reference bytes before dispatch. Old catalogs omit the field and remain readable; new readers must be deployed together when the constraint is advertised.

## Asset transport and actual bytes

Connector's Java AssetLocator uses owner-scoped metadata/content APIs. It checks owner/Asset ID, MIME, bounded content length, SHA-256 and decoded image dimensions, then creates a provider data URI. Internal Java URLs and bucket credentials are not forwarded to the provider. `LUX_BACKEND_URL` is required for reference-bearing normalized requests.

Before normalized success, Connector reopens retained result bytes, verifies immutable length/checksum, validates encoding/container and decodes through `ffprobe -count_frames`. Decoder errors, missing samples and absent decoded visual/audio streams are rejected. Dimensions, duration and audio presence must match the frozen request. Java independently imports and verifies those original bytes before business Task success.

Agent resolves uploaded input Assets and fresh Task-result observations through those same Java owner-scoped endpoints. It verifies source Item/Task version, owner, metadata, immutable bytes and decoded media, then freezes compact typed descriptors without changing historical ToolResults. On invocation it presents transient verified JPEG `input_image` blocks, with labels and timestamps for sampled video frames. Accepted-response recovery retrieves the original response without media refetch. Successful descriptors survive native checkpoint reopening and subsequent tool turns.

The presentation window holds at most eight images/frames, reserving room for fresh Assets. PNG/JPEG sources are bounded to 10 MiB; MP4/MOV sources to 100 MiB, 120 seconds and 40 million decoded source pixels. At most eight frames are sampled uniformly per video; each presentation is at most 1024 pixels on its longest edge and 1 MiB JPEG. Decoder subprocesses have 20-second deadlines, one thread and fd/pipe-only protocols. Audio observation is explicitly unsupported. GPT 6 Luna receives images, including sampled frames, rather than native video/audio. [Model modalities](https://developers.openai.com/api/docs/models/gpt-6-luna).

Real-human portrait references require a separate provider-managed authorization workflow; video/audio input references and that portrait workflow are outside the initial generic image-reference route.

## Remaining uncertainty and acceptance

Agent and Connector expose default-disabled restricted original-evidence routes. A configured Bearer token, trusted server-side operator identity and exact allowed account references protect list/detail/attachment access. Agent attaches an original OpenAI response ID; Connector attaches an original Seedance task ID or typed synchronous Seedream response. Immutable owner/Session/execution or Task/operation/account/model/request bindings must match. Original evidence and audit commit atomically; exact replay is idempotent, and terminal/conflicting or undispatched evidence cannot be overwritten. Attachment resumes normal original GET/preservation only and never directly marks a Java Task successful.

The OpenAI create carries the durable execution UUID in the SDK-supported `X-Client-Request-Id` header for operator/vendor correlation. It is not idempotency or a query-by-trace API. Evidence attestation remains an operator responsibility; local hashes cannot discover missing IDs or prove provider identity. Without original evidence, acceptance stays unresolved. Synchronous Seedream still has no automated original-result lookup. Absence, age or lease expiry never authorizes resubmission.

The [cross-project acceptance report and matrix](../plans/2026-10-13-cross-project-integration.md) distinguish SDK/component fixtures from real local MySQL/Kafka/S3 and three-process evidence. Neither establishes live account activation, provider output quality or billing. Keys will be supplied separately; live execution remains disabled until explicitly configured for the agreed acceptance scope.
