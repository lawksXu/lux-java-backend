package com.lux.backend.generation;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import static com.lux.backend.generation.GenerationPlanContract.*;

/** 执行与结果核验边界的显式注册；生产默认关闭，需要部署方启用完整的媒体执行链路。 */
@Component
public final class GenerationExecutionSupport {
    /** 精确媒体、模型版本、配置档及请求模式版本的注册事实。 */
    public record Route(MediaType mediaType, ModelSelection selection, ProviderBinding binding,
                        int requestSchemaVersion, boolean executionAvailable, boolean resultVerifierAvailable) {
        public Route {
            java.util.Objects.requireNonNull(mediaType); java.util.Objects.requireNonNull(selection);
            java.util.Objects.requireNonNull(binding);
            if (requestSchemaVersion != 1) throw new IllegalArgumentException("Unsupported request schema version");
        }
    }
    private final List<Route> routes;
    public GenerationExecutionSupport() { this(List.of()); }
    /** 与能力目录共用启用开关，防止目录可提交而执行路由缺失。 */
    @Autowired
    public GenerationExecutionSupport(@Value("${lux.generation.byteplus.enabled:false}") boolean enabled) {
        this(BytePlusGenerationCatalog.routes(enabled));
    }
    public GenerationExecutionSupport(List<Route> routes) { this.routes = List.copyOf(routes); }
    /** 只返回本次完整配置匹配的注册，不从 provider binding 推断能力。 */
    public Set<ProviderBinding> execution(PlanConfiguration configuration) { return matching(configuration, true); }
    public Set<ProviderBinding> verifiers(PlanConfiguration configuration) { return matching(configuration, false); }
    public boolean requiresMediaProbe() { return routes.stream().anyMatch(route -> route.mediaType()==MediaType.VIDEO && route.resultVerifierAvailable()); }
    private Set<ProviderBinding> matching(PlanConfiguration configuration, boolean execution) {
        MediaType media = configuration instanceof ImagePlanConfiguration ? MediaType.IMAGE : MediaType.VIDEO;
        return routes.stream().filter(route -> route.mediaType() == media && route.selection().equals(configuration.modelSelection()))
            .filter(route -> execution ? route.executionAvailable() : route.resultVerifierAvailable()).map(Route::binding).collect(Collectors.toUnmodifiableSet());
    }
}
