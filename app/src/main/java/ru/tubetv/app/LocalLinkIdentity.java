package ru.tubetv.app;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.UUID;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;

/** Local TLS identity. The private key is generated and retained by Android Keystore. */
final class LocalLinkIdentity {
    private static final String ALIAS = "russtube-local-link-v2";
    private static final String PREFS = "local_link_identity";
    private static final String DEVICE_ID = "device_id";

    static String deviceId(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
        String id = prefs.getString(DEVICE_ID, "");
        if (!id.isEmpty()) return id;
        id = UUID.randomUUID().toString();
        if (!prefs.edit().putString(DEVICE_ID, id).commit())
            throw new IllegalStateException("Не удалось сохранить ID устройства");
        return id;
    }

    static synchronized SSLContext sslContext() throws Exception {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        if (!keys.containsAlias(ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            long now = System.currentTimeMillis();
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
                    .setAlgorithmParameterSpec(new java.security.spec.ECGenParameterSpec("secp256r1"))
                    .setCertificateSubject(new X500Principal("CN=RussTube Local Device"))
                    .setCertificateSerialNumber(new BigInteger(64, new SecureRandom()))
                    .setCertificateNotBefore(new Date(now - 60_000L))
                    .setCertificateNotAfter(new Date(now + 10L * 365 * 24 * 60 * 60 * 1000))
                    .build();
            generator.initialize(spec);
            generator.generateKeyPair();
            keys.load(null);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, null);
        X509TrustManager pairingTrust = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
                    throws java.security.cert.CertificateException { check(chain); }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws java.security.cert.CertificateException { check(chain); }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            private void check(X509Certificate[] chain)
                    throws java.security.cert.CertificateException {
                if (chain == null || chain.length != 1)
                    throw new java.security.cert.CertificateException("Invalid peer certificate");
                chain[0].checkValidity();
                if (!"EC".equalsIgnoreCase(chain[0].getPublicKey().getAlgorithm()))
                    throw new java.security.cert.CertificateException("Unsupported peer key");
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        // Trust here only enables the TLS handshake. Every accepted socket MUST be pinned
        // to its paired fingerprint (or the one-time QR fingerprint) before application data.
        context.init(kmf.getKeyManagers(), new TrustManager[]{pairingTrust}, new SecureRandom());
        return context;
    }

    static String ownFingerprint() throws Exception {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        if (!keys.containsAlias(ALIAS)) sslContext();
        Certificate certificate = keys.getCertificate(ALIAS);
        return fingerprint(certificate);
    }

    static String fingerprint(Certificate certificate) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        char[] digits = "0123456789abcdef".toCharArray();
        char[] hex = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            hex[i * 2] = digits[(digest[i] >>> 4) & 15];
            hex[i * 2 + 1] = digits[digest[i] & 15];
        }
        return new String(hex);
    }
}
