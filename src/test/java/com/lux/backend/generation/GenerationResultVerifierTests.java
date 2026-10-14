package com.lux.backend.generation;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.lux.backend.generation.GenerationPlanContract.*;

class GenerationResultVerifierTests {
    private final GenerationResultVerifier verifier = new GenerationResultVerifier();
    @Test void videoRequiresExactDimensionsContainerAndExplicitAudioFalse() {
        SizePreset preset = new SizePreset("test", new AspectRatio(16, 9), "test", new PixelDimensions(16, 9));
        ResolvedPresetSize size = new ResolvedPresetSize("resolved_preset_size", preset);
        VideoGenerationRequest request = new VideoGenerationRequest("video_generation_plan_v1", 1,
            new ModelSelection("test", "test", 1L), new ProviderBinding("test", "test", "test"),
            new VideoExecutionParameters("video_execution_parameters", "test", size, java.util.List.of(), VideoOutputFormat.MP4, 4, false, VideoMode.values()[0]),
            new VideoResultExpectations("video_result_expectations", 1, size, VideoOutputFormat.MP4, 4, 100L, false));
        assertDoesNotThrow(() -> verifier.verify(request, video("video/mp4", 16, 4000, false)));
        assertThrows(GenerationResultVerifier.ResultVerificationException.class,
            () -> verifier.verify(request, video("video/mp4", 16, 4000, true)));
        assertThrows(GenerationResultVerifier.ResultVerificationException.class,
            () -> verifier.verify(request, video("video/quicktime", 16, 4000, false)));
        assertThrows(GenerationResultVerifier.ResultVerificationException.class,
            () -> verifier.verify(request, video("video/mp4", 17, 4000, false)));
        assertThrows(GenerationResultVerifier.ResultVerificationException.class,
            () -> verifier.verify(request, video("video/mp4", 16, 4101, false)));
        assertDoesNotThrow(() -> verifier.verify(request, video("video/mp4", 16, 4100, false)));
    }
    @Test void imageChecksDetectedFormatAndDimensionsAndKind() {
        PixelSize size = new PixelSize("pixel_size", new PixelDimensions(16, 9));
        ImageGenerationRequest request = new ImageGenerationRequest("image_generation_plan_v1", 1,
            new ModelSelection("test", "test", 1L), new ProviderBinding("test", "test", "test"),
            new ImageExecutionParameters("image_execution_parameters", "test", size, java.util.List.of(), ImageOutputFormat.PNG, ImageMode.values()[0]),
            new ImageResultExpectations("image_result_expectations", 1, size, ImageOutputFormat.PNG));
        VerifiedImageMetadata png = new VerifiedImageMetadata("verified_image_metadata", UUID.randomUUID(), "hash", 1L,
            "image/png", new PixelDimensions(16, 9));
        assertDoesNotThrow(() -> verifier.verify(request, png));
        assertThrows(GenerationResultVerifier.ResultVerificationException.class,
            () -> verifier.verify(request, new VerifiedImageMetadata("verified_image_metadata", png.assetId(), "hash", 1L,
                "image/jpeg", png.dimensions())));
        assertThrows(GenerationResultVerifier.ResultVerificationException.class,
            () -> verifier.verify(request, video("video/mp4", 16, 4000, false)));
    }
    private static VerifiedVideoMetadata video(String mime, int width, long duration, boolean audio) {
        return new VerifiedVideoMetadata("verified_video_metadata", UUID.randomUUID(), "hash", 1L,
            mime, new PixelDimensions(width, 9), duration, audio);
    }
}
