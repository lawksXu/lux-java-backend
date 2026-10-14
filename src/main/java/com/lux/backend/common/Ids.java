package com.lux.backend.common;

import java.util.UUID;

/** 统一生成、校验和规范化 UUID，避免同一身份因不同字符串表示而无法匹配。 */
public final class Ids {
    /** 工具容器仅提供静态能力，禁止创建无意义的实例。 */
    private Ids() { }

    /** 生成标准小写、带连字符的随机 UUID 字符串。 */
    public static String next() { return UUID.randomUUID().toString(); }

    /** 要求完整 UUID 格式并返回标准小写表示，拒绝 Java UUID 解析器可能接受的缩写形式。 */
    public static String require(String value) {
        if (value == null || !UUID.fromString(value).toString().equalsIgnoreCase(value)) {
            throw ApiException.badRequest("A canonical UUID is required");
        }
        return UUID.fromString(value).toString();
    }
}
