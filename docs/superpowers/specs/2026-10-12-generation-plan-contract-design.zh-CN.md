# Generation Plan 契约设计

Java 负责持久化生成意向、管理可编辑的 plan、定义模型能力策略并执行校验。plan 在首次提交被受理时冻结，此后仍可复用：每次提交都使用同一份冻结配置，按要求的文件数创建独立的单文件 Task。Python 负责对话执行，通过明确类型的工具调用 Java；model connector 将规范化请求转换为供应商请求并执行。

v1 明确类型契约、八个 Java plan 工具接口、plan/焦点持久化、原操作回执及单文件 Task 批次原子受理均已实现。生产模型目录和执行/结果核验注册仍为空；测试中的合成注册不代表真实供应商支持。本规范描述已批准的契约，文档本身不会部署迁移或启用供应商。

本文件对应[英文设计文档](2026-10-12-generation-plan-contract-design.md)，字段名、类型名、枚举值、工具名称及协议语义保持一致。

## 契约文件

- [JSON Schema](../../fixtures/generation-plan-v1/contract.schema.json) 使用 JSON Schema 2020-12 定义全部具名结构、类型判别字段、基础约束、工具调用及返回结果。
- [结构示例](../../fixtures/generation-plan-v1/examples.json) 包含全部八个工具的调用、草稿与冻结 plan、完整的 image/video 请求以及空模型目录。UUID、`unregistered-schema-example` 模型 ID 及供应商绑定值都是未注册的结构占位值，不代表模型或适配器注册。这些示例说明数据形状，不证明请求能够通过业务受理。
- [执行契约](../../api-contract-v2.md) 记录当前 plan 接口、上下文读取、回执及 legacy/normalized 请求边界。

Java 应使用明确的 record/class 和 sealed 变体表示记录及带类型标签的联合结构。Python 应使用对应的明确 Pydantic 模型。已知业务结构不能退化为 `JsonNode`、`Map<String, Object>`、`Any` 或以字符串为键的参数字典。JSON Schema 中的属性映射属于 schema 表达方式，不是业务参数映射。

## 归属与不变量

1. 一个 Session 可以拥有零个或多个 plan，当前聚焦的 plan 可以为空，也可以有一个。聚焦属于业务交互状态，不承担 Run 调度槽位的职责。
2. Plan 和 Task 都直接保留所有者及 Session 身份，不依赖 Run、Turn 或 Item 的生命周期。原始工具调用 Item ID 可以作为操作身份使用，无需建立外键。
3. `DRAFT` 允许明确的编辑操作。`FROZEN` 禁止修改整个 plan 的配置、意向、参数来源及冻结请求。
4. Java 首次成功受理提交时，在保存全部要求的 Task、各自的 Outbox 信封、父操作回执及前端通知的同一事务中冻结 plan 一次。受理必须全部成功或全部失败；供应商受理或生成完成不属于冻结边界。
5. 一个冻结 plan 可以创建多个 Task。plan 上不保存 `submitted_task_id`；Task 保存 `plan_id` 及自身不可变的请求载荷。
6. 重放同一次操作返回其原 Task 列表／结果。使用冻结 plan 再生成一次时，使用新的父操作 ID，并按冻结的要求文件数创建新的 Task 列表；这不属于重试之前的 Task。
7. 同媒体类型下更换模型／profile 时修改草稿。跨媒体类型切换时创建新草稿，保持源 plan 不变。修改冻结 plan 必须先明确执行克隆。
8. 更换模型可以清空不兼容的执行参数，但不能悄悄修改用户要求。未解决的要求会阻止提交。
9. Java 只对缺失且不受用户要求约束的字段应用模型目录中明确的默认值。默认值及提供给模型的快照属于持久化事实，不能从对话摘要中重新推断。
10. Java 校验结构和领域事实，无法证明 LLM 忠实提取了自然语言意图。`EXPLICIT` 表示由工具明确提供，不代表已经证明用户批准。

## 传输约定

schema 声明的全部对象字段在传输时都必须出现，包括允许取 null 的字段。拒绝未知属性。这样可以明确工具 schema 及字段缺省语义。列表有序；重复身份及其他跨字段不变量由 Java 校验。

标识符使用规范的小写 UUID 字符串。revision 使用有符号 64 位整数：plan 和能力 revision 从一开始，聚焦 revision 从零开始。revision 不允许溢出。UTC 时间戳以 `Z` 结尾，小数秒最多六位。Java 根据用途使用明确的 `String`／UUID、`long` 和 `Instant` 类型，禁止使用 `var`。

Java 还需要校验文本不能全为空白。在平台入口，描述和 prompt 最多包含 64,000 个 Unicode 码点及 262,144 个 UTF-8 字节；profile 可以规定更小的 prompt 字节上限。模型标识符最多 255 个字符；profile、预设及分辨率标识符最多 64 个字符。这些是 Lux 的限制，不代表供应商能力。

输入数组的顺序、明确提供的 false、空数组及 null 都保留各自语义。Java 会规范化存储的比例值，但操作身份仍包含完整的原始具名参数。即使两个输入规范化后相同，修改同一次操作的参数仍构成冲突。

## Plan 与聚焦结构

### GenerationPlan

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `type` | 常量 `generation_plan` | 传输类型判别字段 |
| `schema_version` | 常量 `1` | Plan 契约版本 |
| `id`, `user_id`, `session_id` | UUID | 稳定身份及直接归属 |
| `revision` | 正 int64 | 业务 CAS 版本，独立于 ORM／来源／SSE 版本 |
| `state` | `DRAFT` 或 `FROZEN` | 配置是否允许编辑 |
| `configuration` | `ImagePlanConfiguration` 或 `VideoPlanConfiguration` | 明确类型的意向、模型及当前参数 |
| `frozen_request` | `NormalizedGenerationRequest` 或 null | 首次受理时固定的完整不可变请求 |
| `created_at`, `updated_at` | UTC Instant | Java 记录时间 |
| `frozen_at` | UTC Instant 或 null | 首次受理时间 |

`DRAFT` 要求 `frozen_request` 和 `frozen_at` 都为 null。`FROZEN` 要求两者均存在；单文件请求必须准确反映执行配置，要求文件数则冻结在意向中。可复用的 plan 不持久化 `READY`、执行中、已完成或失败状态；执行状态由 Task 管理。

`SessionPlanFocus` 包含 `session_id`、允许为 null 的 `active_plan_id` 及 `revision`。只有聚焦指针发生变化时，聚焦 revision 才递增。后台 Task 更新、普通 Session 消息及 plan 参数修改都不会递增它。

创建、克隆及跨媒体创建会原子地将聚焦切换到新 plan。聚焦操作可以选择草稿或冻结 plan。Task 完成不会改变聚焦。读取可以查看非聚焦 plan；除明确的聚焦操作外，修改操作必须针对当前聚焦的源 plan，并携带观察到的聚焦 revision。

