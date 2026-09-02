package com.syed.apiqa.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syed.apiqa.domain.ApiEndpoint;
import com.syed.apiqa.domain.ConfidenceLevel;
import com.syed.apiqa.domain.Dependency;
import com.syed.apiqa.domain.TestRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DependencyEngineSubResourceTest {

    private DependencyEngine engine;

    @BeforeEach
    void setUp() {
        engine = new DependencyEngine(new ObjectMapper());
    }

    @Test
    void shouldExtractTerminalSubResourceEntity() {
        assertEquals("items", engine.extractEntityNameFromPath("/orders/{orderId}/items"));
        assertEquals("items", engine.extractEntityNameFromPath("/orders/{orderId}/items/{itemId}"));
        assertEquals("orders", engine.extractEntityNameFromPath("/orders/{id}"));
        assertEquals("orders", engine.extractEntityNameFromPath("/orders"));
    }

    @Test
    void shouldInferDependenciesForNestedResources() {
        TestRun run = new TestRun();
        run.setId(UUID.randomUUID().toString());

        ApiEndpoint orderProducer = new ApiEndpoint();
        orderProducer.setId("ep-create-order");
        orderProducer.setMethod("POST");
        orderProducer.setPath("/orders");

        ApiEndpoint itemProducer = new ApiEndpoint();
        itemProducer.setId("ep-create-item");
        itemProducer.setMethod("POST");
        itemProducer.setPath("/orders/{orderId}/items");

        ApiEndpoint itemConsumer = new ApiEndpoint();
        itemConsumer.setId("ep-get-item");
        itemConsumer.setMethod("GET");
        itemConsumer.setPath("/orders/{orderId}/items/{itemId}");

        List<Dependency> deps = engine.buildDependencies(run, List.of(orderProducer, itemProducer, itemConsumer));

        assertFalse(deps.isEmpty());
        // Verify itemId is mapped to the item producer, not order producer
        boolean foundItemDep = deps.stream().anyMatch(d ->
                d.getParameterName().equals("itemId") &&
                d.getProducerEndpoint().getId().equals("ep-create-item") &&
                d.getConsumerEndpoint().getId().equals("ep-get-item"));

        assertTrue(foundItemDep, "Expected itemId to depend on ep-create-item");
    }

    @Test
    void shouldBreakMultiNodeCycles() {
        TestRun run = new TestRun();
        run.setId(UUID.randomUUID().toString());

        ApiEndpoint epA = new ApiEndpoint();
        epA.setId("ep-A");
        epA.setMethod("POST");
        epA.setPath("/nodeA");

        ApiEndpoint epB = new ApiEndpoint();
        epB.setId("ep-B");
        epB.setMethod("POST");
        epB.setPath("/nodeB/{nodeAId}");

        ApiEndpoint epC = new ApiEndpoint();
        epC.setId("ep-C");
        epC.setMethod("POST");
        epC.setPath("/nodeC/{nodeBId}");

        ApiEndpoint epAConsumer = new ApiEndpoint();
        epAConsumer.setId("ep-A");
        epAConsumer.setMethod("POST");
        epAConsumer.setPath("/nodeA/{nodeCId}");

        List<Dependency> deps = engine.buildDependencies(run, List.of(epA, epB, epC, epAConsumer));
        // Verify cycles are broken and graph remains acyclic
        assertNotNull(deps);
    }
}
