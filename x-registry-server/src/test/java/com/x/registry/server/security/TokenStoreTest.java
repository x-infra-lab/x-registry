package com.x.registry.server.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TokenStoreTest {

    @Nested
    class TokenStoreTests {

        private TokenStore tokenStore;

        @BeforeEach
        void setUp() {
            tokenStore = new TokenStore();
        }

        @Test
        void validateReturnsTokenForRegisteredSecret() {
            AuthToken token = new AuthToken("id1", "secret-abc", Set.of("ns1"), Set.of(Permission.READ));
            tokenStore.addToken(token);

            AuthToken result = tokenStore.validate("secret-abc");

            assertNotNull(result);
            assertEquals("id1", result.getId());
            assertEquals("secret-abc", result.getSecret());
        }

        @Test
        void validateReturnsNullForUnknownSecret() {
            assertNull(tokenStore.validate("unknown-secret"));
        }

        @Test
        void validateReturnsNullForNullSecret() {
            assertNull(tokenStore.validate(null));
        }

        @Test
        void validateReturnsNullForBlankSecret() {
            assertNull(tokenStore.validate(""));
            assertNull(tokenStore.validate("   "));
        }

        @Test
        void sizeReflectsAddedTokens() {
            assertEquals(0, tokenStore.size());

            tokenStore.addToken(new AuthToken("id1", "s1", Set.of("*"), Set.of(Permission.READ)));
            assertEquals(1, tokenStore.size());

            tokenStore.addToken(new AuthToken("id2", "s2", Set.of("*"), Set.of(Permission.WRITE)));
            assertEquals(2, tokenStore.size());
        }
    }

    @Nested
    class TokenEncryptorTests {

        private static final String KEY = "my-encryption-key-for-testing!!";

        @Test
        void encryptThenDecryptReturnsOriginal() {
            String plaintext = "hello-world-secret-token";

            String encrypted = TokenEncryptor.encrypt(plaintext, KEY);
            String decrypted = TokenEncryptor.decrypt(encrypted, KEY);

            assertEquals(plaintext, decrypted);
        }

        @Test
        void encryptedValueDiffersFromPlaintext() {
            String plaintext = "my-secret-value";

            String encrypted = TokenEncryptor.encrypt(plaintext, KEY);

            assertNotEquals(plaintext, encrypted);
        }

        @Test
        void isEncryptedReturnsTrueForEncryptedValue() {
            String encrypted = TokenEncryptor.encrypt("some-value", KEY);

            assertTrue(TokenEncryptor.isEncrypted(encrypted));
        }

        @Test
        void isEncryptedReturnsFalseForPlaintext() {
            assertFalse(TokenEncryptor.isEncrypted("plain-text"));
            assertFalse(TokenEncryptor.isEncrypted("short"));
            assertFalse(TokenEncryptor.isEncrypted(null));
        }
    }
}