### 配置、意向与用户要求

两种配置变体都包含 `model_selection`、`intent`、`parameters` 和 `parameter_origins`；通过 `type` 判别 image 与 video。在收集输入期间，`model_selection` 可以为 null。

`ModelSelection` 包含 `model_id`、`profile_id` 和 `capability_revision`。revision 由 Java 管理。工具根据模型目录的观察结果选择 `ModelChoice(model_id, profile_id, expected_capability_revision)`。Java 拒绝过期的选择，不会悄悄改用新能力版本。ModelChoice、ModelSelection 及所有工具参数保持不变；工具不能提供或修改供应商绑定。Java 根据所选模型及准确的能力 revision 推导绑定。

`ImageIntent`／`VideoIntent` 包含非空白的 `description` 及对应的 `ImageRequirements`／`VideoRequirements`。description 保留自然语言目标，包括 Java 不解释的风格和内容。已知的数值及素材约束应放入明确类型的要求字段。

| 要求字段 | Image | Video | 取 null 的语义 |
| --- | --- | --- | --- |
| `generation_mode` | `IMAGE` | `VIDEO` | 没有明确要求的输出媒体类型 |
| `prompt` | 文本 | 文本 | 没有要求逐字保留的 prompt |
| `size` | `DraftOutputSize` | `DraftOutputSize` | 没有尺寸要求 |
| `requested_file_count` | 正 int32 | 正 int32 | 请求一个文件 |
| `references` | `ReferenceAsset` 数组 | `ReferenceAsset` 数组 | 没有精确的素材列表要求 |
| `output_format` | `PNG`, `JPEG` | `MP4`, `MOV` | 没有编码格式要求 |
| `duration_seconds` | 不存在 | 正 int32 | 没有时长要求 |
| `generate_audio` | 不存在 | boolean | 没有音频要求 |

只有需要逐字保留 prompt 时，才设置精确 prompt 要求；其他情况下由 description 表达语义目标。Java 比较精确 prompt 时，不会悄悄去除首尾空白或改写文本。

用户要求可以在结构上合法，但不受当前模型或平台提交政策支持。这些要求仍保留，并产生提交阻塞项。要求中的空素材数组明确表示不使用素材；null 表示没有精确列表约束。非 null 数组要求使用相同顺序的素材 ID 列表。false 是明确要求，不等于未提供。`generation_mode` 只表示输出媒体类型，不能据此推断输入场景。

### ImageParameters 与 VideoParameters

执行候选参数使用相同的 prompt、尺寸、素材及输出格式字段。Video 增加时长和 `generate_audio`，后者控制同一个输出视频内的音轨，不表示单独的文件。Image 不包含时长字段。草稿中的候选标量字段可以为 null；候选素材数组必须出现，初始为空。这些字段表示当前选择，不一定就是用户要求。`requested_file_count` 是提交要求，不是执行参数、模型默认值或参数来源字段。

候选素材数组为空时，始终表示当前尚未选择素材，包括执行 SET `[]` 或 CLEAR 之后。在规范化过程中，它被视为缺失的选择：如果存在受支持的非空素材要求，可以用该要求填入候选参数。要明确要求不使用素材生成，工具必须将要求中的素材列表 SET 为 `[]`；仅清空候选参数不能表达这一要求。没有素材要求时，空候选数组可以满足允许不使用素材的 profile。

`ParameterOrigin(field, source, source_model)` 对每个非 null 标量／尺寸组成部分，以及每个非空素材列表，恰好记录一次参数来源。`source=EXPLICIT` 要求 `source_model` 为 null。Java 应用的 `MODEL_DEFAULT` 必须记录提供该默认值的准确 `ModelSelection`。缺失值及空素材列表不记录参数来源。比例和分辨率分别记录参数来源；派生的预设 ID 不单独记录参数来源。工具不能提交参数来源信息。

更换模型后保留的兼容参数继续保留原参数来源。保留旧模型的默认值属于保留已有选择；返回结果不能将它描述为本次新应用的目标模型默认值。

### 尺寸与要求文件数

`DraftOutputSize` 是封闭的联合类型：

- `RatioSize(type=ratio_size, aspect_ratio?, resolution_id?)`：允许逐步收集。`AspectRatio(width_units, height_units)` 使用正 int32 比例单位；Java 用最大公约数 GCD 约分。
- `PixelSize(type=pixel_size, dimensions)`：`PixelDimensions(width, height)` 要求宽和高都是正 int32。

两种变体互斥。替换尺寸时替换整个尺寸对象，不会合并像素与比例字段。模型分辨率 ID 是目录键，不是对 2K 等标签的通用解释。

V1 图片执行支持目录列出的尺寸预设，以及明确支持的自定义像素尺寸。V1 视频执行支持目录列出的尺寸预设和正整数秒时长；供应商的帧数与自动时长选项不属于本规范化契约。

Java 将完整的比例／分辨率组合解析为所选 profile 中唯一的 `SizePreset`。每个预设定义 `preset_id`、比例、分辨率 ID 及具体像素尺寸。注册经过验证的预设时，必须给出尺寸且比例一致；未知映射不能被视为不受限制的预设。自定义像素规则明确规定边界、尺寸步长、像素面积上限及允许的比例。计算面积和聚合时长时，使用带溢出检查的 64 位运算。

`requested_file_count` 是可为 null 的正 int32 要求。null 的有效值为一。大于一的值表示用户明确要求多个文件；agent 不能擅自填写，不能从模型默认值获取，也不能通过额外提交隐式凑足数量。Java 为每个要求的文件创建一个 Task，每个 Task 恰好请求一个输出文件。即使明确数量超出当前政策并阻止提交，该数量仍作为要求保留。

首次受理时，数量与 plan 的意向／配置一起冻结。复用 FROZEN plan 会创建相同有效数量的新 Task；修改数量必须先克隆，再编辑草稿。null 与明确的一保留各自原始传输语义，但都会创建一个 Task。Task 失败时不自动补发。

### 素材引用

`ReferenceAsset(type=reference_asset, asset_id)` 是图片和视频 plan 中所有非文本输入共用的唯一引用结构。Java 根据可信元数据判断实际 `IMAGE`、`VIDEO` 或 `AUDIO` 媒体类型及检测出的 MIME，不使用模型提供的角色。保留素材顺序，拒绝重复的 `asset_id`。Lux 入口最多允许 64 个素材引用，所选 profile 还会进一步收紧限制。这些工具不包含角色、帧位置、任意 URL、本地路径、供应商任务或凭据字段。

