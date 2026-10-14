package com.lux.backend.task;

import com.lux.backend.asset.AssetRecord;
import com.lux.backend.asset.AssetRepository;
import com.lux.backend.asset.AssetStore;
import com.lux.backend.asset.AssetStore.TransferDeadline;
import com.lux.backend.common.Timestamps;
import com.lux.backend.infrastructure.IncomingEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.lux.backend.generation.GenerationMediaInspector;
import com.lux.backend.generation.GenerationResultVerifier;
import com.lux.backend.generation.GenerationPlanContract.VerifiedMediaMetadata;

/** Kafka 接收 worker 同步导入；数据库事务只包状态/元数据/终态，不包含外部 I/O。 */
@Service
public class TaskResultService {
    /** 将确认的导入结果和 Inbox 标记合并到短事务。 */
    private final TaskService tasks;
    /** 查找已验证的部分成果，以原 Asset 身份恢复技术中断。 */
    private final AssetRepository assets;
    /** 不可变字节发布和实际摘要校验边界。 */
    private final AssetStore store;
    /** 只覆盖接收、元数据和终态；不会在下载时保留事务。 */
    private final TransactionTemplate transactions;
    /** 每个文件的长度上限，防止无界来源流。 */
    private final long maxBytes;
    /** 整次 Kafka 回调共享的单调预算，不是每个文件重新获得的时限。 */
    private final long importTimeoutMillis;
    /** 新规范化结果检查实际内容；旧供应商路径仍沿用原导入语义。 */
    private final GenerationMediaInspector inspector;
    private final GenerationResultVerifier resultVerifier;

    public TaskResultService(TaskService tasks, AssetRepository assets, AssetStore store,
                             TransactionTemplate transactions,
                             @Value("${lux.storage.max-bytes:104857600}") long maxBytes,
                             @Value("${lux.task.import-timeout-ms:240000}") long importTimeoutMillis) {
        this(tasks, assets, store, transactions, maxBytes, importTimeoutMillis,
            new GenerationMediaInspector(store, "ffprobe", maxBytes), new GenerationResultVerifier());
    }

    /** 保留旧构造契约，应用 wiring 使用具名检查组件及其明确配置。 */
    @org.springframework.beans.factory.annotation.Autowired
    public TaskResultService(TaskService tasks, AssetRepository assets, AssetStore store,
                             TransactionTemplate transactions,
                             @Value("${lux.storage.max-bytes:104857600}") long maxBytes,
                             @Value("${lux.task.import-timeout-ms:240000}") long importTimeoutMillis,
                             GenerationMediaInspector inspector, GenerationResultVerifier resultVerifier) {
        if (maxBytes < 1 || importTimeoutMillis < 1) throw new IllegalArgumentException("Import limits must be positive");
        this.tasks = tasks;
        this.assets = assets;
        this.store = store;
        this.transactions = transactions;
        this.maxBytes = maxBytes;
        this.importTimeoutMillis = importTimeoutMillis;
        this.inspector = inspector;
        this.resultVerifier = resultVerifier;
    }

    /** markApplied 只在短事务里调用；方法成功返回才允许 listener 确认消费。 */
    public void receive(IncomingEvent.Generation event, Runnable markApplied) {
        TransferDeadline deadline = TransferDeadline.afterMillis(importTimeoutMillis);
        TaskService.ImportPlan plan = transactions.execute(status -> {
            check(deadline);
            return tasks.admitGeneration(event, markApplied);
        });
        if (plan == null) return;
        try {
            List<AssetRecord> verified = importSources(plan, deadline);
            transactions.executeWithoutResult(status -> {
                check(deadline);
                tasks.completeImport(plan, verified);
                markApplied.run();
            });
        } catch (ConfirmedImportFailure failure) {
            persistFailure(plan, failure.code(), failure.getMessage(), markApplied, deadline);
        } catch (AssetStore.AssetTooLargeException failure) {
            persistFailure(plan, "IMPORT_SOURCE_SIZE", failure.getMessage(), markApplied, deadline);
        } catch (AssetStore.AssetConflictException failure) {
            persistFailure(plan, "IMPORT_ASSET_CONFLICT", failure.getMessage(), markApplied, deadline);
        } catch (IOException technical) {
            // 存储请求可能已经发布对象；原身份核对后才能继续，不能盲判 Task FAILED。
            throw new IllegalStateException("Result import requires technical recovery", technical);
        }
    }

    private void persistFailure(TaskService.ImportPlan plan, String code, String message, Runnable applied,
                                TransferDeadline deadline) {
        transactions.executeWithoutResult(status -> {
            check(deadline);
            tasks.failImport(plan, code, message);
            applied.run();
        });
    }

