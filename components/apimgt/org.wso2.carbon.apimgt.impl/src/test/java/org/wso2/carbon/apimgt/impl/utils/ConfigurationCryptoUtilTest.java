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

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.wso2.carbon.core.util.CryptoException;
import org.wso2.carbon.core.util.CryptoUtil;

import java.nio.charset.StandardCharsets;

@RunWith(PowerMockRunner.class)
@PrepareForTest({CryptoUtil.class})
public class ConfigurationCryptoUtilTest {

    @Test
    public void testDecryptWithLegacyFallbackUsesDefaultProvider() throws Exception {

        PowerMockito.mockStatic(CryptoUtil.class);
        CryptoUtil cryptoUtil = Mockito.mock(CryptoUtil.class);
        PowerMockito.when(CryptoUtil.getDefaultCryptoUtil()).thenReturn(cryptoUtil);
        Mockito.when(cryptoUtil.decrypt("cipher-text".getBytes(StandardCharsets.UTF_8)))
                .thenReturn("plain-text".getBytes(StandardCharsets.UTF_8));

        String decryptedValue = ConfigurationCryptoUtil.decryptWithLegacyFallback("cipher-text");

        Assert.assertEquals("plain-text", decryptedValue);
        Mockito.verify(cryptoUtil, Mockito.never()).decrypt(Mockito.any(byte[].class), Mockito.anyString(),
                Mockito.anyString());
    }

    @Test
    public void testDecryptWithLegacyFallbackUsesLegacyProvider() throws Exception {

        PowerMockito.mockStatic(CryptoUtil.class);
        CryptoUtil cryptoUtil = Mockito.mock(CryptoUtil.class);
        PowerMockito.when(CryptoUtil.getDefaultCryptoUtil()).thenReturn(cryptoUtil);
        byte[] cipherBytes = "{\"ciphertext\":\"abc\",\"transformation\":\"RSA/ECB/OAEPwithSHA1andMGF1Padding\"}"
                .getBytes(StandardCharsets.UTF_8);
        String cipherText = new String(cipherBytes, StandardCharsets.UTF_8);
        Mockito.when(cryptoUtil.decrypt(cipherBytes)).thenThrow(new CryptoException("primary failure"));
        Mockito.when(cryptoUtil.decrypt(cipherBytes, ConfigurationCryptoUtil.LEGACY_RSA_CIPHER_TRANSFORMATION,
                ConfigurationCryptoUtil.LEGACY_KEYSTORE_INTERNAL_CRYPTO_PROVIDER))
                .thenReturn("legacy-plain".getBytes(StandardCharsets.UTF_8));

        String decryptedValue = ConfigurationCryptoUtil.decryptWithLegacyFallback(cipherText);

        Assert.assertEquals("legacy-plain", decryptedValue);
    }

    @Test
    public void testDecryptWithLegacyFallbackUsesLegacyBase64Provider() throws Exception {

        PowerMockito.mockStatic(CryptoUtil.class);
        CryptoUtil cryptoUtil = Mockito.mock(CryptoUtil.class);
        PowerMockito.when(CryptoUtil.getDefaultCryptoUtil()).thenReturn(cryptoUtil);
        byte[] cipherBytes = "legacy-base64".getBytes(StandardCharsets.UTF_8);
        Mockito.when(cryptoUtil.decrypt(cipherBytes)).thenThrow(new CryptoException("primary failure"));
        Mockito.when(cryptoUtil.decrypt(cipherBytes, ConfigurationCryptoUtil.LEGACY_RSA_CIPHER_TRANSFORMATION,
                ConfigurationCryptoUtil.LEGACY_KEYSTORE_INTERNAL_CRYPTO_PROVIDER))
                .thenThrow(new CryptoException("legacy self-contained failure"));
        Mockito.when(cryptoUtil.base64DecodeAndDecrypt("legacy-base64",
                ConfigurationCryptoUtil.LEGACY_RSA_CIPHER_TRANSFORMATION,
                ConfigurationCryptoUtil.LEGACY_KEYSTORE_INTERNAL_CRYPTO_PROVIDER))
                .thenReturn("legacy-base64-plain".getBytes(StandardCharsets.UTF_8));

        String decryptedValue = ConfigurationCryptoUtil.decryptWithLegacyFallback("legacy-base64");

        Assert.assertEquals("legacy-base64-plain", decryptedValue);
    }

