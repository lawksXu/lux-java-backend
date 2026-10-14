package com.lux.backend.user;

import com.lux.backend.common.ApiException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserRepositoryTests {
    private final UserJpaRepository users = mock(UserJpaRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final UserRepository repository = new UserRepository(users, entityManager);

    @Test
    void ownerCoordinationRejectsAStaleVersionBeforeRefreshing() {
        UserEntity user = new UserEntity();
        user.id = "owner";
        user.version = 4L;
        when(users.findById(user.id)).thenReturn(Optional.of(user));
        when(users.coordinate(user.id, 4L)).thenReturn(0);

        assertThrows(OptimisticLockingFailureException.class, () -> repository.coordinate(user.id));

        verify(entityManager, never()).refresh(user);
    }

    @Test
    void successfulCoordinationRefreshesTheManagedVersionAfterTheWrite() {
        UserEntity user = new UserEntity();
        user.id = "owner";
        user.version = 4L;
        when(users.findById(user.id)).thenReturn(Optional.of(user));
        when(users.coordinate(user.id, 4L)).thenReturn(1);

        repository.coordinate(user.id);

        InOrder order = inOrder(users, entityManager);
        order.verify(users).coordinate(user.id, 4L);
        order.verify(entityManager).refresh(user);
    }

    @Test
    void coordinationOfAnUnknownOwnerKeepsTheNotFoundContract() {
        when(users.findById("missing")).thenReturn(Optional.empty());

        ApiException failure = assertThrows(ApiException.class, () -> repository.coordinate("missing"));

        assertEquals(404, failure.status());
        verifyNoInteractions(entityManager);
    }
}
