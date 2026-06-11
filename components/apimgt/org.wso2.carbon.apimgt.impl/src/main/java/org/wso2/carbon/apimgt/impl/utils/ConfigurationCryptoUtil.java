/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.apimgt.impl.utils;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.core.util.CryptoException;
import org.wso2.carbon.core.util.CryptoUtil;

import java.nio.charset.StandardCharsets;

/**
 * Utility for decrypting persisted configuration values with backward-compatible fallbacks.
 */
public final class ConfigurationCryptoUtil {

    private static final Log log = LogFactory.getLog(ConfigurationCryptoUtil.class);

    /**
     * Keystore-based internal crypto provider used before APIM 4.7 symmetric encryption.
     */
    public static final String LEGACY_KEYSTORE_INTERNAL_CRYPTO_PROVIDER =
            "org.wso2.carbon.crypto.provider.KeyStoreBasedInternalCryptoProvider";

    /**
     * Default RSA cipher transformation used by APIM 4.6 and earlier releases.
     */
    public static final String LEGACY_RSA_CIPHER_TRANSFORMATION = "RSA/ECB/OAEPwithSHA1andMGF1Padding";

    private static final String SYMMETRIC_CIPHER_PREFIX = "AES";

    private ConfigurationCryptoUtil() {

    }

    /**
     * Decrypts a persisted configuration value using the active crypto provider, and falls back to
     * the legacy keystore-based RSA provider only when the ciphertext format indicates RSA encryption.
     *
     * @param cipherText encrypted configuration value
     * @return decrypted plain text
     * @throws CryptoException if decryption fails with all supported strategies
     */
    public static String decryptWithLegacyFallback(String cipherText) throws CryptoException {

        if (StringUtils.isBlank(cipherText)) {
            return cipherText;
        }

        CryptoUtil cryptoUtil = CryptoUtil.getDefaultCryptoUtil();
        byte[] cipherBytes = cipherText.getBytes(StandardCharsets.UTF_8);
        try {
            return new String(cryptoUtil.decrypt(cipherBytes), StandardCharsets.UTF_8);
        } catch (CryptoException primaryException) {
            if (!isLegacyRsaCipherText(cipherText)) {
                if (log.isDebugEnabled()) {
                    log.debug("Default configuration decryption failed and ciphertext is not RSA formatted. "
                            + "Skipping legacy RSA fallback.", primaryException);
                }
                throw primaryException;
            }
            if (log.isDebugEnabled()) {
                log.debug("Default configuration decryption failed. Attempting legacy RSA decryption.",
                        primaryException);
            }
            return decryptUsingLegacyProviders(cryptoUtil, cipherText, cipherBytes, primaryException);
        }
    }

    /**
     * Determines whether the ciphertext was produced by the legacy keystore RSA provider.
     *
     * @param cipherText encrypted configuration value
     * @return true if legacy RSA fallback may be attempted
     */
    static boolean isLegacyRsaCipherText(String cipherText) {

        String normalizedCipherText = cipherText.trim();
        if (!normalizedCipherText.startsWith("{")) {
            return true;
        }

        String lowerCaseCipherText = normalizedCipherText.toLowerCase();
        if (lowerCaseCipherText.contains("\"transformation\":\"aes")
                || lowerCaseCipherText.contains("aes/gcm")
                || lowerCaseCipherText.contains("aes/ecb")) {
            return false;
        }

        return lowerCaseCipherText.contains("rsa/");
    }

    private static String decryptUsingLegacyProviders(CryptoUtil cryptoUtil, String cipherText, byte[] cipherBytes,
                                                      CryptoException primaryException) throws CryptoException {

        CryptoException lastException = primaryException;
        try {
            return new String(cryptoUtil.decrypt(cipherBytes, LEGACY_RSA_CIPHER_TRANSFORMATION,
                    LEGACY_KEYSTORE_INTERNAL_CRYPTO_PROVIDER), StandardCharsets.UTF_8);
        } catch (Exception legacySelfContainedException) {
            lastException = toCryptoException(legacySelfContainedException);
            if (log.isDebugEnabled()) {
                log.debug("Legacy self-contained RSA decryption failed. Attempting legacy base64 RSA decryption.",
                        legacySelfContainedException);
            }
        }

        try {
            return new String(cryptoUtil.base64DecodeAndDecrypt(cipherText, LEGACY_RSA_CIPHER_TRANSFORMATION,
                    LEGACY_KEYSTORE_INTERNAL_CRYPTO_PROVIDER), StandardCharsets.UTF_8);
        } catch (Exception legacyBase64Exception) {
            if (log.isDebugEnabled()) {
                log.debug("Legacy base64 RSA decryption failed.", legacyBase64Exception);
            }
            throw lastException;
        }
    }

    private static CryptoException toCryptoException(Exception exception) {

        if (exception instanceof CryptoException) {
            return (CryptoException) exception;
        }
        return new CryptoException("Legacy configuration decryption failed.", exception);
    }
}
