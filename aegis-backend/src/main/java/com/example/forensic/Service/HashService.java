package com.example.forensic.Service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class HashService {


    public String calculateFileHash(Path filePath) throws IOException, NoSuchAlgorithmException {

        try (InputStream inputStream = Files.newInputStream(filePath)) {
            String content = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))
                    .lines()
                    .collect(Collectors.joining("\n"));

            return calculateMessageHash(content);
        }
    }

    public String calculateFileHash(MultipartFile file) throws IOException, NoSuchAlgorithmException {
        // 파일 내용을 문자열로 읽기
        InputStream inputStream = file.getInputStream();
        String content = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))
                .lines()
                .collect(Collectors.joining("\n"));

        // 메시지 해시 계산
        return calculateMessageHash(content);
    }

    public String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }




    private String calculateMessageHash(String message) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        byte[] textBytes = message.getBytes(StandardCharsets.UTF_8);
        digest.update(textBytes);
        byte[] hashBytes = digest.digest();
        return bytesToHex(hashBytes);
    }

    public boolean verifyLogIntegrity(String deviceId, String logType, String hash) {
        Path hashFilePath = Paths.get("log", deviceId, logType, "hash.txt");

        if (!Files.exists(hashFilePath)) {
            return false;
        }

        try (Stream<String> lines = Files.lines(hashFilePath, StandardCharsets.UTF_8)) {
            return lines.anyMatch(line -> line.contains(hash));
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }



}
