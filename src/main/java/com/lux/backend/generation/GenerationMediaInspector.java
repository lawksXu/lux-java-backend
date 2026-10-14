package com.lux.backend.generation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lux.backend.asset.AssetRecord;
import com.lux.backend.asset.AssetStore;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import static com.lux.backend.generation.GenerationPlanContract.*;

/** 从真实资源字节建立可信事实；调用者必须在数据库事务之外执行检查。 */
@Component
public final class GenerationMediaInspector {
    private final AssetStore store;
    private final String command;
    private final long maxBytes;
    private static final int MAX_OUTPUT = 1_048_576;
    private static final long TIMEOUT_SECONDS = 10;
    /** 命令是单个可执行文件路径，不经 shell 展开；字节上限限制暂存和解码。 */
    public GenerationMediaInspector(AssetStore store,
            @Value("${lux.generation.ffprobe-command:ffprobe}") String command,
            @Value("${lux.generation.inspection-max-bytes:268435456}") long maxBytes) {
        if (command == null || command.isBlank() || maxBytes < 1) throw new IllegalArgumentException("Invalid inspection settings");
        this.store = store;
        this.command = command;
        this.maxBytes = maxBytes;
    }
    /** 检查 ffprobe 当前是否可启动；不会据此虚构媒体事实。 */
    public boolean probeAvailable() {
        try { runProbe(List.of(command, "-version"), AssetStore.TransferDeadline.afterMillis(10_000)); return true; }
        catch (IOException exception) { return false; }
    }
    /** 下载后同时校验长度和摘要，再根据签名选择检查器。 */
    public VerifiedMediaMetadata inspect(AssetRecord asset) throws IOException {
        return inspect(asset, AssetStore.TransferDeadline.afterMillis(10_000));
    }
    /** 共享导入回调预算；存储技术失败和中断保持 IOException，不能解释成确认的业务失败。 */
    public VerifiedMediaMetadata inspect(AssetRecord asset, AssetStore.TransferDeadline deadline) throws IOException {
        deadline.check();
        if (asset.sizeBytes() < 1 || asset.sizeBytes() > maxBytes) throw new MediaInspectionException("Asset exceeds inspection byte limit");
        Path temporary = null;
        try {
            // 存储范围流会按声明长度截断，先检查完整对象，防止尾部额外字节被隐藏。
            if (!store.matches(UUID.fromString(asset.userId()), UUID.fromString(asset.id()), asset.sizeBytes(), asset.sha256(), deadline))
                throw new MediaInspectionException("Asset checksum or size mismatch");
            deadline.check();
            temporary = Files.createTempFile("lux-media-", ".bin");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream input = store.open(UUID.fromString(asset.userId()), UUID.fromString(asset.id()), 0, asset.sizeBytes());
                 java.io.OutputStream output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                int read;
                while (true) {
                    deadline.check();
                    read = input.read(buffer);
                    if (read == -1) break;
                    deadline.check();
                    total += read;
                    if (total > maxBytes || total > asset.sizeBytes()) throw new MediaInspectionException("Asset size mismatch");
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            if (total != asset.sizeBytes() || !HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(asset.sha256()))
                throw new MediaInspectionException("Asset checksum or size mismatch");
            byte[] prefix;
            try (InputStream input = Files.newInputStream(temporary)) { prefix = input.readNBytes(64); }
            deadline.check();
            String mime = signature(prefix);
            UUID id = UUID.fromString(asset.id());
            if (mime.equals("image/png") || mime.equals("image/jpeg")) {
                try (ImageInputStream input = ImageIO.createImageInputStream(temporary.toFile())) {
                    Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
                    if (!readers.hasNext()) throw new MediaInspectionException("Image decoder unavailable");
                    ImageReader reader = readers.next();
                    try {
                        reader.setInput(input, true, true);
                        PixelDimensions size = dimensions(reader.getWidth(0), reader.getHeight(0));
                        // 解码预算按像素展开后的大小限制，防止小文件声明极大图像。
                        if (size.width() > 16384 || size.height() > 16384 || (long) size.width() * size.height() > maxBytes / 4) throw new MediaInspectionException("Decoded image exceeds inspection limit");
                        deadline.check();
                        if (reader.read(0) == null) throw new MediaInspectionException("Cannot decode image pixels");
                        deadline.check();
                        return new VerifiedImageMetadata("verified_image_metadata", id, asset.sha256(), total, mime, size);
                    } catch (AssetStore.TransferInterruptedException exception) { throw exception;
                    } catch (MediaInspectionException exception) { throw exception;
                    } catch (IOException exception) { throw new MediaInspectionException("Invalid image encoding", exception);
                    } finally { reader.dispose(); }
                }
            }
            ProbeOutput probe = JsonMapper.builder().build().readValue(runProbe(List.of(command, "-v", "error", "-protocol_whitelist", "file", "-show_entries",
                "stream=codec_type,width,height,duration:format=format_name,duration", "-of", "json", temporary.toString()), deadline), ProbeOutput.class);
            if (probe.streams() == null || probe.format() == null || probe.format().formatName() == null)
                throw new MediaInspectionException("Incomplete probe metadata");
            String formats = probe.format().formatName();
            String expectedContainer = switch (mime) {
                case "audio/wav" -> "wav";
                case "audio/flac" -> "flac";
                case "audio/mpeg" -> "mp3";
                default -> "mov";
            };
            if (!List.of(formats.split(",")).contains(expectedContainer))
                throw new MediaInspectionException("Container signature and probe disagree");
            ProbeStream video = null;
            boolean audio = false;
            for (ProbeStream stream : probe.streams()) {
                if ("video".equals(stream.codecType())) {
                    if (video != null) throw new MediaInspectionException("Multiple video tracks unsupported");
                    video = stream;
                }
                if ("audio".equals(stream.codecType())) audio = true;
            }
            long duration = duration(probe.format().duration());
            if (video != null) {
                if (!mime.startsWith("video/")) throw new MediaInspectionException("Unexpected video track");
                return new VerifiedVideoMetadata("verified_video_metadata", id, asset.sha256(), total, mime,
                    dimensions(video.width(), video.height()), duration, audio);
            }
            if (!audio) throw new MediaInspectionException("No media tracks");
            String audioMime = mime.equals("video/mp4") ? "audio/mp4" : mime;
            if (!audioMime.startsWith("audio/")) throw new MediaInspectionException("Unexpected audio container");
            return new VerifiedAudioMetadata("verified_audio_metadata", id, asset.sha256(), total, audioMime, duration);
        } catch (MediaInspectionException exception) { throw exception;
        } catch (NoSuchAlgorithmException | RuntimeException exception) {
            throw new MediaInspectionException("Cannot inspect asset", exception);
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException exception) { /* 暂存删除失败不改变已经验证的事实。 */ }
        }
    }
    /** 有界读取进程输出；独立读线程避免输出管道阻塞导致时限失效。 */
    private String runProbe(List<String> arguments, AssetStore.TransferDeadline budget) throws IOException {
        budget.check();
        long timeoutNanos = Math.min(budget.remaining().toNanos(), TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS));
        long deadline = System.nanoTime() + timeoutNanos;
        Process process;
        try { process = new ProcessBuilder(arguments).redirectErrorStream(true).start(); }
        catch (IOException exception) { throw new InspectorUnavailableException("ffprobe unavailable", exception); }
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<byte[]> output = executor.submit(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] bytes = input.readNBytes(MAX_OUTPUT + 1);
                if (bytes.length > MAX_OUTPUT) throw new IOException("Probe output exceeds limit");
                return bytes;
            }
        });
        try {
            if (!process.waitFor(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) throw new AssetStore.TransferInterruptedException("Probe budget exhausted");
            budget.check();
            byte[] bytes = output.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (process.exitValue() != 0) throw new MediaInspectionException("Probe rejected media");
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssetStore.TransferInterruptedException("Probe interrupted");
        } catch (java.util.concurrent.TimeoutException exception) {
            throw new AssetStore.TransferInterruptedException("Probe output budget exhausted");
        } catch (java.util.concurrent.ExecutionException exception) {
            throw new MediaInspectionException("Cannot read probe output", exception);
        } finally {
            // 某些受限运行时禁止枚举进程；父进程清理仍必须执行。
            try { process.descendants().forEach(ProcessHandle::destroyForcibly); }
            catch (RuntimeException exception) { /* 平台无法枚举子进程。 */ }
            process.destroyForcibly();
            output.cancel(true);
            executor.shutdownNow();
        }
    }
    /** 只认已知容器签名，不使用文件名或提供者声明的 MIME。 */
    private static String signature(byte[] bytes) throws MediaInspectionException {
        if (bytes.length >= 8 && bytes[0] == (byte) 137 && new String(bytes, 1, 7, StandardCharsets.ISO_8859_1).equals("PNG\r\n\u001a\n")) return "image/png";
        if (bytes.length >= 3 && bytes[0] == (byte) 255 && bytes[1] == (byte) 216 && bytes[2] == (byte) 255) return "image/jpeg";
        if (bytes.length >= 12 && ascii(bytes, 4, 4).equals("ftyp")) {
            String brand = ascii(bytes, 8, 4);
            if (brand.equals("qt  ")) return "video/quicktime";
            if (List.of("isom", "iso2", "mp41", "mp42", "avc1", "M4V ", "M4A ").contains(brand)) return "video/mp4";
        }
        if (bytes.length >= 12 && ascii(bytes, 0, 4).equals("RIFF") && ascii(bytes, 8, 4).equals("WAVE")) return "audio/wav";
        if (bytes.length >= 4 && ascii(bytes, 0, 4).equals("fLaC")) return "audio/flac";
        if (bytes.length >= 3 && ascii(bytes, 0, 3).equals("ID3")) return "audio/mpeg";
        throw new MediaInspectionException("Unsupported or unknown media signature");
    }
    private static String ascii(byte[] bytes, int offset, int length) { return new String(bytes, offset, length, StandardCharsets.US_ASCII); }
    private static PixelDimensions dimensions(Integer width, Integer height) throws MediaInspectionException {
        if (width == null || height == null || width < 1 || height < 1) throw new MediaInspectionException("Invalid media dimensions");
        return new PixelDimensions(width, height);
    }
    private static long duration(String seconds) throws MediaInspectionException {
        try {
            long value = new BigDecimal(seconds).multiply(BigDecimal.valueOf(1000)).setScale(0, RoundingMode.HALF_UP).longValueExact();
            if (value < 1) throw new IllegalArgumentException("Nonpositive duration");
            return value;
        } catch (RuntimeException exception) { throw new MediaInspectionException("Invalid media duration", exception); }
    }
    /** ffprobe 输出仅解码需要的字段；未知扩展字段由适配边界忽略。 */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record ProbeOutput(List<ProbeStream> streams, ProbeFormat format) { }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record ProbeStream(@JsonProperty("codec_type") String codecType, Integer width, Integer height, String duration) { }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record ProbeFormat(@JsonProperty("format_name") String formatName, String duration) { }
    /** 无法建立事实时由业务边界转换为阻塞或确认的导入失败。 */
    public static class MediaInspectionException extends IOException {
        public MediaInspectionException(String message) { super(message); }
        public MediaInspectionException(String message, Throwable cause) { super(message, cause); }
    }
    /** 检查器缺失与媒体损坏分开，便于提交前就阻止不可验证的执行。 */
    public static final class InspectorUnavailableException extends MediaInspectionException {
        public InspectorUnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}
