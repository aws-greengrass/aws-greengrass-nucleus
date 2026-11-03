/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.http.apache.internal.conn;

import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.EncryptionUtilsTest;
import com.aws.greengrass.util.Pair;
import org.apache.http.HttpHost;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.config.SocketConfig;
import org.apache.http.conn.HttpHostConnectException;
import org.apache.http.conn.ManagedHttpClientConnection;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.conn.DefaultHttpClientConnectionOperator;
import org.apache.http.impl.conn.ManagedHttpClientConnectionFactory;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(GGExtension.class)
class SdkTlsSocketFactoryTest {

    private static final char[] KEYSTORE_PASSWORD = "password".toCharArray();

    private SSLServerSocket server;
    private SSLContext clientContext;
    private InetAddress ipv6;
    private InetAddress loopback;

    @BeforeEach
    @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
    void startServer(@TempDir Path tempDir) throws Exception {
        Pair<Path, KeyPair> certAndKey =
                EncryptionUtilsTest.generateCertificateFile(2048, false, tempDir.resolve("cert.der"), false);
        Certificate cert;
        try (InputStream in = Files.newInputStream(certAndKey.getLeft())) {
            cert = CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null, null);
        keyStore.setKeyEntry("server", certAndKey.getRight().getPrivate(), KEYSTORE_PASSWORD,
                new Certificate[]{cert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, KEYSTORE_PASSWORD);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(kmf.getKeyManagers(), null, null);
        clientContext = SSLContext.getInstance("TLS");
        clientContext.init(null, tmf.getTrustManagers(), null);

        ipv6 = InetAddress.getByName("2001:db8::1");
        loopback = InetAddress.getByName("127.0.0.1");
        server = (SSLServerSocket) serverContext.getServerSocketFactory().createServerSocket(0, 1, loopback);
        Thread acceptor = new Thread(this::acceptAndHandshake);
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.close();
    }

    @Test
    void GIVEN_connect_to_first_address_throws_SocketException_WHEN_connect_THEN_next_address_is_used()
            throws Exception {
        try (ManagedHttpClientConnection conn =
                connect(new FakeSocket(new SocketException("Network is unreachable"), null), ipv6, loopback)) {
            assertEquals(loopback, conn.getRemoteAddress());
        }
    }

    @Test
    void GIVEN_first_address_fails_after_tcp_connect_WHEN_connect_THEN_error_is_rethrown_and_next_address_not_used() {
        SocketException reset = new SocketException("Connection reset");

        IOException thrown =
                assertThrows(IOException.class, () -> connect(new FakeSocket(null, reset), ipv6, loopback));

        assertSame(reset, thrown);
    }

    @Test
    void GIVEN_only_address_throws_SocketException_WHEN_connect_THEN_HttpHostConnectException_caused_by_it() {
        SocketException unreachable = new SocketException("Network is unreachable");

        HttpHostConnectException thrown = assertThrows(HttpHostConnectException.class,
                () -> connect(new FakeSocket(unreachable, null), ipv6));

        assertSame(unreachable, thrown.getCause().getCause());
    }

    @Test
    void GIVEN_only_address_throws_ConnectException_WHEN_connect_THEN_HttpHostConnectException_caused_directly_by_it() {
        ConnectException refused = new ConnectException("Connection refused");

        HttpHostConnectException thrown = assertThrows(HttpHostConnectException.class,
                () -> connect(new FakeSocket(refused, null), ipv6));

        assertSame(refused, thrown.getCause());
    }

    @Test
    void GIVEN_only_address_throws_NoRouteToHostException_WHEN_connect_THEN_it_is_rethrown_unchanged() {
        NoRouteToHostException noRoute = new NoRouteToHostException("No route to host");

        IOException thrown = assertThrows(IOException.class, () -> connect(new FakeSocket(noRoute, null), ipv6));

        assertSame(noRoute, thrown);
    }

    @Test
    void GIVEN_no_socket_passed_WHEN_connectSocket_fails_THEN_error_is_rethrown_unchanged() {
        SocketException unreachable = new SocketException("Network is unreachable");
        SdkTlsSocketFactory factory = factory(new FakeSocket(unreachable, null));
        HttpHost host = new HttpHost("localhost", server.getLocalPort(), "https");

        IOException thrown = assertThrows(IOException.class, () -> factory.connectSocket(5000, null, host,
                new InetSocketAddress(ipv6, server.getLocalPort()), null, new BasicHttpContext()));

        assertSame(unreachable, thrown);
    }

    /**
     * A factory whose first created socket is the given fake. Later sockets are real and reach the local TLS server.
     */
    private SdkTlsSocketFactory factory(Socket firstSocket) {
        AtomicReference<Socket> nextFake = new AtomicReference<>(firstSocket);
        return new SdkTlsSocketFactory(clientContext, NoopHostnameVerifier.INSTANCE) {
            @Override
            public Socket createSocket(HttpContext context) throws IOException {
                Socket fake = nextFake.getAndSet(null);
                return fake == null ? super.createSocket(context) : fake;
            }
        };
    }

    /**
     * Runs Apache's connect loop over the resolved addresses, using {@link #factory} with the given first socket.
     */
    private ManagedHttpClientConnection connect(Socket firstSocket, InetAddress... resolved) throws IOException {
        DefaultHttpClientConnectionOperator operator = new DefaultHttpClientConnectionOperator(
                RegistryBuilder.<ConnectionSocketFactory>create().register("https", factory(firstSocket)).build(),
                null, host -> resolved);
        HttpHost host = new HttpHost("localhost", server.getLocalPort(), "https");
        ManagedHttpClientConnection conn = ManagedHttpClientConnectionFactory.INSTANCE.create(new HttpRoute(host),
                null);
        operator.connect(conn, host, null, 5000, SocketConfig.DEFAULT, new BasicHttpContext());
        return conn;
    }

    private void acceptAndHandshake() {
        while (!server.isClosed()) {
            try (SSLSocket socket = (SSLSocket) server.accept()) {
                socket.startHandshake();
                socket.getInputStream().read();
            } catch (IOException e) {
                // stopServer closed the server, or the client went away
            }
        }
    }

    /**
     * A socket whose connect fails with {@code connectError}, or, when that is null, reports connected and then fails
     * every read and write with {@code ioError}, like a peer that resets the connection during the TLS handshake.
     * Throwing the exception directly keeps the tests independent of how each JDK reports an unreachable network
     * (Java 8 already throws ConnectException for ENETUNREACH, JDK 13+ a plain SocketException).
     */
    private static final class FakeSocket extends Socket {
        private final SocketException connectError;
        private final SocketException ioError;
        private boolean connected;

        FakeSocket(SocketException connectError, SocketException ioError) {
            this.connectError = connectError;
            this.ioError = ioError;
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            if (connectError != null) {
                throw connectError;
            }
            connected = true;
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            throw ioError;
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            throw ioError;
        }
    }
}
