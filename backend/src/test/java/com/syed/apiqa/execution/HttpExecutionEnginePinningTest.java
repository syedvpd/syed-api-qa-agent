package com.syed.apiqa.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syed.apiqa.assertion.AssertionEngine;
import com.syed.apiqa.domain.*;
import com.syed.apiqa.persistence.AssertionResultRepository;
import com.syed.apiqa.persistence.CapturedVariableRepository;
import com.syed.apiqa.persistence.ExecutionRepository;
import com.syed.apiqa.safety.SecretMasker;
import com.syed.apiqa.safety.SsrfProtectionGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class HttpExecutionEnginePinningTest {

    private ServerSocket serverSocket;
    private int listenPort;
    private Thread serverThread;
    private final AtomicBoolean connectionAccepted = new AtomicBoolean(false);
    private final AtomicReference<String> receivedHostHeader = new AtomicReference<>();
    private final AtomicReference<String> clientConnectedIp = new AtomicReference<>();
    private CountDownLatch serverReadyLatch;

    private HttpExecutionEngine engine;
    private SsrfProtectionGuard ssrfGuard;

    @BeforeEach
    void setUp() throws Exception {
        serverReadyLatch = new CountDownLatch(1);
        serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        listenPort = serverSocket.getLocalPort();

        serverThread = new Thread(() -> {
            try {
                serverReadyLatch.countDown();
                Socket socket = serverSocket.accept();
                connectionAccepted.set(true);
                clientConnectedIp.set(socket.getInetAddress().getHostAddress());

                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    if (line.toLowerCase().startsWith("host:")) {
                        receivedHostHeader.set(line.substring(5).trim());
                    }
                }

                OutputStream out = socket.getOutputStream();
                String httpResponse = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: 15\r\n\r\n" +
                        "{\"status\":\"ok\"}";
                out.write(httpResponse.getBytes(StandardCharsets.UTF_8));
                out.flush();
                socket.close();
            } catch (Exception ignored) {}
        });
        serverThread.setDaemon(true);
        serverThread.start();
        assertTrue(serverReadyLatch.await(5, TimeUnit.SECONDS));

        ssrfGuard = Mockito.mock(SsrfProtectionGuard.class);
        SecretMasker secretMasker = new SecretMasker();
        AssertionEngine assertionEngine = new AssertionEngine(new ObjectMapper());
        ExecutionRepository executionRepo = Mockito.mock(ExecutionRepository.class);
        AssertionResultRepository assertionRepo = Mockito.mock(AssertionResultRepository.class);
        CapturedVariableRepository variableRepo = Mockito.mock(CapturedVariableRepository.class);

        engine = new HttpExecutionEngine(
                ssrfGuard,
                secretMasker,
                assertionEngine,
                executionRepo,
                assertionRepo,
                variableRepo,
                new ObjectMapper()
        );
        ReflectionTestUtils.setField(engine, "defaultTimeoutSeconds", 5);
        ReflectionTestUtils.setField(engine, "maxResponseSizeBytes", 2097152);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
        }
        if (serverThread != null) {
            serverThread.interrupt();
        }
    }

    @Test
    void shouldConnectDirectlyToPinnedIpWithoutResolvingUnresolvableHostname() throws Exception {
        // A fake hostname that is mathematically impossible to resolve in public DNS
        String unresolvableFakeHost = "unresolvable-fake-host-999.test";
        String baseUrl = "http://" + unresolvableFakeHost + ":" + listenPort;
        String fullUrl = baseUrl + "/api/test";

        // Pre-validated pinned target pointing socket directly to 127.0.0.1
        URI originalUri = URI.create(fullUrl);
        InetAddress pinnedIp = InetAddress.getByName("127.0.0.1");
        String pinnedUrl = "http://127.0.0.1:" + listenPort + "/api/test";
        String hostHeader = unresolvableFakeHost + ":" + listenPort;

        SsrfProtectionGuard.ValidatedTarget validatedTarget = new SsrfProtectionGuard.ValidatedTarget(
                originalUri,
                pinnedIp,
                unresolvableFakeHost,
                listenPort,
                pinnedUrl,
                hostHeader,
                true
        );

        when(ssrfGuard.resolveAndValidate(anyString(), anyBoolean())).thenReturn(validatedTarget);

        TestRun run = new TestRun();
        run.setId(UUID.randomUUID().toString());
        run.setEnvironmentType(EnvironmentType.STAGING);

        TestCase testCase = new TestCase();
        testCase.setId(UUID.randomUUID().toString());
        testCase.setTestRun(run);

        TestStep step = new TestStep();
        step.setId(UUID.randomUUID().toString());
        step.setTestCase(testCase);
        step.setName("Pinned Connection Verification Step");
        step.setMethod("GET");
        step.setPathTemplate("/api/test");
        step.setExpectedStatus(200);

        ExecutionContext context = new ExecutionContext(run.getId());

        // Execute step
        HttpExecutionEngine.StepExecutionOutcome outcome = engine.executeStep(
                step,
                baseUrl,
                context,
                EnvironmentType.STAGING,
                "NONE",
                null
        );

        // 1. Verify execution succeeded
        assertEquals(StepStatus.PASSED, outcome.getFinalStatus(), "Step must pass when connected to pinned IP");
        assertEquals(200, outcome.getExecution().getResponseStatus());

        // 2. PROVE the socket connected to the pinned IP (127.0.0.1)
        assertTrue(connectionAccepted.get(), "ServerSocket must have accepted a connection from pinned IP");
        assertEquals("127.0.0.1", clientConnectedIp.get(), "Physical socket must connect to 127.0.0.1");

        // 3. PROVE the original unresolvable hostname was sent in Host header
        assertNotNull(receivedHostHeader.get());
        assertTrue(receivedHostHeader.get().startsWith(unresolvableFakeHost),
                "Host header must contain original hostname: " + receivedHostHeader.get());
    }
}
