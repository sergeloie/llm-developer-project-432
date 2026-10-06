package com.carddraft.temporal;

import java.io.IOException;
import java.net.ServerSocket;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import io.temporal.testserver.TestServer;

/**
 * Gives the suite a process engine to talk to.
 *
 * <p>The application has no mode in which it starts its own engine — the address
 * {@code card.temporal.target} names has to resolve to something — so every {@code @SpringBootTest}
 * in this repository needed an engine running before it could load a context. That was worth 46
 * failing tests on a machine with nothing on the port, and the same 46 would have failed on CI.
 *
 * <p>Started once for the whole test JVM and pointed at with a system property, because a system
 * property outranks every other configuration source. That is what makes the suite independent of
 * the developer's own {@code .env} for this setting: a machine that names a different engine still
 * runs these tests against the engine this extension started.
 *
 * <p>A port bound server rather than the in-process one because it is the honest shape — a real
 * address on a real port, reached over a real connection, exactly as production reaches it. Sharing
 * one server across every test context is also what removes a hazard the code used to warn about:
 * several contexts polling an engine that one of them owns to close.
 */
public class ProcessEngineTestServerExtension implements BeforeAllCallback {

    private static final String TARGET_PROPERTY = "card.temporal.target";

    private static TestServer.PortBoundTestServer server;
    private static int port;

    @Override
    public void beforeAll(ExtensionContext context) {
        if (server != null) {
            return;
        }
        try {
            port = freePort();
            server = TestServer.createPortBoundServer(port);
        } catch (IOException e) {
            throw new IllegalStateException("could not start a process engine for the test suite", e);
        }
        System.setProperty(TARGET_PROPERTY, "127.0.0.1:" + port);
    }

    /**
     * Asks the operating system for a port and gives it straight back.
     *
     * <p>Not a fixed port, because a fixed one turns "something else is running here" into a suite
     * that cannot start. The gap between asking and binding is narrow enough for a test run, and a
     * collision surfaces as a connection error naming the port rather than as a silent hang.
     */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}