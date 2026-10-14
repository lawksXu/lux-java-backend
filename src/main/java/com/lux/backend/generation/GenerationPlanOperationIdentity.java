package com.lux.backend.generation;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;
import com.lux.backend.generation.GenerationPlanContract.JavaPlanToolInvocation;

/** 原操作摘要包含 trusted owner 和完整原始调用；不使用归一化后的业务配置。 */
public record GenerationPlanOperationIdentity(@JsonProperty("user_id") UUID userId, JavaPlanToolInvocation invocation) { }
