package com.lux.backend.generation;

import org.springframework.stereotype.Component;
import static com.lux.backend.generation.GenerationPlanContract.*;

/** 将实际媒体事实与冻结请求逐项比较；文件数量由结果清单边界检查。 */
@Component
public final class GenerationResultVerifier {
    /** false 音频要求同样是约束，必须证明视频没有音轨。 */
    public void verify(NormalizedGenerationRequest request, VerifiedMediaMetadata metadata) {
        if (request instanceof ImageGenerationRequest image) {
            ImageResultExpectations expected = image.resultExpectations();
            if (!(metadata instanceof VerifiedImageMetadata actual)) fail("Expected image output");
            VerifiedImageMetadata actual = (VerifiedImageMetadata) metadata;
            String mime = expected.outputFormat() == ImageOutputFormat.PNG ? "image/png" : "image/jpeg";
            if (!mime.equals(actual.detectedMimeType())) fail("Image encoding differs from requested format");
            if (!dimensions(expected.size()).equals(actual.dimensions())) fail("Image dimensions differ from requested size");
        } else if (request instanceof VideoGenerationRequest video) {
            VideoResultExpectations expected = video.resultExpectations();
            if (!(metadata instanceof VerifiedVideoMetadata)) fail("Expected video output");
            VerifiedVideoMetadata actual = (VerifiedVideoMetadata) metadata;
            String mime = expected.outputFormat() == VideoOutputFormat.MP4 ? "video/mp4" : "video/quicktime";
            if (!mime.equals(actual.detectedMimeType())) fail("Video container differs from requested format");
            if (!expected.size().preset().pixelDimensions().equals(actual.dimensions())) fail("Video dimensions differ from requested size");
            long expectedDuration = Math.multiplyExact(expected.durationSeconds().longValue(), 1000);
            long actualDuration = actual.durationMilliseconds();
            long tolerance = expected.durationToleranceMilliseconds();
            if (actualDuration < 1 || tolerance < 0 || Math.abs(Math.subtractExact(actualDuration, expectedDuration)) > tolerance)
                fail("Video duration outside tolerance");
            if (!expected.expectedAudio().equals(actual.hasAudio())) fail("Video audio track differs from requirement");
        } else fail("Unsupported generation request");
    }
    private static PixelDimensions dimensions(ExecutableSize size) {
        if (size instanceof PixelSize pixels) return pixels.dimensions();
        return ((ResolvedPresetSize) size).preset().pixelDimensions();
    }
    private static void fail(String message) { throw new ResultVerificationException(message); }
    /** 已确认的结果不满足冻结合同，不应重新调用付费提供者。 */
    public static final class ResultVerificationException extends IllegalArgumentException {
        public ResultVerificationException(String message) { super(message); }
    }
}
