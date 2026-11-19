package io.factry.historian.gateway;

import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * Test certificates in {@code src/test/resources/tls}, all signed by one test CA (valid to 2126):
 * {@value #LOOPBACK} names localhost and 127.0.0.1; {@value #FACTRY_BUILT_IN} names only
 * historian.factry.local and historian.factry.dev, like Factry's built-in certificate, with the
 * test CA standing in for Factry's.
 */
final class TestTls {

    static final String LOOPBACK = "test-server-loopback";
    static final String FACTRY_BUILT_IN = "test-factry-builtin";

    private TestTls() {
    }

    static SslContext server(String name) throws IOException {
        try (InputStream cert = open(name + ".crt"); InputStream key = open(name + ".key")) {
            return GrpcSslContexts.forServer(cert, key).build();
        }
    }

    static String caPem() {
        try (InputStream in = open("test-ca.crt")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<X509Certificate> ca() {
        return FactryGrpcClient.parseCaCertificates(caPem());
    }

    private static InputStream open(String file) {
        InputStream in = TestTls.class.getClassLoader().getResourceAsStream("tls/" + file);
        if (in == null) {
            throw new IllegalStateException("missing test resource tls/" + file);
        }
        return in;
    }
}
