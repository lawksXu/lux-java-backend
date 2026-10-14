package com.lux.backend.common;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** 数据库时间工具，使应用生成的时间精度与 MySQL DATETIME(6) 保持一致。 */
public final class Timestamps {
    /** 工具容器仅提供静态能力，禁止创建无意义的实例。 */
    private Timestamps() { }

    /** 返回截断到微秒的当前时间，使即时响应与数据库再次读取的快照精度一致。 */
    public static Instant now() {
        // MySQL DATETIME(6) 只能保存微秒；预先截断，避免即时 DTO 与再次查询的时间因纳秒丢失而不相等。
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
