package com.lux.backend.generation;

import com.lux.backend.asset.AssetRecord;
import com.lux.backend.asset.AssetStore;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.lux.backend.asset.LocalAssetStore;
import static com.lux.backend.generation.GenerationPlanContract.*;

class GenerationMediaInspectorTests {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path root;
    @Test void detectsRealPngAndJpegRatherThanClaimedMime() throws Exception {
        for (String format : new String[]{"png", "jpeg"}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(9, 7, BufferedImage.TYPE_INT_RGB), format, bytes);
            byte[] content = bytes.toByteArray();
            AssetRecord asset = asset(content);
            AssetStore store = stored(asset, content);
            VerifiedImageMetadata metadata = (VerifiedImageMetadata) new GenerationMediaInspector(store, "missing-probe", 1024).inspect(asset);
            assertEquals("image/" + format, metadata.detectedMimeType());
            assertEquals(new PixelDimensions(9, 7), metadata.dimensions());
            assertEquals(UUID.fromString(asset.id()), metadata.assetId());
        }
    }
    @Test void refusesChecksumMismatch() throws Exception {
        byte[] content = new byte[]{1, 2, 3};
        AssetRecord asset = asset(content);
        AssetStore store = stored(asset, new byte[]{3, 2, 1});
        assertThrows(GenerationMediaInspector.MediaInspectionException.class,
            () -> new GenerationMediaInspector(store, "missing-probe", 1024).inspect(asset));
    }
    @Test void controlledProbeConfirmsVideoAudioAndRejectsMalformedOutput() throws Exception {
        byte[] content = new byte[]{0,0,0,24,102,116,121,112,105,115,111,109,0,0,0,0};
        java.nio.file.Path executable = java.nio.file.Files.createTempFile("lux-test-probe", ".sh");
        try {
            java.nio.file.Files.writeString(executable, "#!/bin/sh\nprintf '%s' '{\"streams\":[{\"codec_type\":\"video\",\"width\":16,\"height\":9},{\"codec_type\":\"audio\"}],\"format\":{\"format_name\":\"mov,mp4\",\"duration\":\"4.001\"}}'\n");
            assertTrue(executable.toFile().setExecutable(true));
            AssetRecord asset = asset(content);
            AssetStore store = stored(asset, content);
            GenerationMediaInspector inspector = new GenerationMediaInspector(store, executable.toString(), 1024);
            VerifiedVideoMetadata result = (VerifiedVideoMetadata) inspector.inspect(asset);
            assertEquals(4001L, result.durationMilliseconds());
            assertTrue(result.hasAudio());
            assertEquals("video/mp4", result.detectedMimeType());
            java.nio.file.Files.writeString(executable, "#!/bin/sh\nprintf '%s' '{\"streams\":[{\"codec_type\":\"video\",\"width\":16,\"height\":9}],\"format\":{\"format_name\":\"wav\",\"duration\":\"4\"}}'\n");
            assertThrows(GenerationMediaInspector.MediaInspectionException.class, () -> inspector.inspect(asset));
            java.nio.file.Files.writeString(executable, "#!/bin/sh\nprintf '%s' '{}'\n");
            assertThrows(GenerationMediaInspector.MediaInspectionException.class, () -> inspector.inspect(asset));
        } finally { java.nio.file.Files.deleteIfExists(executable); }
    }
    @Test void unavailableProbeIsExplicitAndUnknownBytesAreRejected() throws Exception {
        byte[] content = new byte[]{0,0,0,24,102,116,121,112,105,115,111,109};
        AssetRecord asset = asset(content);
        AssetStore store = stored(asset, content);
        assertThrows(GenerationMediaInspector.InspectorUnavailableException.class,
            () -> new GenerationMediaInspector(store, "/missing/lux-ffprobe", 1024).inspect(asset));
    }
    @Test void expiredCallbackBudgetRemainsTechnicalFailure() throws Exception {
        byte[] content = new byte[]{1, 2, 3};
        AssetRecord asset = asset(content);
        GenerationMediaInspector inspector = new GenerationMediaInspector(stored(asset, content), "missing", 1024);
        assertThrows(AssetStore.TransferInterruptedException.class,
            () -> inspector.inspect(asset, new AssetStore.TransferDeadline(System.nanoTime() - 1)));
    }
    @Test void storageReadFailureRemainsTechnicalAndInterruptFlagIsRetained() throws Exception {
        byte[] content = new byte[]{1, 2, 3};
        AssetRecord asset = asset(content);
        GenerationMediaInspector inspector = new GenerationMediaInspector(new LocalAssetStore(root.toString()) {
            @Override public boolean matches(UUID user, UUID id, long size, String hash, AssetStore.TransferDeadline deadline)
                    throws java.io.IOException { throw new java.io.IOException("Storage unavailable"); }
        }, "missing", 1024);
        java.io.IOException failure = assertThrows(java.io.IOException.class, () -> inspector.inspect(asset));
        assertFalse(failure instanceof GenerationMediaInspector.MediaInspectionException);
        Thread.currentThread().interrupt();
        try {
            assertThrows(AssetStore.TransferInterruptedException.class, () -> inspector.inspect(asset));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
    @Test void oversizedImageHeaderIsRejectedBeforePixelDecode() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", bytes);
        byte[] content = bytes.toByteArray();
        java.nio.ByteBuffer.wrap(content).putInt(16, 20000);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(content, 12, 17);
        java.nio.ByteBuffer.wrap(content).putInt(29, (int) crc.getValue());
        AssetRecord asset = asset(content);
        GenerationMediaInspector inspector = new GenerationMediaInspector(stored(asset, content), "missing", 268435456);
        GenerationMediaInspector.MediaInspectionException failure = assertThrows(
            GenerationMediaInspector.MediaInspectionException.class, () -> inspector.inspect(asset));
        assertTrue(failure.getMessage().contains("Decoded image"));
    }
    private AssetStore stored(AssetRecord asset, byte[] content) throws Exception {
        AssetStore store = new LocalAssetStore(root.toString());
        store.write(UUID.fromString(asset.userId()), UUID.fromString(asset.id()), new ByteArrayInputStream(content), 1024);
        return store;
    }
    static AssetRecord asset(byte[] content) throws Exception {
        return new AssetRecord(UUID.randomUUID().toString(), UUID.randomUUID().toString(), null,
            "untrusted", "application/octet-stream", content.length,
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)), Instant.now());
    }
}