输入组合由显式 profile 和媒体限制定义。connector 可以使用经过验证、由所选 profile 适配器负责的固定供应商映射，例如 profile 只允许一个图片引用，适配器将其映射到明确的供应商输入字段。能力证据记录这项验证。Java 和 connector 都不能仅凭通用数组顺序猜测首帧／尾帧分配，也不能替用户做出需要尚未提供输入的角色决策。如果供应商路径需要用户区分当前契约无法表达的帧角色，该路径仍不可用；不受支持的媒体类型或组合仍构成阻塞项。

Java 通过可信的内容检查边界获取事实，使用具名的 `VerifiedImageMetadata`、`VerifiedVideoMetadata` 和 `VerifiedAudioMetadata` 变体。这些事实绑定素材 ID、实际校验和、字节数、检测出的 MIME 及媒体类型；图片／视频包含尺寸，视频／音频包含时长，视频还包含音频是否存在。事实必须匹配不可变素材的校验和。模型提供的事实，以及上传时声明的 MIME，都不能作为充分证据。

## 模型能力结构

`ModelCatalogSnapshot(type=model_catalog_snapshot, catalog_revision, submission_policy, models)` 包含平台提交政策和图片/视频 `ModelDefinition` 变体。每个定义都指定 `model_id`、显示名称、正整数能力 revision、产品 `enabled` 标志、必填的 `provider_binding: ProviderBinding` 及相应的 profile 数组。`ImageModelDefinition` 和 `VideoModelDefinition` 都要求该绑定。目录键为 `(media_type, model_id)`；同一个 ID 可以分别具有图片和视频定义。revision 标识不可变的内容，包括默认值和供应商绑定。修改绑定时，能力 revision 和目录 revision 都必须递增。

`ProviderBinding(provider_id, provider_model_id, adapter_id)` 包含三个必填、非 null、非空白的 `String` 字段。它们标识经过验证的执行路由，不包含凭据或端点。

| 字段 | Lux 最大长度 | 含义 |
| --- | --- | --- |
| `provider_id` | 64 | 供应商注册表的键 |
| `provider_model_id` | 255 | 第三方实际的模型／部署 ID，按不透明值处理，不能猜测或解析别名 |
| `adapter_id` | 128 | 带版本的接口适配器注册表键；版本已包含在键中 |

不另设单独的适配器 revision 字段。一个适配器 ID 所标识的契约版本不可变：不能原地改成不同供应商、API 或字段映射。适配器契约变化时，必须使用新的带版本 ID，并修改绑定。Java 校验绑定与模型媒体类型、所选 profile、请求判别字段及 schema 版本一致。connector 负责已注册的适配器实现、供应商账户／凭据、实际端点、HTTP 传输、字段映射、Asset 解析及结果验证。这些实现细节都不成为 plan 工具参数。本设计不注册具体供应商／模型绑定或适配器。

首个实现将定义保存为具名类型的 Java 代码。Java 拥有产品允许使用的能力子集；connector 代码拥有供应商参数转换逻辑。跨语言 fixtures 和明确的证据保证二者一致。本规范不注册任何能力值或默认值。

`GenerationSubmissionPolicy(maximum_tasks_per_submission)` 包含正 int32 的平台上限，与模型能力和默认值分开。Java 在每次新提交时，将有效 `requested_file_count` 与此上限比较，包括复用冻结 plan。超出上限的要求可以保存，但会产生 `REQUESTED_FILE_COUNT_UNSUPPORTED`，对应 `REQUESTED_FILE_COUNT` 字段，并阻止提交；Java 不得悄悄减少数量。示例中的政策值仅用于说明，不代表已注册生效的服务配置。

### 使用 profile，而不是相互独立的能力数组

一个 profile 表示一种合法输入场景。它将输出媒体类型、显式输入媒体组合、参考素材规则和输出选项绑定在一起。`generation_mode` 为 `IMAGE` 或 `VIDEO`，不再区分输入到输出的类别。模型分别支持某个时长、某种分辨率和参考素材，并不能证明它支持这些条件的组合。当规则依赖输入场景时，应使用不同的显式 profile，而不是通用表达式语言或仅隐藏在 Java 中的判断条件。

| 已验证的 profile 字段 | 图片 | 视频 |
| --- | --- | --- |
| `profile_id`, `generation_mode` | 必填 | 必填 |
| `prompt_rules` | 最大 UTF-8 字节数 | 最大 UTF-8 字节数 |
| `size_rules` | 预设、可选的自定义像素规则 | 预设；v1 中自定义像素规则必须为 null |
| 输出选项 | 此 profile 支持的尺寸 | `VideoOutputOption(size_preset_id, duration_seconds)` |
| `reference_rules` | 必填 | 必填 |
| `output_formats` | 非空的 PNG/JPEG 子集 | 非空的 MP4/MOV 子集 |
| 音轨 | 不存在 | 显式、非空的 `generate_audio_values` 布尔值列表 |
| 时长容差 | 不存在 | 用于结果验证的非负毫秒数 |
| `defaults` | `ImageDefaults` | `VideoDefaults` |
| `evidence` | 非空的 `CapabilityEvidence` 数组 | 同左 |

`PositiveIntDomain` 为 `DiscreteIntDomain(values)` 或 `SteppedIntDomain(minimum, maximum, step)`。值必须为正数并严格递增；区间最小值不能大于最大值，步长必须为正数。成员判断条件为 `minimum <= n <= maximum` 且 `(n-minimum) % step == 0`。视频时长选项使用此取值域；要求文件数不属于模型能力取值域。

`ReferenceRules` 定义总数量的最小值/最大值、`media_limits`、视频/音频总时长限制，以及可选的输入视频时长与输出时长之和的限制。每个 `ReferenceMediaLimit` 定义 `media_type: ReferenceMediaType`（`IMAGE`、`VIDEO` 或 `AUDIO`）、`minimum_count`、`maximum_count`、`mime_types`、`max_asset_bytes`、可为 null 的 `dimensions` 和可为 null 的 `max_duration_milliseconds`。每种媒体类型最多出现一次。未提供媒体条目表示不支持该媒体类型。`AUDIO` 要求 `dimensions=null`；`IMAGE` 要求 `max_duration_milliseconds=null`。Java 根据可信元数据变体验证这些字段。必需媒体数量必须能够在总数量限制内同时满足。不提供角色限制、禁止共存的角色对或首帧/尾帧匹配规则。在已验证的 profile 中，可选限制为 null 表示此产品 profile 没有增加该限制；它绝不表示供应商限制未知。支持证据不完整时，必须将 profile 标记为不可用。

`CapabilityEvidence(source_kind, reference, checked_at, note)` 区分官方文档、契约验证和产品策略。产品策略可以缩小已经验证的供应商支持范围，但不能据此认定未经验证的供应商支持。供应商账号的访问权限和运行时失败仍属于执行结果。

