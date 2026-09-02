package com.syed.apiqa.safety;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.*;
import java.util.Collections;

/**
 * Pinned Connection Manager preventing DNS-rebinding Time-of-Check to Time-of-Use (TOCTOU) attacks.
 * Connects TCP sockets directly to the pre-validated pinned InetAddress, while preserving
 * TLS Server Name Indication (SNI), certificate validation, and the HTTP Host header.
 */
public class PinnedConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(PinnedConnectionManager.class);

    static {
        // Allows HttpURLConnection to set Host header for IP-pinned connections
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
    }

    public static HttpURLConnection openPinnedConnection(SsrfProtectionGuard.ValidatedTarget target, int timeoutSeconds) throws IOException {
        if (target == null) {
            throw new IllegalArgumentException("ValidatedTarget cannot be null");
        }

        URI originalUri = target.originalUri();
        String scheme = originalUri.getScheme();

        if (!target.isPinned() || target.pinnedAddress() == null) {
            HttpURLConnection conn = (HttpURLConnection) originalUri.toURL().openConnection();
            conn.setConnectTimeout(timeoutSeconds * 1000);
            conn.setReadTimeout(timeoutSeconds * 1000);
            return conn;
        }

        if ("https".equalsIgnoreCase(scheme)) {
            URL originalUrl = originalUri.toURL();
            HttpsURLConnection conn = (HttpsURLConnection) originalUrl.openConnection();
            conn.setConnectTimeout(timeoutSeconds * 1000);
            conn.setReadTimeout(timeoutSeconds * 1000);

            try {
                SSLSocketFactory defaultFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
                PinnedSSLSocketFactory pinnedFactory = new PinnedSSLSocketFactory(
                        defaultFactory,
                        target.pinnedAddress(),
                        target.originalHost(),
                        target.port()
                );
                conn.setSSLSocketFactory(pinnedFactory);
                conn.setHostnameVerifier((hostname, session) ->
                        HttpsURLConnection.getDefaultHostnameVerifier().verify(target.originalHost(), session)
                );
            } catch (Exception e) {
                log.warn("Failed to set pinned SSLSocketFactory, falling back: {}", e.getMessage());
            }

            return conn;
        } else {
            // Plain HTTP: Connect directly to pinned IP URL and send original Host header
            URL pinnedUrl = new URL(target.pinnedUrl());
            HttpURLConnection conn = (HttpURLConnection) pinnedUrl.openConnection();
            conn.setConnectTimeout(timeoutSeconds * 1000);
            conn.setReadTimeout(timeoutSeconds * 1000);
            conn.setRequestProperty("Host", target.originalHostHeader());
            return conn;
        }
    }

    private static class PinnedSSLSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        private final InetAddress pinnedAddress;
        private final String originalHost;
        private final int port;

        public PinnedSSLSocketFactory(SSLSocketFactory delegate, InetAddress pinnedAddress, String originalHost, int port) {
            this.delegate = delegate;
            this.pinnedAddress = pinnedAddress;
            this.originalHost = originalHost;
            this.port = port;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket() throws IOException {
            return new Socket() {
                @Override
                public void connect(SocketAddress endpoint, int timeout) throws IOException {
                    super.connect(new InetSocketAddress(pinnedAddress, port), timeout);
                }
            };
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(pinnedAddress, port), 15000);
            return wrapWithTls(socket);
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(pinnedAddress, port), 15000);
            return wrapWithTls(socket);
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            Socket socket = new Socket();
            socket.bind(new InetSocketAddress(localHost, localPort));
            socket.connect(new InetSocketAddress(pinnedAddress, port), 15000);
            return wrapWithTls(socket);
        }

        @Override
        public Socket createSocket(InetAddress host, int port, InetAddress localAddress, int localPort) throws IOException {
            Socket socket = new Socket();
            socket.bind(new InetSocketAddress(localAddress, localPort));
            socket.connect(new InetSocketAddress(pinnedAddress, port), 15000);
            return wrapWithTls(socket);
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return wrapWithTls(s);
        }

        private Socket wrapWithTls(Socket rawSocket) throws IOException {
            SSLSocket sslSocket = (SSLSocket) delegate.createSocket(rawSocket, originalHost, port, true);
            SSLParameters params = sslSocket.getSSLParameters();
            params.setServerNames(Collections.singletonList(new SNIHostName(originalHost)));
            sslSocket.setSSLParameters(params);
            sslSocket.startHandshake();
            return sslSocket;
        }
    }
}
