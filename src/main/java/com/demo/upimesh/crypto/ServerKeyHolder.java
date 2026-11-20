package com.demo.upimesh.crypto;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

@Component
public class ServerKeyHolder {

    private static final Logger log = LoggerFactory.getLogger(ServerKeyHolder.class);

    @Value("${paymesh.rsa.private-key-base64:}")
    private String configuredPrivateKey;

    @Value("${paymesh.rsa.require-persistent-key:false}")
    private boolean requirePersistentKey;

    private KeyPair keyPair;

    @PostConstruct
    public void init() throws Exception {
        if (configuredPrivateKey != null && !configuredPrivateKey.isBlank()) {
            byte[] keyBytes = Base64.getDecoder().decode(
                    configuredPrivateKey.replaceAll("\\s+", ""));

            KeyFactory factory = KeyFactory.getInstance("RSA");
            PrivateKey privateKey = factory.generatePrivate(
                    new PKCS8EncodedKeySpec(keyBytes));

            if (!(privateKey instanceof RSAPrivateCrtKey rsaPrivate)) {
                throw new IllegalStateException("RSA CRT private key required");
            }

            PublicKey publicKey = factory.generatePublic(
                    new RSAPublicKeySpec(
                            rsaPrivate.getModulus(),
                            rsaPrivate.getPublicExponent()));

            keyPair = new KeyPair(publicKey, privateKey);
            log.info("Persistent RSA keypair loaded from environment");
        } else {
            if (requirePersistentKey) {
                throw new IllegalStateException(
                        "Persistent RSA key required; set PAYMESH_RSA_PRIVATE_KEY_BASE64");
            }
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            keyPair = generator.generateKeyPair();
            log.warn("Ephemeral RSA keypair generated; pending packets will not survive restart");
        }
    }

    public PublicKey getPublicKey() {
        return keyPair.getPublic();
    }

    public PrivateKey getPrivateKey() {
        return keyPair.getPrivate();
    }

    public String getPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(
                keyPair.getPublic().getEncoded());
    }
}