`UnavailableImageProfile` / `UnavailableVideoProfile` 暴露 profile ID、模式、`status=UNKNOWN|UNSUPPORTED` 和原因，不包含可执行的规则/默认值。这些 profile 可以读取，但不能为可执行计划选用。空列表、缺失的目录条目和未知的边界绝不表示无限制支持。

### 目录验证

Java 在启动时验证代码中的目录，并拒绝无效注册：

- 模型键、profile ID、预设 ID 和比例/分辨率对必须唯一；revision 及媒体/profile 类型必须有效。每个模型都必须具有结构合法的供应商绑定；已验证的可执行注册要求存在支持对应媒体类型、profile、`request_type` 和 `schema_version` 的已注册供应商／模型／适配器组合。未知或不受支持的绑定不能证明可执行支持。
- 取值域必须非空，选项必须唯一；上下限有序、步长有效，数值乘积必须处于安全范围内。
- 视频输出选项必须引用已声明的预设。每个已声明支持的预设都必须具有输出选项；选项不能宣称支持被禁止的尺寸/时长组合。
- 参考媒体限制定义 profile 支持的输入组合。每个 `media_type` 条目必须唯一。无参考素材的 profile 必须禁止素材；必需的图片/视频/音频数量必须能够在总数量限制内同时满足。`AUDIO` 要求 `dimensions=null`，`IMAGE` 要求 `max_duration_milliseconds=null`；尺寸/时长字段及 MIME 类型必须符合声明的输入媒体类型。
- 每个提供的默认值都必须符合其 profile。如果提供多个默认值，它们的组合必须受支持。必需的用户输入不要求一定有默认值。提示词和参考素材没有目录默认值。
- 预设尺寸和比例必须一致。图片自定义尺寸按上下限/步长/面积/比例检查；此 v1 契约不支持视频自定义尺寸。
- 每个已验证的 profile 至少需要一条 `OFFICIAL_DOCUMENTATION` 或 `CONTRACT_VERIFICATION` 证据。单独的 `PRODUCT_POLICY` 不能证明供应商支持，只能收紧已验证的范围。
- 已注册的规范化路径必须具备已实现的 adapter/结果验证路径，支持恰好一个输出文件，并明确关闭附属输出。供应商参数在结构上兼容，并不足以构成证据。
- 重复的布尔值选项、未声明的格式、重复或不可能满足的媒体限制和未知的可执行 profile 均被拒绝。正数的平台提交上限与模型注册分开验证。

## 规范化与要求匹配

规范化是确定性的，并且只对草稿运行。创建和更新时：

1. 解码精确的变体，并检查基础不变量、所有者、Session、聚焦和 revision。
2. 原子应用具名类型的要求补丁和候选项补丁。拒绝格式错误或明确选择但不受支持的候选项；允许格式正确但不受支持的要求。
3. 如果新要求与此前的 `MODEL_DEFAULT` 候选项矛盾，移除该候选项。绝不能静默覆盖不兼容的显式候选项；应保留它，并添加要求阻塞项。
4. 如果某个候选组成部分缺失且存在对应的用户要求，只有选定 profile 支持该要求时才将其复制为候选项。否则继续保持缺失，并报告要求阻塞项。尚未选定模型时，格式正确的要求值可以填入候选项，但仍未通过提交验证。
5. 只有缺失且没有对应要求的组成部分，才应用目录默认值。绝不能用默认值解决不受支持的用户时长、尺寸、格式或参考素材要求，也不能增加要求文件数。
6. 已选择模型时，根据其准确的能力 revision 解析供应商绑定，验证适配器／结果验证支持及完整组合，再返回完整快照。组成部分、参考素材元数据、兼容默认值或受支持的绑定缺失时，草稿可以成功保存，但仍不可提交。推导出的默认值组合无效时，应形成阻塞项，而不是获得修改显式选择的许可。

标量要求按精确值比较。比例要求比较约分后的分数；像素要求比较具体尺寸，包括从预设解析得到的尺寸。部分比例尺寸要求只约束其中非 null 的组成部分。分辨率要求匹配选定预设的分辨率 ID；任意自定义像素尺寸并不意味着对应的分辨率标签。格式和音轨要求都是显式要求。要求文件数按平台提交政策检查，不复制为候选项。描述不能替代这些检查。

`parameter_origins` 和本次新应用的 `applied_defaults` 由 Java 计算。agent 必须根据返回的参数解释默认值，不能编造默认值。冻结时，Java 将所有必需的候选项解析为 `ImageExecutionParameters` / `VideoExecutionParameters`，将准确解析的供应商绑定复制到规范化请求中，并持久化。复用冻结计划绝不运行规范化、应用当前默认值，或为同一模型 ID 替换为较新的绑定。

## 工具调用输入

模型只能看到下列具名的 `arguments` 类型。Python 注入所有者/Session 身份和原始调用 Item ID；模型不能提供这些身份或参数来源。

| 工具 | 完整参数字段 | 返回结果 |
| --- | --- | --- |
| `get_model_catalog` | 可为 null 的 `media_type` | `ModelCatalogSnapshot` |
| `read_generation_plan` | `query: PlanQuery` | `GenerationPlanReadResult` |
| `create_generation_plan` | `target`、类型匹配的 `intent`、类型匹配的 `parameters`、`expected_focus_revision` | `PlanMutationReceipt` |
| `update_generation_plan` | `plan_id`、`expected_plan_revision`、`expected_focus_revision`、类型匹配的 `patch` | `PlanMutationReceipt` |
| `change_generation_target` | `plan_id`、`expected_plan_revision`、`expected_focus_revision`、`target` | `PlanMutationReceipt` |
| `clone_generation_plan` | `source_plan_id`、`expected_source_plan_revision`、`expected_focus_revision` | `PlanMutationReceipt` |
| `focus_generation_plan` | `plan_id`、`expected_focus_revision` | `PlanFocusReceipt` |
| `submit_generation_plan` | `plan_id`、`expected_plan_revision`、`expected_focus_revision` | `PlanTaskSubmissionReceipt` |

对于已经证实的业务拒绝，所有工具都可以返回 `PlanToolRejection`。现有 `get_task` 仍作为 Task 观察工具。

`PlanQuery` 为 `FocusedPlanQuery(type=focused)`、`PlanByIdQuery(type=by_id, plan_id)` 或 `SessionPlansQuery(type=list, cursor?, limit)`。limit 范围为 1..50。cursor 包含 `before_created_at` 和 `before_id`；分页按不可变的创建时间/ID 降序进行。未聚焦任何计划时，聚焦查询返回零个计划以及聚焦快照。按 ID 查询未命中时，返回限定在当前作用域内的拒绝。读取在一次一致观察中返回计划及验证快照。

