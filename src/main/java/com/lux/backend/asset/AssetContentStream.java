package com.lux.backend.asset;

import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** 限制读取长度的输入流；底层文件提前结束时抛出异常，避免把截断内容当作完整下载。 */
final class AssetContentStream extends FilterInputStream {
    /** 本次响应还允许读取的字节数，防止越过 Range 或声明长度。 */
    private long remaining;

    /** 包装底层输入流，并以目标响应长度初始化读取预算。 */
    AssetContentStream(InputStream input, long length) {
        super(input);
        remaining = length;
    }

    /** 读取声明范围内的内容并扣减剩余字节；若底层提前结束则报错。 */
    @Override public int read() throws IOException {
        if (remaining == 0) return -1;
        int value = in.read();
        if (value < 0) throw new EOFException("Asset content ended before its declared length");
        remaining--;
        return value;
    }

    /** 读取声明范围内的内容并扣减剩余字节；若底层提前结束则报错。 */
    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) return 0;
        if (remaining == 0) return -1;
        int read = in.read(bytes, offset, (int) Math.min(length, remaining));
        if (read < 0) throw new EOFException("Asset content ended before its declared length");
        remaining -= read;
        return read;
    }

    /** 最多跳过剩余范围内的字节，同时保持后续读取计数一致。 */
    @Override public long skip(long count) throws IOException {
        long skipped = in.skip(Math.max(0, Math.min(count, remaining)));
        remaining -= skipped;
        return skipped;
    }

    /** 禁用标记能力，避免回读后剩余字节计数失真。 */
    @Override public boolean markSupported() { return false; }
    /** 明确拒绝重置流位置，防止绕过已声明的内容长度。 */
    @Override public void reset() throws IOException { throw new IOException("Asset streams cannot be reset"); }
}
