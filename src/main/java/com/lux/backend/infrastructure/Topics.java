package com.lux.backend.infrastructure;

/** 新存储协议独立使用 v2 主题；旧生成回调只能经显式兼容适配读取。 */
public final class Topics {
    /** 工具容器仅提供静态能力，禁止创建无意义的实例。 */
    private Topics() { }
    /** Java 发给 Python Agent 的命令主题，按用户与会话分区。 */
    public static final String AGENT_COMMANDS = "lux.agent.commands.v2";
    /** Python Agent 发回 Java 的执行事件主题。 */
    public static final String AGENT_EVENTS = "lux.agent.events.v2";
    /** Java 发给模型连接器的生成和状态查询命令主题，按用户与任务分区。 */
    public static final String GENERATION_COMMANDS = "lux.generation.commands.v2";
    /** 模型连接器发回 Java 的生成状态与结果来源事件主题。 */
    public static final String GENERATION_EVENTS = "lux.generation.events.v2";
    /** 仅接收旧回调，不把新 Session-only 请求伪装成旧 Run 请求。 */
    public static final String LEGACY_GENERATION_EVENTS = "lux.generation.events.v1";

    /** 结果导入与普通执行事实采用不同 ACK/恢复边界。 */
    public static boolean generationEvents(String source) {
        return GENERATION_EVENTS.equals(source) || LEGACY_GENERATION_EVENTS.equals(source);
    }
}