`GenerationTarget` 为 `ImageTarget(media_type=IMAGE, model_choice?)` 或 `VideoTarget(media_type=VIDEO, model_choice?)`。null 表示尚未选择模型；新草稿允许这种情况。已知、启用且经过验证的目标选择必须匹配已观察到的能力 revision。新媒体类型必须与其参数/要求一致。清除草稿的模型属于显式目标操作，并会阻止提交。

### 具名类型的补丁

`ImagePlanPatch` / `VideoPlanPatch` 包含 `description`、类型匹配的 `requirements` 补丁以及类型匹配的 `parameters` 补丁。每个补丁字段都是具名的 `KEEP`、`SET(value)` 或 `CLEAR` 变体之一。description 只允许 KEEP 或非空白的 SET。

```json
{
  "op": "SET",
  "value": 2
}
```

```json
{"op": "KEEP"}
```

```json
{"op": "CLEAR"}
```

SET 的值不能为 null。对要求执行 CLEAR 会移除该要求；对候选项执行 CLEAR 会移除当前选择。缺失且不受要求约束的候选项可能立即获得目录中明确定义的默认值。清除必需的候选项不会移除其要求，也不会授权应用与要求冲突的默认值。候选参考素材的 CLEAR 变为空/缺失的选择 `[]`；要求中的参考素材 CLEAR 变为 null，而要求的 SET `[]` 表示要求不使用参考素材。候选项的 SET `[]` 仍允许将受支持的要求具体化为候选项。

`requested_file_count` 只在要求补丁中使用 `RequestedFileCountChange`。SET 要求正 int32；CLEAR 恢复 null，有效数量为一。共用的 `SetReferences` / `ReferencesChange` 类型适用于图片和视频引用补丁。尺寸和参考素材补丁会替换整个值。不提供任意字段路径或 JSON Patch 字典。已知模型 ID、profile、媒体类型、revision、状态、参数来源和冻结快照都不是补丁字段。

### 目标变更与克隆

同媒体类型的变更要求计划为 DRAFT。Java 在一个事务中解析目标并迁移候选项，保留完整意图。无实际变化的选择不推进计划 revision。配置实际发生变化时，revision 只推进一次。

迁移按固定顺序进行：

1. 只有提示词符合目标规则时才保留。只有自定义像素尺寸受支持时才保留；对于比例尺寸，先保留允许的宽高比，再保留兼容的分辨率，并清除不受支持的组成部分。
2. 只有全部实际媒体类型都受支持时才保留参考素材列表，并保持其顺序。当媒体类型、数量或总量限制不满足时，清除整个列表，而不是静默选择其中一部分。缺少可信元数据时形成阻塞项，而不是凭空认定不兼容。
3. 对于视频，保留在所选尺寸和参考素材预算下受支持的时长。如果组合不满足，清除时长。保留受支持的格式和音轨选择。
4. 保留所有要求，只规范化符合条件的缺失候选项，并报告已清除字段和剩余问题。必需参考素材缺失是阻塞项，不能据此编造输入语义。要求文件数保持不变，并单独按平台政策验证。

只有部分尺寸信息时，保留的时长必须至少适用于一个仍可能选择的选项；最终验证检查实际选定的选项。规范化绝不为与要求冲突的显式选择寻找替代值。

跨媒体类型变更可以从 DRAFT 或 FROZEN 发起，在校验源计划的所有者、Session 归属和请求的 plan revision 后创建新的 DRAFT。它复制描述作为上下文、存在的宽高比要求，以及 `requested_file_count`（包括 null）。它不复制字面的提示词要求、提示词候选项、profile/模式、分辨率或像素尺寸、格式、参考素材、时长或音轨选择。这些内容在不同媒体类型中的含义不同。源计划保留所有原始要求和参数。结果列出 `non_migrated_requirement_fields`；agent 必须向用户说明这些字段，并使用显式、目标类型匹配的更新来采用其中任何内容。它不能自动给参考素材分配帧语义。

克隆可从任一状态发起，校验源计划的所有者和 Session 归属，锁定源计划的当前记录，并要求其 revision 等于 `expected_source_plan_revision`。不匹配时返回 `PLAN_REVISION_CONFLICT`；该工具不读取历史版本。检查通过后，将当前意图/配置复制到新的 DRAFT，清除冻结元数据，并将聚焦设置为克隆计划。它绝不修改源计划。已固定的选择和参数值直接复制，不应用新的默认值，也不刷新绑定；根据当前对该准确 revision 及其绑定的支持情况重新评估是否可以提交。只有显式选择新目标才能选用不同的绑定。克隆本身不会发起生成。克隆和跨媒体创建都不在新计划上持久化源计划关系。操作回执保留 `source_plan_id` 及新计划的完整创建快照，作为该次操作的证据。

### 返回结构

`GenerationPlanSnapshot` 包含 `plan` 和 `PlanValidation(can_submit, issues, validated_catalog_revision)`。验证是当前观察结果，不属于不可变的已存储计划。只有 issues 为空、选定能力仍被允许且所有受理前提均满足时，`can_submit` 才为 true。冻结计划在历史上可以有效，但今天可能已无法再次提交。

`ValidationIssue` 具有封闭的错误码、封闭的字段、可选的参考素材索引、有长度上限的消息，以及可为 null 的模型选择。它不包含任意通用值。完整的具名类型请求、当前参数和目录提供具体值/选项；参考素材索引用于关联参考素材错误。问题码在 schema 中声明，包括不受支持的要求、缺失元数据、组合冲突、缺失 adapter 和缺失结果验证器。

`PlanMutationReceipt` 返回操作、可为 null 的源计划 ID、完整快照、聚焦快照、已清除候选字段列表、未迁移要求字段列表，以及 `AppliedDefault(field, source_model)` 数组。保留下来的默认值不属于本次新应用的默认值。

`PlanFocusReceipt` 返回聚焦。`PlanTaskSubmissionReceipt` 返回 `plan_id`、`frozen_plan_revision`、`accepted=true`、有效受理的 `requested_file_count`、非空 `tasks` 列表和观察到的聚焦。每个 `SubmittedTask(index, task_id, task_operation_id)` 使用从零开始的连续索引。列表长度必须等于数量；Task ID 和子操作 ID 必须唯一，且任何子操作 ID 都不能复用父操作 ID。即使只有一个 Task，也返回长度为一的列表。父 `operation_id` 只出现在外层 `JavaPlanToolOperationReceipt`；Java 为每个子操作分配独立 UUID，包括单文件情况，并原子地持久化完整映射。受理不表示供应商已接受，也不表示生成成功。

