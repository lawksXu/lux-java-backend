package com.lux.backend.common;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;
import java.util.function.Function;
import java.util.Optional;

/**
 * HTTP 幂等执行器：用“用户 + 请求键”保存请求摘要和响应回执。
 * 相同输入重放原响应，复用同一键提交不同输入则返回冲突。
 */
@Component
public class HttpIdempotency {
    /**
     * HTTP 幂等回执的数据库访问接口。
     */
    private final HttpRequestJpaRepository requests;
    /**
     * JPA 持久化上下文入口，管理实体加载、flush 同步和 refresh 重读。
     */
    private final EntityManager entities;
    /**
     * 统一 JSON 编解码器，负责载荷构建、解析与规范化摘要。
     */
    private final JsonCodec json;
    /**
     * 数据库事务执行边界；默认注入支持锁冲突重试的 DatabaseTransactions。
     */
    private final TransactionTemplate transactions;

    /**
     * 通过构造器集中接收所需依赖，使组件职责与协作对象明确可见。
     */
    public HttpIdempotency(
        HttpRequestJpaRepository requests,
        EntityManager entities,
        JsonCodec json,
        TransactionTemplate transactions
    ) {
        this.requests = requests;
        this.entities = entities;
        this.json = json;
        this.transactions = transactions;
    }

    /**
     * 摘要保留原 operation/input 层次，泛型只复用幂等基础设施，不隐藏具体业务模型。
     */
    private record HashInput<I>(String operation, I input) {}

    /** 对原具名输入计算相同摘要，供协议回执和调用配对验证使用。 */
    public <I> String requestHash(String operation, I input) {
        return json.hash(new HashInput<>(operation, input));
    }

    /** 原回执查询不读取业务最新状态，尚未提交的请求仍可能暂时不可见。 */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Optional<String> originalResponse(String user, String key) {
        return requests.findById(new HttpRequestEntity.Id(Ids.require(user), Ids.require(key)))
            .map(receipt -> receipt.response);
    }

    /** 具备原调用输入的重放入口先核对持久化摘要，再向业务协议交付响应，避免跨协议解码。 */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Optional<String> originalResponse(String user, String key, String expectedRequestHash) {
        java.util.Objects.requireNonNull(expectedRequestHash, "expectedRequestHash");
        return requests.findById(new HttpRequestEntity.Id(Ids.require(user), Ids.require(key)))
            .map(receipt -> {
                if (!expectedRequestHash.equals(receipt.requestHash)) {
                    throw ApiException.conflict("Idempotency key has different input");
                }
                return receipt.response;
            });
    }

    /**
     * 在同一事务中保留请求身份、执行数据库业务动作并保存响应，重试时返回原响应。
     */
    public <I, R> R execute(
        String user,
        String key,
        String operation,
        I input,
        Class<R> responseType,
        Supplier<R> action
    ) {
        return executeDecoded(user, key, operation, input, response -> json.read(response, responseType), action);
    }

    /** 具体协议可提供自己的封闭联合解码器，基础设施仍只负责原回执和事务。 */
    public <I, R> R executeDecoded(String user, String key, String operation, I input,
                                   Function<String, R> decoder, Supplier<R> action) {
        String requestId = Ids.require(key);
        String hash = requestHash(operation, input);
        return transactions.execute(status -> {
            // 先让数据库唯一键协调同一请求的并发到达，再加载并刷新回执，读取本轮可见的最新响应。
            requests.insertIfAbsent(user, requestId, hash);
            HttpRequestEntity receipt = requests.findById(new HttpRequestEntity.Id(user, requestId)).orElseThrow();
            entities.refresh(receipt);
            if (!hash.equals(receipt.requestHash)) {throw ApiException.conflict("Idempotency key has different input");}
            if (receipt.response != null) {return decoder.apply(receipt.response);}
            // 业务动作和响应回执一起提交；失败回滚后不会留下“回执完成但业务未完成”的半成品。
            R response = action.get();
            receipt.response = json.write(response);
            entities.flush();
            return response;
        });
    }
}
