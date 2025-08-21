package com.example.logcat.manager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.stream.Collectors;

public class HashGenerator {
    private static final String TAG = "HashManager";

    public HashGenerator() {}


    private String calculateMessageHash(String message) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        byte[] textBytes = message.getBytes(StandardCharsets.UTF_8);
        digest.update(textBytes);
        byte[] hashBytes = digest.digest();
        return bytesToHex(hashBytes);
    }
    public String generateSHA256HashFromFile(Path filePath) throws IOException, NoSuchAlgorithmException {
        try (InputStream inputStream = Files.newInputStream(filePath)) {
            String content = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))
                    .lines()
                    .collect(Collectors.joining("\n"));

            return calculateMessageHash(content);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for(byte b: bytes) {
            String hex = Integer.toHexString(0xff & b);
            if(hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }
}