`PlanToolRejection` 包含封闭的错误码、消息、可为 null 的当前计划/聚焦 revision、具名类型的问题列表以及 `business_execution=NOT_PERFORMED`。这表示没有执行任何计划/聚焦/Task 业务变更或生成；允许存储拒绝回执。它不能用于描述不确定的 HTTP/数据库/供应商结果。本地批次预检可以返回相同的拒绝变体，此时不提供 revision，也不调用 Java。

## Java 工具传输与回执

adapter 构建 `JavaPlanToolInvocation(type=generation_plan_tool_invocation, operation_id, session_id, tool_name, arguments)`。对于每个计划工具，包括读取工具，`operation_id` 都等于原始 TOOL_CALL Item UUID。供应商的 `call_id` 仍是独立身份，并且必须保留，用于调用/结果配对。

拟定的内部路由：

- POST `/internal/v2/users/{user_id}/sessions/{session_id}/generation-plan-tools/{tool_name}` 接收具名调用，并返回具名类型的 `JavaPlanToolOperationReceipt`。
- GET `/internal/v2/users/{user_id}/sessions/{session_id}/generation-plan-tool-operations/{operation_id}` 只读取原始操作回执。

路径中的 Session/工具必须与请求体和可信运行时绑定一致。所有者仍由当前可信的内部 user 路径选择；此提案不赋予该路径认证作用。所有查询和授权仍限定在所有者/Session 作用域内。

`JavaPlanToolOperationReceipt` 包含 schema 版本、操作 ID、Session ID、工具名、SHA-256 请求摘要，以及一个封闭的 `GenerationPlanToolResult` 变体。摘要输入是原始所有者/Session/工具/具名类型参数，而不是最新计划内容。面向用户的 Item 内容保留原始模型参数；注入的操作元数据属于传输/回执层。

使用具名封装、独立的操作命名空间和具名类型的原始回执查询，复用现有 `http_request` 幂等存储和事务基础设施。不要仅为计划引入第二张回执表。存储成功结果和通过有效解码的业务拒绝。格式错误的请求可以在入口处失败而不产生回执。除非已经证明未执行，否则技术失败保持 UNKNOWN。

回执查询和重放先于当前版本/能力检查。同一键和完整请求返回原始响应，包括其中的旧快照/聚焦以及任何拒绝。在同一所有者作用域内，复用键但携带不同 Session/工具/参数会发生冲突。修正被拒绝的调用时，需要使用新的调用/操作身份。重放旧回执时，不得将其当作新观察而覆盖当前聚焦/上下文；需要时获取新快照。

对于结果不确定的操作，查询其原始回执，或通过幂等端点重新发送完全相同的具名调用/身份。回执缺失可能是因为原请求尚未提交事务；这并不能证明供应商操作从未发生。绝不能创建新操作来恢复结果不确定的提交。读取也保留其原始观察结果，使运行时恢复不会用较新的结果替代被中断调用的结果。

## Java 校验与事务规则

| 阶段 | 必需检查 | 结果 |
| --- | --- | --- |
| 解码 | 封闭变体、必填字段、禁止额外字段、UUID、非空白文本、整数边界，以及显式 false/null/列表语义 | 在业务处理前拒绝格式错误的输入 |
| 操作身份 | 原始回执的作用域和完整参数哈希 | 重放原始结果，或返回身份冲突 |
| 所有权 | 用户/Session 存在；每个计划/源计划及 Asset 均属于该所有者，并处于正确的 Session 作用域内 | 在限定作用域内拒绝，且不泄露其他所有者的信息 |
| 并发 | 预期的业务计划 revision 和聚焦 revision；聚焦源计划与显式 ID 一致 | 对过期状态返回可确认的类型化拒绝 |
| 状态 | 仅 DRAFT 可编辑或切换同媒体类型的模型；FROZEN 允许读取、克隆、跨媒体创建和重复提交 | 对禁止的修改返回 PLAN_FROZEN |
| 目标 | 目录键、已启用模型、匹配的媒体类型、经验证的 profile、准确的能力 revision 及其供应商绑定 | 不猜测模型别名、默认模型或 fallback，也不替换路由 |
| 候选配置 | 尺寸结构、提示词限制、有序引用、格式、视频时长/音轨 | 原子地拒绝无效的显式赋值 |
| 用户要求 | 精确/部分匹配规则、profile 支持情况，以及有效要求数量与平台政策的比较 | 保留要求并暴露阻塞项 |
| 引用 | 所有权、当前可用性、可信文件签名/元数据，以及媒体/数量/MIME/字节数/尺寸/时长限制 | 未知事实会阻塞；它们不能证明成功 |
| 组合 | 合法的尺寸-时长输出选项，以及场景特定的引用规则 | 阻塞不受支持的组合 |
| 执行就绪 | 已固定绑定匹配供应商／模型／适配器、媒体／profile／`request_type`／`schema_version`；已实现的规范化适配器／结果验证器及当前可用性 | 不通过不受支持的路径提交；适用时使用 `EXECUTION_ADAPTER_UNAVAILABLE` 或 `RESULT_VERIFIER_UNAVAILABLE`，对应 `PROVIDER_BINDING` |
| 提交 | 完整的生效值、冻结请求/数量一致性、回执成员及唯一连续的子身份；重新授权 Asset | 原子地完成冻结/全部 Task/全部 Outbox 信封/父回执/通知 |

Java 必须以统一顺序获取相关的所有者/Session/聚焦/计划锁。保留现有的短事务和有界的整个事务重试模式。媒体检查、字节传输、供应商调用及消息代理发布均须位于数据库重试回调之外。在事务外准备的事实必须绑定不可变的 Asset 校验和；受理事务会重新检查所有者/存在性/校验和，以及已观察到的目录 revision（包括提交政策）。它无法使外部供应商可用性与数据库操作保持原子性。

每次实际配置/状态变更只推进一次计划 revision。无实际变化的更新和拒绝操作均不改变 revision。源计划的克隆/创建操作不更新源计划。首次冻结只推进一次 revision，并固定时间戳/请求；后续从 FROZEN 提交不会改变计划 revision 或时间戳。并发的首次提交若携带较旧的草稿 revision，在另一提交先完成冻结后会被拒绝，除非它是在重放同一操作。调用方随后可以使用新的操作身份，提交已观察到的冻结 revision，请求另一次生成。

### 首次提交与后续提交

对于新操作，先检查作用域/聚焦/CAS 和准入条件，然后：