    @Test(expected = CryptoException.class)
    public void testDecryptWithLegacyFallbackSkipsRsaForAesCipherText() throws Exception {

        PowerMockito.mockStatic(CryptoUtil.class);
        CryptoUtil cryptoUtil = Mockito.mock(CryptoUtil.class);
        PowerMockito.when(CryptoUtil.getDefaultCryptoUtil()).thenReturn(cryptoUtil);
        String cipherText = "{\"ciphertext\":\"abc\",\"transformation\":\"AES/GCM/NoPadding\",\"iv\":\"xyz\"}";
        byte[] cipherBytes = cipherText.getBytes(StandardCharsets.UTF_8);
        Mockito.when(cryptoUtil.decrypt(cipherBytes)).thenThrow(new CryptoException("primary failure"));

        try {
            ConfigurationCryptoUtil.decryptWithLegacyFallback(cipherText);
        } finally {
            Mockito.verify(cryptoUtil, Mockito.never()).decrypt(Mockito.any(byte[].class), Mockito.anyString(),
                    Mockito.anyString());
            Mockito.verify(cryptoUtil, Mockito.never()).base64DecodeAndDecrypt(Mockito.anyString(),
                    Mockito.anyString(), Mockito.anyString());
        }
    }

    @Test
    public void testIsLegacyRsaCipherText() {

        Assert.assertTrue(ConfigurationCryptoUtil.isLegacyRsaCipherText("YmFzZTY0LWNpcGhlcg=="));
        Assert.assertTrue(ConfigurationCryptoUtil.isLegacyRsaCipherText(
                "{\"ciphertext\":\"abc\",\"transformation\":\"RSA/ECB/OAEPwithSHA1andMGF1Padding\"}"));
        Assert.assertFalse(ConfigurationCryptoUtil.isLegacyRsaCipherText(
                "{\"ciphertext\":\"abc\",\"transformation\":\"AES/GCM/NoPadding\",\"iv\":\"xyz\"}"));
    }

    @Test(expected = CryptoException.class)
    public void testDecryptWithLegacyFallbackThrowsWhenAllStrategiesFail() throws Exception {

        PowerMockito.mockStatic(CryptoUtil.class);
        CryptoUtil cryptoUtil = Mockito.mock(CryptoUtil.class);
        PowerMockito.when(CryptoUtil.getDefaultCryptoUtil()).thenReturn(cryptoUtil);
        byte[] cipherBytes = "invalid-cipher".getBytes(StandardCharsets.UTF_8);
        CryptoException primaryException = new CryptoException("primary failure");
        Mockito.when(cryptoUtil.decrypt(cipherBytes)).thenThrow(primaryException);
        Mockito.when(cryptoUtil.decrypt(cipherBytes, ConfigurationCryptoUtil.LEGACY_RSA_CIPHER_TRANSFORMATION,
                ConfigurationCryptoUtil.LEGACY_KEYSTORE_INTERNAL_CRYPTO_PROVIDER))
                .thenThrow(new CryptoException("legacy self-contained failure"));
        Mockito.when(cryptoUtil.base64DecodeAndDecrypt("invalid-cipher",
                ConfigurationCryptoUtil.LEGACY_RSA_CIPHER_TRANSFORMATION,
                ConfigurationCryptoUtil.LEGACY_KEYSTORE_INTERNAL_CRYPTO_PROVIDER))
                .thenThrow(new CryptoException("legacy base64 failure"));

        ConfigurationCryptoUtil.decryptWithLegacyFallback("invalid-cipher");
    }
}
