package org.open.scdm.common.config;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.DESedeKeySpec;
import javax.crypto.spec.IvParameterSpec;

public class DES3Util {
    private final static String I_V = "01234567";

    private static String getScretKey(String secretKey) {
        StringBuilder buf = new StringBuilder(secretKey);
        int lenght = buf.length();
        if (lenght < 24) {
            int cLenght = 24 - lenght;
            for (int i = 0; i < cLenght; i++) {
                buf.append("0");
            }
        }
        return buf.toString();
    }

    public static String encode(String plainText, String secretKey) {
        try {
            DESedeKeySpec spec = new DESedeKeySpec(getScretKey(secretKey).getBytes(StandardCharsets.UTF_8));
            SecretKeyFactory keyfactory = SecretKeyFactory.getInstance("desede");
            Key deskey = keyfactory.generateSecret(spec);

            Cipher cipher = Cipher.getInstance("desede/CBC/PKCS5Padding");
            IvParameterSpec ips = new IvParameterSpec(I_V.getBytes(StandardCharsets.UTF_8));
            cipher.init(Cipher.ENCRYPT_MODE, deskey, ips);
            byte[] encryptData = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encryptData);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String decode(String encryptText, String secretKey) {
        try {
            DESedeKeySpec spec = new DESedeKeySpec(getScretKey(secretKey).getBytes(StandardCharsets.UTF_8));
            SecretKeyFactory keyfactory = SecretKeyFactory.getInstance("desede");
            Key deskey = keyfactory.generateSecret(spec);
            Cipher cipher = Cipher.getInstance("desede/CBC/PKCS5Padding");
            IvParameterSpec ips = new IvParameterSpec(I_V.getBytes(StandardCharsets.UTF_8));
            cipher.init(Cipher.DECRYPT_MODE, deskey, ips);

            byte[] decryptData = cipher.doFinal(Base64.getDecoder().decode(encryptText));
            return new String(decryptData, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