1. DRAFT：在准确的所选能力 revision 下解析完整的单文件类型化请求、冻结的结果预期、参数来源及供应商绑定；验证绑定并复制到冻结请求中，确定有效要求文件数，冻结计划一次，并递增 revision。
2. FROZEN：复制其原始规范化请求及冻结的有效文件数，不应用默认值、不迁移值，也不根据新的能力 revision 重新编译。复用原始冻结供应商绑定，不从当前同一模型 ID 的定义中读取替代路由。已固定的 revision/profile/适配器必须仍受支持，且当前提交政策必须允许冻结数量；否则阻塞，并通过对话提供克隆/修改方案。
3. 重新授权所引用的 Asset。对于数量 N，分配 N 个新 Task ID 和 N 个独立的子 `task_operation_id` UUID，包括 N 为一的情况。保存 N 个 Task，其中包含所有者、Session、计划 ID、子执行身份，以及同一单文件规范化请求的不可变副本。
4. 保存 N 条生成 Outbox 信封，每条都绑定对应 Task 和子操作身份；保存一份包含全部 `SubmittedTask` 条目的完整父操作回执；按需发出计划/聚焦/Task 的前端失效通知；将这些操作一并提交。

受理必须全部成功或全部失败：回滚会使草稿保持未冻结状态，且不创建任何 Task/Outbox 信封。提交后出现的不确定响应通过原始父回执核对，绝不能分配新的子身份进行恢复。受理后各子 Task 独立执行；一个子 Task 的供应商/导入失败不会回滚或补发其他子 Task、解冻计划、修改其请求，或自动发起另一次生成。重放父操作时返回原回执保存的有序子 Task 身份。

### Agent 批次顺序

当前工具会并发执行。仅依靠预期 revision 无法保护“先更新再提交”的意图：提交可能先完成并冻结更新前的版本。

在调用某次模型响应中的任何 Java 工具之前，Python 会检查整个批次。当一个有依赖关系的组包含多个针对同一计划的修改、修改加提交、多次草稿提交，或更改聚焦的操作与任何依赖聚焦的修改/提交同时出现时，必须在调用前拒绝该组的所有成员。返回 `DEPENDENT_TOOL_BATCH`，并保证可以确认未执行。只有在语义允许时，才运行独立的组。

模型必须先修改，观察新的快照/revision，然后在后续模型响应中提交。只读观察可以并发执行；它们代表各自独立捕获的观察。对已经处于聚焦的 FROZEN revision 发起多次各自独立、有明确生成意图的提交可以并发执行；每次都按冻结的要求数量创建各自的 Task。它们不会合并为隐式文件批次，也不能代替明确的多文件要求或绕过提交政策。停止 Run 不会撤销 Java 已受理的 Task。

在对话压缩后，以及每次模型请求之前，适配器会在压缩历史记录后提供最新的权威聚焦、聚焦计划快照和相关目录 profile。如果权威读取失败，依赖这些状态的生成必须被阻塞；不得从摘要重建状态。检查点中的 ID/快照是引用和观察，不是 Java 状态的权威来源。

上下文补充是内部只读观察，与 LLM 工具调用不同。它使用限定作用域的读取服务/端点，不人为创建 TOOL_CALL Item 或模型操作回执。八个显式模型工具仍按前述规定保留各自的原始观察/操作回执。

## 冻结请求与结果预期

`ImageGenerationRequest` / `VideoGenerationRequest` 携带唯一的 `request_type` 判别字段、`schema_version=1`、模型选择、必填的 `provider_binding`、完整执行参数及相匹配的结果预期。它们是封闭的 `NormalizedGenerationRequest` 变体。必须显式解码判别字段；格式错误的规范化变体绝不能继续尝试按旧版供应商请求解析。Java 根据模型选择及准确的能力 revision 推导绑定，并在首次受理前检查媒体／profile／判别字段／版本一致性；LLM 绝不提供该绑定。

connector 根据绑定以及 `request_type` 和 `schema_version` 选择经过验证的已注册实现。这两个字段标识传输契约，本身不表示供应商路由。它检查供应商／适配器／模型／profile 兼容性，并明确拒绝未知或不受支持的路由；不能通过 fallback 切换供应商或模型。冻结绑定是每个子 Task 不可变规范化请求的一部分，提交文件数绝不成为供应商执行参数。

执行参数中的必填值不得为 null。图片使用已解析的预设或经校验的自定义像素尺寸。视频使用已解析的预设，以及显式的时长/音轨值。生成模式就是所选 profile 的输出媒体类型。要求文件数不出现在执行参数中。Asset 引用在 Java 的计划和 Task 快照中保持为稳定 ID。

`ImageResultExpectations` 固定 `file_count=1`、尺寸和编码。`VideoResultExpectations` 还固定时长/容差及预期音轨存在情况。Java 在受理前验证结果预期与参数、能力证据一致。这条规范化路径不允许任何附属输出：供应商适配器必须明确关闭附属输出，并支持每个 Task 恰好一个文件。如果供应商提供数量参数，适配器必须明确固定为一；不能依赖未知默认值，也不能将提交文件数传给供应商。无法确定这一契约的路径应标记为不可用，而不是悄悄放宽。

请求校验不能证明输出满足要求。在业务成功之前，负责结果/导入的边界必须验证恰好一个文件、检测到的编码、实际尺寸、视频时长容差和音轨要求。返回零个或多个文件属于已确认的结果失败；connector 和导入方不能悄悄丢弃多余文件、放宽受理数量或再次进行付费调用。现有失败 Task 规则下已验证 Asset 的保留，与成功满足单文件要求是不同的边界。预期音频值为 false 表示不能有音轨，而不只是无需检查音频。

Connector 需要经过授权的 Asset 解析/交付边界，将稳定 ID 转换为供应商可读取的媒体。每个新 Task 都可以获得新的传输定位信息，同时复用冻结计划。Connector 在可能派发之前固定所选账户，并持久化准确的、已解析的供应商负载及适配器身份。账户／凭据处理仍由 connector 负责，不进入 plan 绑定。重放或恢复不得改变冻结绑定、所选路由或已保存的供应商负载；结果不确定的操作绝不能因 URL 过期而重新提交。签名定位信息和凭据不会成为计划数据或模型可见参数。

规范化 v1 契约之外的供应商特定标志仍由 Connector 负责。Connector 记录其适配器身份和完整的已提交负载，以支持恢复。它不得默默推断、丢弃或改写已声明的规范化要求。本设计不引入自动选模、fallback 或隐式 fan-out。同一次提交中的多个 Task 只能来自 Java 受理的明确要求文件数。

## 持久化影响

这是一份逻辑清单，不是可执行 DDL，也不授权重置数据。Java 现有的十二张表仍由 Java 负责。

