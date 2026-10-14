package com.lux.backend.asset;

import com.lux.backend.common.ApiException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.util.MimeTypeUtils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 把已通过归属检查的资源转换为 HTTP 文件响应，统一处理 HEAD、完整下载和 Range 分段读取。
 * 只负责响应协议；文件字节由 AssetStore 提供。
 */
final class AssetContentResponse {
    /** 资源字节存储接口，隔离业务元数据与实际文件传输。 */
    private final AssetStore store;

    /** 通过构造器集中接收所需依赖，使组件职责与协作对象明确可见。 */
    AssetContentResponse(AssetStore store) {
        this.store = store;
    }

    /** 根据请求方法和 Range 头输出资源；只有分段响应返回 206，无效范围返回 416。 */
    void write(AssetRecord asset, HttpServletRequest request, HttpServletResponse response) throws IOException {
        UUID owner = UUID.fromString(asset.userId());
        UUID id = UUID.fromString(asset.id());
        // HEAD 只验证内容存在并返回元数据，不打开响应体，也不按 Range 截取内容。
        if ("HEAD".equals(request.getMethod())) {
            requireContent(owner, id);
            headers(asset, response);
            response.setContentLengthLong(asset.sizeBytes());
            return;
        }

        List<ByteRange> ranges;
        try {
            ranges = ranges(request.getHeader(HttpHeaders.RANGE), asset.sizeBytes());
        } catch (IllegalArgumentException exception) {
            requireContent(owner, id);
            headers(asset, response);
            response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + asset.sizeBytes());
            response.setContentLengthLong(0);
            return;
        }

        if (ranges.size() <= 1) {
            ByteRange range = ranges.isEmpty() ? new ByteRange(0, asset.sizeBytes()) : ranges.get(0);
            try (InputStream input = open(owner, id, range)) {
                headers(asset, response);
                if (!ranges.isEmpty()) {
                    response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
                    response.setHeader(HttpHeaders.CONTENT_RANGE, range.header(asset.sizeBytes()));
                }
                response.setContentLengthLong(range.length());
                input.transferTo(response.getOutputStream());
            }
            return;
        }

        // 多个区间以 multipart/byteranges 分别传输，每段都有自己的 Content-Range；不拼成整文件再切片。
        String boundary = MimeTypeUtils.generateMultipartBoundaryString();
        for (int index = 0; index < ranges.size(); index++) {
            ByteRange range = ranges.get(index);
            try (InputStream input = open(owner, id, range)) {
                if (index == 0) {
                    headers(asset, response);
                    response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
                    response.setContentType("multipart/byteranges; boundary=" + boundary);
                }
                ServletOutputStream output = response.getOutputStream();
                String partHeader = "\r\n--" + boundary + "\r\nContent-Type: " + asset.mediaType()
                        + "\r\nContent-Range: " + range.header(asset.sizeBytes()) + "\r\n\r\n";
                output.write(partHeader.getBytes(StandardCharsets.US_ASCII));
                input.transferTo(output);
            }
        }
        response.getOutputStream().write(("\r\n--" + boundary + "--").getBytes(StandardCharsets.US_ASCII));
    }

    /** 打开指定区间，并将存储层的文件缺失转换为业务 404。 */
    private InputStream open(UUID owner, UUID id, ByteRange range) throws IOException {
        try {
            return store.open(owner, id, range.offset(), range.length());
        } catch (FileNotFoundException exception) {
            throw ApiException.notFound();
        }
    }

    /** 确认底层内容存在，避免只有元数据时仍返回成功的 HEAD 或范围错误响应。 */
    private void requireContent(UUID owner, UUID id) throws IOException {
        if (!store.exists(owner, id)) {
            throw ApiException.notFound();
        }
    }

    /** 设置私有下载的响应头，禁用缓存和 MIME 嗅探，并声明支持字节范围。 */
    private static void headers(AssetRecord asset, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(asset.filename(), StandardCharsets.UTF_8).build().toString());
        response.setContentType(asset.mediaType());
    }

    /** 将 HTTP Range 转为起点与长度，检查越界和多区间总量，限制重复传输放大。 */
    private static List<ByteRange> ranges(String header, long size) {
        if (header == null) {
            return List.of();
        }
        List<HttpRange> requested = HttpRange.parseRanges(header);
        if (requested.isEmpty()) {
            throw new IllegalArgumentException("A byte range is required");
        }
        List<ByteRange> ranges = new ArrayList<>(requested.size());
        long total = 0;
        for (HttpRange range : requested) {
            long start = range.getRangeStart(size);
            long end = range.getRangeEnd(size);
            if (start >= size || end < start) {
                throw new IllegalArgumentException("Unsatisfiable byte range");
            }
            long length = end - start + 1;
            // 多区间总长度必须小于完整资源；用减法比较避免累加时发生 long 溢出。
            if (requested.size() > 1 && total >= size - length) {
                throw new IllegalArgumentException("Combined byte ranges must be smaller than the asset");
            }
            total += length;
            ranges.add(new ByteRange(start, length));
        }
        return ranges;
    }

    /**
     * 内部字节区间：用起点和长度表示范围，避免在各分支反复换算末尾位置。
     * @param offset 区间起始字节位置，从 0 开始。
     * @param length 区间字节数，末尾位置为 offset + length - 1。
     */
    private record ByteRange(long offset, long length) {
        /** 生成 Content-Range 值；HTTP 使用包含末尾字节的闭区间。 */
        String header(long size) {
            return "bytes " + offset + "-" + (offset + length - 1) + "/" + size;
        }
    }
}
