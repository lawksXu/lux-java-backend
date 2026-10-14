package com.lux.backend.generation;

import static com.lux.backend.generation.GenerationPlanContract.*;

/** 每次模型请求前读取的权威业务观察；不制造 TOOL_CALL 或持久化模型回执。 */
public record GenerationPlanContext(SessionPlanFocus focus, GenerationPlanSnapshot plan, ModelCatalogSnapshot catalog) { }
