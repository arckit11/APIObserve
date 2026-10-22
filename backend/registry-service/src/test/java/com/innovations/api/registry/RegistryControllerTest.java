package com.innovations.api.registry;

import com.innovations.api.dto.ServiceDto;
import com.innovations.api.exceptions.ConflictException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegistryControllerTest {

    @Mock
    private ServiceRepository repo;

    private RegistryController controller;

    @BeforeEach
    void setUp() {
        controller = new RegistryController(repo);
    }

    @Test
    void update_throwsConflict_whenNameAndEnvAlreadyTakenByAnotherService() {
        UUID serviceId = UUID.randomUUID();
        ServiceEntity existing = new ServiceEntity();
        existing.setId(serviceId);
        existing.setName("orders-api");
        existing.setEnvironment("prod");
        existing.setBaseUrl("http://orders.prod");

        when(repo.findById(serviceId)).thenReturn(Optional.of(existing));
        when(repo.existsByNameAndEnvironmentAndIdNot("payments-api", "prod", serviceId)).thenReturn(true);

        ServiceDto.UpdateRequest req = new ServiceDto.UpdateRequest(
                "payments-api", null, null, "prod", null, null
        );

        ConflictException ex = assertThrows(ConflictException.class, () -> controller.update(serviceId, req));
        assertEquals("Service 'payments-api' already registered in 'prod'", ex.getMessage());
    }

    @Test
    void update_succeeds_whenNameAndEnvAreUnique() {
        UUID serviceId = UUID.randomUUID();
        ServiceEntity existing = new ServiceEntity();
        existing.setId(serviceId);
        existing.setName("orders-api");
        existing.setEnvironment("prod");
        existing.setBaseUrl("http://orders.prod");
        existing.setCreatedAt(OffsetDateTime.now());
        existing.setUpdatedAt(OffsetDateTime.now());

        when(repo.findById(serviceId)).thenReturn(Optional.of(existing));
        when(repo.existsByNameAndEnvironmentAndIdNot("orders-v2-api", "prod", serviceId)).thenReturn(false);
        when(repo.save(any(ServiceEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ServiceDto.UpdateRequest req = new ServiceDto.UpdateRequest(
                "orders-v2-api", null, null, null, null, null
        );

        ServiceDto res = controller.update(serviceId, req);
        assertNotNull(res);
        assertEquals("orders-v2-api", res.name());
        verify(repo).save(existing);
    }
}