    private List<AssetRecord> importSources(TaskService.ImportPlan plan, TransferDeadline deadline) throws IOException {
        TaskRecord task = plan.task();
        List<AssetRecord> verified = new ArrayList<>(plan.sources().size());
        UUID owner = UUID.fromString(task.userId());
        for (int index = 0; index < plan.sources().size(); index++) {
            deadline.check();
            String assetId = sourceId(task.id(), index);
            UUID identity = UUID.fromString(assetId);
            Optional<AssetRecord> persisted = assets.find(task.userId(), assetId);
            if (persisted.isPresent()) {
                AssetRecord original = persisted.get();
                if (!task.id().equals(original.taskId()) || !store.matches(owner, identity,
                        original.sizeBytes(), original.sha256(), deadline)) {
                    throw new ConfirmedImportFailure("IMPORT_ASSET_CONFLICT", "Existing asset no longer matches verified metadata");
                }
                verified.add(original);
                verifyGeneratedAsset(task, original, deadline);
                continue;
            }
            ResultSource source = plan.sources().get(index);
            AssetStore.StoredFile stored = download(owner, identity, source, deadline);
            AssetRecord proposed = new AssetRecord(assetId, task.userId(), task.id(), source.filename(), source.mediaType(),
                    stored.sizeBytes(), stored.sha256(), Timestamps.now());
            int sourceIndex = index;
            AssetRecord accepted = transactions.execute(status -> {
                check(deadline);
                return tasks.registerImportAsset(plan, proposed, sourceIndex);
            });
            // 另一个 consumer 已提交同一 Task 终态，立即停止新增 I/O，最终事务只补 APPLIED。
            if (accepted == null) return List.of();
            verifyGeneratedAsset(task, accepted, deadline);
            verified.add(accepted);
        }
        for (AssetRecord asset : verified) {
            deadline.check();
            if (!store.matches(owner, UUID.fromString(asset.id()), asset.sizeBytes(), asset.sha256(), deadline)) {
                throw new ConfirmedImportFailure("IMPORT_ASSET_CONFLICT", "Imported asset no longer matches its metadata");
            }
        }
        return verified;
    }

    /** 内容检查不占用数据库事务；技术 I/O 中断保持原 Task 的恢复资格。 */
    private void verifyGeneratedAsset(TaskRecord task, AssetRecord asset, TransferDeadline deadline) throws IOException {
        if (task.payload().normalized() == null) return;
        try {
            VerifiedMediaMetadata metadata = inspector.inspect(asset, deadline);
            resultVerifier.verify(task.payload().normalized(), metadata);
            if (!asset.mediaType().equals(metadata.detectedMimeType())) {
                throw new GenerationResultVerifier.ResultVerificationException("Declared MIME differs from detected output MIME");
            }
            assets.saveInspection(task.userId(), asset.id(), asset.sha256(), metadata);
        } catch (GenerationMediaInspector.InspectorUnavailableException unavailable) {
            // 缺少检查工具不能证明结果违反要求，保留原导入身份等待技术恢复。
            throw new IOException("Generation result inspector is unavailable", unavailable);
        } catch (GenerationMediaInspector.MediaInspectionException | GenerationResultVerifier.ResultVerificationException failure) {
            throw new ConfirmedImportFailure("OUTPUT_VERIFICATION_FAILED", failure.getMessage());
        }
    }

    private AssetStore.StoredFile download(UUID owner, UUID asset, ResultSource source, TransferDeadline deadline)
            throws IOException {
        deadline.check();
        HttpURLConnection connection = (HttpURLConnection) URI.create(source.url()).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        int timeout = (int) Math.max(1, Math.min(10_000, deadline.remaining().toMillis()));
        connection.setConnectTimeout(timeout);
        connection.setReadTimeout(timeout);
        long sourceDeadline = System.nanoTime() + 60_000_000_000L;
        try {
            InputStream response;
            try {
                int status = connection.getResponseCode();
                if (status != 200) throw new ConfirmedImportFailure("IMPORT_SOURCE_HTTP", "Result source returned HTTP " + status);
                response = connection.getInputStream();
            } catch (ConfirmedImportFailure confirmed) {
                throw confirmed;
            } catch (IOException failure) {
                deadline.check();
                throw new ConfirmedImportFailure("IMPORT_SOURCE_IO", failure.getMessage());
            }
            try (InputStream body = new ValidatedInputStream(response, sourceDeadline,
                    connection.getContentLengthLong(), deadline)) {
                return store.write(owner, asset, body, maxBytes, deadline);
            }
        } finally {
            connection.disconnect();
        }
    }

    /** Task 与固定来源位置生成稳定身份，重复下载不会形成新 Asset。 */
    static String sourceId(String taskId, int index) {
        return UUID.nameUUIDFromBytes((taskId + ":" + index).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static void check(TransferDeadline deadline) {
        try { deadline.check(); }
        catch (AssetStore.TransferInterruptedException failure) { throw new IllegalStateException(failure.getMessage(), failure); }
    }

    /** 来源传输失败与对象发布失败分开分类，保留技术中断的 Kafka 恢复资格。 */
    static final class ConfirmedImportFailure extends IOException {
        private final String code;
        ConfirmedImportFailure(String code, String message) { super(message); this.code = code; }
        String code() { return code; }
    }

    private static final class ValidatedInputStream extends FilterInputStream {
        private final long sourceDeadline;
        private final long expectedLength;
        private final TransferDeadline deadline;
        private long count;
        ValidatedInputStream(InputStream input, long sourceDeadline, long expectedLength, TransferDeadline deadline) {
            super(input); this.sourceDeadline = sourceDeadline; this.expectedLength = expectedLength; this.deadline = deadline;
        }
        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read < 0 ? -1 : one[0] & 0xff;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            deadline.check();
            if (System.nanoTime() - sourceDeadline >= 0) throw new ConfirmedImportFailure("IMPORT_SOURCE_TIMEOUT", "Result source download timed out");
            int read;
            try { read = in.read(buffer, offset, length); }
            catch (IOException failure) {
                deadline.check();
                throw new ConfirmedImportFailure("IMPORT_SOURCE_IO", failure.getMessage());
            }
            deadline.check();
            if (read >= 0) count += read;
            if (read < 0 && count == 0) throw new ConfirmedImportFailure("IMPORT_SOURCE_EMPTY", "Result source is empty");
            if (expectedLength >= 0 && (count > expectedLength || read < 0 && count != expectedLength)) {
                throw new ConfirmedImportFailure("IMPORT_SOURCE_TRUNCATED", "Result source length differs from Content-Length");
            }
            return read;
        }
    }
}
