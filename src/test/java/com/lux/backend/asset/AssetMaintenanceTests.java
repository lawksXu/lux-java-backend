package com.lux.backend.asset;

import com.lux.backend.task.TaskRepository;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.UUID;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetMaintenanceTests {
    @Test
    void cleanupPreservesRegisteredAssetsAndAnImportersUncommittedObjects() throws Exception {
        AssetRepository assets = mock(AssetRepository.class);
        TaskRepository tasks = mock(TaskRepository.class);
        AssetStore store = mock(AssetStore.class);
        UUID registeredOwner = UUID.randomUUID();
        UUID registeredAsset = UUID.randomUUID();
        UUID importingOwner = UUID.randomUUID();
        when(assets.exists(registeredOwner.toString(), registeredAsset.toString())).thenReturn(true);
        when(tasks.hasImporting(importingOwner.toString())).thenReturn(true);
        doAnswer(call -> {
            BiPredicate<UUID, UUID> retain = call.getArgument(1);
            assertTrue(retain.test(registeredOwner, registeredAsset));
            assertTrue(retain.test(importingOwner, UUID.randomUUID()));
            assertFalse(retain.test(UUID.randomUUID(), UUID.randomUUID()));
            return null;
        }).when(store).cleanupOrphans(eq(Duration.ofDays(2)), any());
        AssetMaintenance maintenance = new AssetMaintenance(store, assets, tasks);

        maintenance.cleanup();

        verify(store).cleanupTemporaryFiles(Duration.ofDays(1));
        verify(store).cleanupOrphans(eq(Duration.ofDays(2)), any());
    }
}
