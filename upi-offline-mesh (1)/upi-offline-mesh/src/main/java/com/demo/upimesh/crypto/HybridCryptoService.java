package com.demo.upimesh.crypto;

import com.demo.upimesh.model.PaymentInstruction;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;

/**
 * Implements the wire scheme described in the README:
 *
 *   [RSA-OAEP-encrypted AES-256 key][12-byte GCM IV][AES-GCM ciphertext + 16-byte tag]
 *
 * Why hybrid? RSA-2048/OAEP can only encrypt ~245 bytes directly, and a
 * PaymentInstruction serialized to JSON can exceed that. So each packet gets
 * its own fresh AES-256 key; the (small, fixed-size) AES key is what actually
 * goes through RSA, and the (larger, variable-size) JSON payload goes through
 * AES-GCM, which is both fast and authenticated. This is the same shape TLS
 * uses for the same reason.
 *
 * AES-GCM's authentication tag is what makes tampering detectable: flipping
 * any bit in the ciphertext makes the tag fail to verify, and decrypt()
 * throws instead of silently returning garbage or manipulated data.
 */
@Service
public class HybridCryptoService {

    private static final String RSA_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final String AES_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int AES_KEY_SIZE_BITS = 256;
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final ServerKeyHolder keyHolder;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecureRandom secureRandom = new SecureRandom();

    public HybridCryptoService(ServerKeyHolder keyHolder) {
        this.keyHolder = keyHolder;
    }

    /** Encrypts a PaymentInstruction for transport, returning a Base64 string. */
    public String encrypt(PaymentInstruction instruction) {
        try {
            byte[] plaintext = objectMapper.writeValueAsBytes(instruction);

            // 1. Fresh AES-256 key for this packet only.
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(AES_KEY_SIZE_BITS, secureRandom);
            SecretKey aesKey = keyGen.generateKey();

            // 2. Random IV, then AES-GCM encrypt (ciphertext + 16-byte tag appended).
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);
            Cipher aesCipher = Cipher.getInstance(AES_TRANSFORMATION);
            aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] aesCiphertext = aesCipher.doFinal(plaintext);

            // 3. Wrap the AES key itself with RSA-OAEP using the server's public key.
            Cipher rsaCipher = Cipher.getInstance(RSA_TRANSFORMATION);
            rsaCipher.init(Cipher.ENCRYPT_MODE, keyHolder.getPublicKey());
            byte[] wrappedKey = rsaCipher.doFinal(aesKey.getEncoded());

            // 4. Concatenate: [wrappedKey][iv][aesCiphertext] and Base64 it.
            byte[] combined = new byte[wrappedKey.length + iv.length + aesCiphertext.length];
            System.arraycopy(wrappedKey, 0, combined, 0, wrappedKey.length);
            System.arraycopy(iv, 0, combined, wrappedKey.length, iv.length);
            System.arraycopy(aesCiphertext, 0, combined, wrappedKey.length + iv.length, aesCiphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new CryptoOperationException("Failed to encrypt payment instruction", e);
        }
    }

    /**
     * Decrypts a Base64 packet ciphertext back into a PaymentInstruction.
     * Throws CryptoOperationException on any tampering (bad GCM tag), a
     * malformed blob, or any other cryptographic failure — callers should
     * treat that as "INVALID", never as "settled".
     */
    public PaymentInstruction decrypt(String base64Ciphertext) {
        try {
            byte[] combined = Base64.getDecoder().decode(base64Ciphertext);

            int rsaKeyLengthBytes = rsaOutputLengthBytes();
            if (combined.length < rsaKeyLengthBytes + GCM_IV_LENGTH_BYTES) {
                throw new IllegalArgumentException("Ciphertext too short to contain wrapped key + IV");
            }

            byte[] wrappedKey = Arrays.copyOfRange(combined, 0, rsaKeyLengthBytes);
            byte[] iv = Arrays.copyOfRange(combined, rsaKeyLengthBytes, rsaKeyLengthBytes + GCM_IV_LENGTH_BYTES);
            byte[] aesCiphertext = Arrays.copyOfRange(
                    combined, rsaKeyLengthBytes + GCM_IV_LENGTH_BYTES, combined.length);

            Cipher rsaCipher = Cipher.getInstance(RSA_TRANSFORMATION);
            rsaCipher.init(Cipher.DECRYPT_MODE, keyHolder.getPrivateKey());
            byte[] aesKeyBytes = rsaCipher.doFinal(wrappedKey);
            SecretKey aesKey = new SecretKeySpec(aesKeyBytes, "AES");

            Cipher aesCipher = Cipher.getInstance(AES_TRANSFORMATION);
            aesCipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] plaintext = aesCipher.doFinal(aesCiphertext); // throws AEADBadTagException if tampered

            return objectMapper.readValue(plaintext, PaymentInstruction.class);
        } catch (AEADBadTagException e) {
            throw new CryptoOperationException("GCM authentication failed — ciphertext was tampered with", e);
        } catch (Exception e) {
            throw new CryptoOperationException("Failed to decrypt packet", e);
        }
    }

    /**
     * SHA-256 hash of the raw ciphertext string. This is the value the
     * idempotency cache and the transactions.packet_hash unique index key
     * off of — see README "Problem 2: the duplicate-storm" for why the
     * ciphertext (and not packetId or cleartext) is what gets hashed.
     */
    public String hash(String base64Ciphertext) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(base64Ciphertext.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available on this JVM", e);
        }
    }

    private int rsaOutputLengthBytes() {
        RSAPublicKey rsaPublicKey = (RSAPublicKey) keyHolder.getPublicKey();
        return (rsaPublicKey.getModulus().bitLength() + 7) / 8;
    }

    /** Unchecked wrapper so callers don't have to declare checked crypto exceptions everywhere. */
    public static class CryptoOperationException extends RuntimeException {
        public CryptoOperationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