| 表 | 拟议影响 |
| --- | --- |
| `generation_plan` | 新增：身份/所有者/Session/revision/状态、类型化配置 JSON、冻结的单文件请求、冻结意向中的文件数及时间戳。使用所有者/Session 复合约束和 revision CAS；不包含已提交 Task 列。 |
| `session` | 新增可空的聚焦计划 ID，以及独立的非负聚焦 revision。必须强制保证相同所有者/Session 的归属关系。 |
| `task` | 新增可空的 `plan_id`，以兼容现有/直接创建的历史 Task；每个由计划工具新建的子 Task 都必须包含该字段，并携带冻结的单文件类型化负载及独立子操作身份。强制保证相同所有者/Session 关系。不添加 Run/Turn/Item 外键。 |
| `http_request` | 复用现有请求身份/哈希/结果存储；在应用边界新增类型化操作信封和限定作用域的查询；原子地保留完整父子回执映射。不新增第二张回执表。 |
| `session_event` | 保留存储；新增类型化 PLAN_UPDATED 和 PLAN_FOCUS_CHANGED 通知变体。 |
| `user`, `agent_run`, `agent_turn`, `asset`, `item`, `run_event`, `outbox_event`, `inbox_event` | 保留这些表。按需扩展具名序列化契约；不重置 schema，也不新增业务调度状态。 |

只要冻结计划仍被保留，其中引用的 Asset 就必须受到保护，不能被未使用上传清理删除。如果 Asset 删除无法保留引用，则需要明确的独立生命周期策略；v1 不得默默删除被引用的输入。生成的和上传的 Asset 仍保留当前的所有权/存储职责。

## 激活前所需的集成变更

- Java：具名计划/目录/领域 DTO 和校验器，包括根据准确的能力 revision 解析必填绑定并持久化冻结请求；功能存储库；业务 revision/聚焦；复用的回执信封及查询；原子的多 Task 入口；限定作用域的前端查询/通知；安全保留被引用的 Asset。
- Python Agent：匹配的具名工具输入/结果；所有计划工具的原始操作身份；类型化回执恢复；批次依赖预检；权威上下文补充；规范的 Item 序列化器/解码器和测试夹具。
- Connector：显式解码规范化变体、根据绑定／判别字段／版本选择已注册适配器并检查兼容性、解析 Asset 定位信息、供应商映射、严格单文件及关闭附属输出的语义，以及输出预期验证。未知绑定会阻塞；恢复过程中，已有的已保存路由及供应商负载保持不可变。现有不可变执行/恢复身份保持不变。
- 同步扩展 Java `ConversationContent`、`EventProtocol`、`ContentValidator` 和 Python 协议/模型观察联合类型。Plan 工具结果（包括历史重放）必须依据其原始父操作回执的成员关系校验，包括每个子 Task/操作 ID，而不是依据最新的可变计划状态。旧版历史工具结果继续使用原解码器及回执校验。
- 将 `submit_generation_plan` 暴露为 Agent 的生成入口，并保留 `get_task`。不得同时将旧的、不透明的 `submit_generation(kind, request)` 暴露为 LLM 绕过入口。按需保留历史解码。
- 与现有生成 v2 信封协调传输激活。其 `request` 变为具有显式判别字段的规范化/旧版联合类型；在生产方发送规范化变体之前，新接收方必须能够理解两种变体。仅支持旧版的 Connector 会拒绝新字段。本设计不会激活当前主题。

当前源码边界：[Java 不透明请求](../../../src/main/java/com/lux/backend/task/ConnectorRequest.java)、[Task 受理](../../../src/main/java/com/lux/backend/task/TaskService.java)、[幂等性存储](../../../src/main/java/com/lux/backend/common/HttpIdempotency.java)、[Asset 事实](../../../src/main/java/com/lux/backend/asset/AssetRecord.java)、[Agent 并行工具](../../../../lux-python-agent/src/lux_agent/runtime.py)、[供应商请求结构](../../../../lux-python-model-connector/src/lux_model_connector/byteplus.py)。

当前 Asset 记录缺少可信的尺寸/时长；Connector 接受供应商可读取的字符串，而不是 Java Asset ID；当前结果保存也不能证明严格满足单文件/尺寸/时长要求。当前 `TaskService` 的回执恢复假设只有一个 Task，`ContentValidator` 假设父调用 Item ID 等于 Task 操作 ID。激活前必须改为恢复完整的原始父回执，并校验子成员关系，而不能继续检查这一等式。当前不透明请求路径也缺少这一显式供应商绑定契约及已固定适配器的兼容性检查。它们是实现本契约的依赖，不是计划 schema 已经提供的检查；本设计不实现这些运行时变更。

## 验收场景

实现验证应保护可观察行为：

1. 不完整的草稿在新的模型调用/对话压缩后保留用户意图，并准确返回缺失字段/默认值来源。
2. 模型变更保留用户要求，原子地清除不兼容的候选值，并阻止有冲突的默认值；兼容值保留其参数来源。
3. 即使尺寸/时长/参考素材中的每个值都独立受支持，不受支持的组合仍会失败。未知能力和缺失的引用事实绝不能被当作无限制而通过。
4. 跨媒体创建保留源计划，报告未迁移的要求，复制要求文件数但不编造帧语义，并原子地更改聚焦。
5. 首次受理原子地冻结计划一次，并创建 N 个 Task/N 条 Outbox 信封/一份父回执。回滚不冻结任何内容，也不创建任何子；重复的原始操作在 CAS 检查前返回原始有序身份。
6. 使用新的父操作 ID 复用一个冻结计划，会按冻结数量创建不同的 Task，它们的单文件规范化请求相同，且计划 revision 不变。更新冻结计划会失败；克隆会产生可编辑草稿，修改数量也遵循此规则。
7. 聚焦和计划 CAS 拒绝过期操作；同一响应中有依赖的更新/提交在调用前被拒绝，避免冻结旧参数。
8. 原始回执查询能够恢复写操作和读观察，而不以当前状态替换它们。跨所有者/Session 的查询不会泄露数据。
9. 可信元数据强制执行 实际 Asset 媒体类型、数量、尺寸及时长预算；解析器 URL/凭据绝不进入计划或 LLM 参数。
10. 要求文件数为 null 时得到一个；明确的多文件要求在平台政策内创建恰好 N 个独立子。超政策数量继续保存为阻塞项。每个子要求恰好一个文件并关闭附属输出；缺少或多出文件都失败，不裁切，也不自动付费补发。
11. Java/Python 规范的 Item/工具以及规范化/旧版 Connector 测试夹具保持一致，包括 false、null、数组、revision 及未知属性拒绝。
12. 冻结计划引用的输入不会被常规未使用上传清理删除。供应商结果不确定时核对原始操作，绝不进行新提交。
13. 首次受理固定准确能力 revision 对应的供应商绑定。修改目录绑定必须递增 revision；冻结复用和恢复保留原始路由／负载。已固定适配器／结果验证器不可用时，使用现有问题码阻塞，而不是重新路由。克隆保留其已固定选择，直到显式变更目标。

所附 JSON Schema/示例只验证结构契约。本地运行时和数据库测试使用合成注册；真实供应商执行、部署启用及供应商结果核验的验收仍需要独立证据。
