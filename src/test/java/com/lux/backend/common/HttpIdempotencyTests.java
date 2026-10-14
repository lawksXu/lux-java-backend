package com.lux.backend.common;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HttpIdempotencyTests {
    private final JsonCodec json=new JsonCodec(JsonMapper.builder().build());
    private final HttpRequestJpaRepository rows=mock(HttpRequestJpaRepository.class);
    private final EntityManager entities=mock(EntityManager.class);
    private final TransactionTemplate transactions=mock(TransactionTemplate.class);
    private final HttpIdempotency requests=new HttpIdempotency(rows,entities,json,transactions);
    private final String user="00000000-0000-0000-0000-000000000001";
    private final String key="00000000-0000-0000-0000-000000000002";
    private final TitleInput input=new TitleInput("new session");

    @Test void actionAndStoredResponseShareTheRetryableTransactionBoundary() {
        AtomicBoolean inTransaction=new AtomicBoolean();
        when(transactions.execute(any())).thenAnswer(invocation->{
            inTransaction.set(true);
            try { return invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(null); }
            finally { inTransaction.set(false); }
        });
        HttpRequestEntity receipt=receipt();
        when(rows.findById(new HttpRequestEntity.Id(user,key))).thenReturn(Optional.of(receipt));
        doAnswer(invocation->{ assertTrue(inTransaction.get()); return null; }).when(entities).flush();

        SavedSession response=requests.execute(user,key,"create",input,SavedSession.class,()->{
            assertTrue(inTransaction.get());
            return new SavedSession("created");
        });

        assertEquals(response,json.read(receipt.response,SavedSession.class));
        verify(entities).flush();
        assertFalse(inTransaction.get());
    }

    @Test void completedReceiptReplaysTheStoredResponseWithoutRepeatingTheAction() {
        when(transactions.execute(any())).thenAnswer(invocation->
                invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
        HttpRequestEntity receipt=receipt();
        receipt.response="{\"session_id\":\"original\"}";
        when(rows.findById(new HttpRequestEntity.Id(user,key))).thenReturn(Optional.of(receipt));

        SavedSession response=requests.execute(user,key,"create",input,SavedSession.class,()->{
            fail("A completed receipt must not invoke the action");
            return null;
        });

        assertEquals("original",response.sessionId());
        verify(entities,never()).flush();
    }

    @Test void changedInputRejectsAReusedReceiptBeforeTheAction() {
        when(transactions.execute(any())).thenAnswer(invocation->
                invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
        HttpRequestEntity receipt=receipt();
        when(rows.findById(new HttpRequestEntity.Id(user,key))).thenReturn(Optional.of(receipt));

        assertThrows(ApiException.class,()->requests.execute(user,key,"other",input,SavedSession.class,()->{
            fail("Conflicting input must not invoke the action");
            return null;
        }));

        assertNull(receipt.response);
    }

    private record TitleInput(String title) {}

    private record SavedSession(@com.fasterxml.jackson.annotation.JsonProperty("session_id") String sessionId) {}

    private HttpRequestEntity receipt() {
        HttpRequestEntity receipt=new HttpRequestEntity();
        receipt.userId=user;
        receipt.requestId=key;
        receipt.requestHash=json.hash(Map.of("operation","create","input",input));
        return receipt;
    }
}
