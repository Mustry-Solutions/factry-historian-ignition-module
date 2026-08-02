package io.factry.historian.gateway;

import org.junit.jupiter.api.Test;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the combined TLS trust store trusts both the system CAs and the bundled Factry CA. */
class FactryGrpcClientTlsTest {

    private static final String CA_RESOURCE = "factry-historian-ca.crt";

    private static X509Certificate[] issuers(TrustManagerFactory tmf) {
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) {
                return ((X509TrustManager) tm).getAcceptedIssuers();
            }
        }
        return new X509Certificate[0];
    }

    private static InputStream caStream() {
        return FactryGrpcClientTlsTest.class.getClassLoader().getResourceAsStream(CA_RESOURCE);
    }

    @Test
    void systemOnly_trustsPublicCAs() throws Exception {
        X509Certificate[] issuers = issuers(FactryGrpcClient.buildCombinedTrustManagerFactory(null));
        assertTrue(issuers.length > 0, "Should trust the JVM's built-in system/public CAs");
    }

    @Test
    void combined_trustsSystemCAsAndBundledFactryCA() throws Exception {
        // The bundled Factry CA (a private CA) should not already be a system CA.
        X509Certificate factryCa;
        try (InputStream ca = caStream()) {
            assertNotNull(ca, "bundled Factry CA cert resource should be present");
            factryCa = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(ca);
        }

        X509Certificate[] systemOnly = issuers(FactryGrpcClient.buildCombinedTrustManagerFactory(null));
        assertFalse(contains(systemOnly, factryCa),
                "The private Factry CA should not already be in the system trust store");

        // The combined store must trust the Factry CA AND keep all the system CAs.
        X509Certificate[] combined;
        try (InputStream ca = caStream()) {
            combined = issuers(FactryGrpcClient.buildCombinedTrustManagerFactory(ca));
        }
        assertTrue(contains(combined, factryCa), "Combined store must trust the bundled Factry CA");
        assertTrue(combined.length >= systemOnly.length + 1,
                "Combined store must keep the system CAs and add the Factry CA");
    }

    private static boolean contains(X509Certificate[] certs, X509Certificate target) {
        return Arrays.stream(certs).anyMatch(c -> c.equals(target));
    }
}
