package com.demo.upimesh.crypto;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;

/**
 * Holds the backend's RSA-2048 keypair. Generated fresh on every startup for
 * this demo (see README "What's NOT real" — in production this would be a
 * long-lived key in an HSM/KMS, with only the public half ever leaving it,
 * cached on sender devices).
 */
@Component
public class ServerKeyHolder {

    private static final int KEY_SIZE_BITS = 2048;

    private KeyPair keyPair;

    @PostConstruct
    public void init() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(KEY_SIZE_BITS);
            this.keyPair = generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA not available on this JVM", e);
        }
    }

    public PublicKey getPublicKey() {
        return keyPair.getPublic();
    }

    public PrivateKey getPrivateKey() {
        return keyPair.getPrivate();
    }

    public String getPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(getPublicKey().getEncoded());
    }
}
