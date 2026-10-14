package com.lux.backend.chat;

import com.lux.backend.common.JsonCodec;
import com.lux.backend.infrastructure.EventProtocol;
import com.lux.backend.infrastructure.IncomingEvent;
import tools.jackson.databind.JsonNode;

/** 前端通知与 Python 原事实分开，Item 包装携带稳定登记位置以便立即插入时间线。 */
public interface SessionChange {
    interface ItemBody {
    }

    record SessionItemNotification(String itemId, long itemSequenceNo, ItemBody body) implements SessionChange {
    }

    record ItemContentReplaced(IncomingEvent.AgentItemSnapshot snapshot) implements ItemBody {
    }

    record TitleChanged(String title) implements SessionChange {
    }

    record RunChanged(String runId) implements SessionChange {
    }

    record TurnChanged(String turnId) implements SessionChange {
    }

    record TaskChanged(String taskId) implements SessionChange {
    }

    /** 计划变化通知只携带身份/版本；前端通过 scoped query 获取完整最新快照。 */
    record PlanChanged(String planId, long revision) implements SessionChange { }
    record PlanFocusChanged(com.lux.backend.generation.GenerationPlanContract.SessionPlanFocus focus) implements SessionChange { }

    /** 按明确 SSE 类型恢复通知，原始载荷只在反序列化边界读取。 */
    static SessionChange read(JsonCodec json, String type, String payload) {
        if (type.equals("ITEM_CREATED")
            || type.equals("ITEM_TEXT_DELTA")
            || type.equals("ITEM_CONTENT_REPLACED")) {
            JsonNode value = json.read(payload);
            ItemBody body = switch (type) {
                case "ITEM_TEXT_DELTA" -> EventProtocol.delta(value.path("body"));
                case "ITEM_CONTENT_REPLACED" -> new ItemContentReplaced(EventProtocol.itemSnapshot(value.path("body").path("snapshot")));
                default -> EventProtocol.itemProjection(value.path("body"));
            };
            return new SessionItemNotification(EventProtocol.uuid(value, "item_id"), EventProtocol.positive(value,
                "item_sequence_no"), body);
        }
        return switch (type) {
            case "SESSION_UPDATED" -> json.read(payload, TitleChanged.class);
            case "RUN_UPDATED" -> json.read(payload, RunChanged.class);
            case "TURN_UPDATED" -> json.read(payload, TurnChanged.class);
            case "TASK_UPDATED" -> json.read(payload, TaskChanged.class);
            case "PLAN_UPDATED" -> json.read(payload, PlanChanged.class);
            case "PLAN_FOCUS_CHANGED" -> json.read(payload, PlanFocusChanged.class);
            default -> throw new IllegalArgumentException("Unsupported session notification");
        };
    }
}
