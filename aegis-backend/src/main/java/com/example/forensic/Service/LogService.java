package com.example.forensic.Service;


import com.example.forensic.Entity.Log;
import com.example.forensic.Entity.Message;
import com.example.forensic.Repository.LogRepository;
import com.itextpdf.kernel.colors.DeviceGray;
import com.itextpdf.kernel.colors.Color;  // Color 클래스 import
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.*;
import org.springframework.web.multipart.MultipartFile;

@Service
public class LogService {

    @Autowired
    private LogRepository logRepository;

    @Autowired
    private HashService hashService;

    @Autowired
    private reportService reportService;


//    private static final ZoneId KST_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

//    private String formatToKST(LocalDateTime ldt) {
//        if (ldt == null) return "N/A";
//        return ldt.atZone(ZoneId.systemDefault())
//                .withZoneSameInstant(KST_ZONE)
//                .format(FORMATTER);
//    }

    private static final Pattern TIMESTAMP_PATTERN =
            Pattern.compile("^(.*?)(?:;\\s*serverTimestamp:\\s*(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}))?$");




    @Async
    public String appendLogAsync(MultipartFile logFile, MultipartFile hashFile) {
        try {
            // 1. 파일명 파싱: deviceId_logType.txt
            String originalFilename = logFile.getOriginalFilename();
            if (originalFilename == null || !originalFilename.contains("_")) {
                throw new IllegalArgumentException("파일명이 올바르지 않습니다. 형식: deviceId_logType.txt");
            }

            String[] parts = originalFilename.split("_");
            if (parts.length < 2) {
                throw new IllegalArgumentException("파일명이 올바르지 않습니다. 형식: deviceId_logType.txt");
            }

            String deviceId = parts[0];
            String logType = parts[1].replace(".txt", "");

            // 2. 해시 파일 처리
            String expectedHash = null;
            if (hashFile != null && !hashFile.isEmpty()) {
                expectedHash = new BufferedReader(new InputStreamReader(hashFile.getInputStream(), StandardCharsets.UTF_8))
                        .lines()
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("해시 파일에 내용이 없습니다."));
            }

            // 3. 실제 파일 해시 계산 및 비교
            String logFileHash = hashService.calculateFileHash(logFile);
            if (expectedHash != null && !logFileHash.equals(expectedHash)) {
                System.out.println("Hashfile의 Hash: " + expectedHash);
                System.out.println("계산된 Hash: " + logFileHash);
                throw new IllegalArgumentException("로그 파일의 해시값이 hash.txt의 해시값과 일치하지 않습니다.");
            }

            // 4. 로그 라인 읽기
            List<String> lines = new BufferedReader(new InputStreamReader(logFile.getInputStream(), StandardCharsets.UTF_8))
                    .lines()
                    .collect(Collectors.toList());

            if (lines.isEmpty()) {
                throw new IllegalArgumentException("로그 파일이 비어 있습니다.");
            }

// 5. 메시지 파싱
            List<Message> messages = new ArrayList<>();
            LocalDateTime earliestCreatedAt = null;

            for (String line : lines) {
                String[] logParts = line.split(" ", 3);
                if (logParts.length < 3) continue;

                try {
                    String dateTimeString = logParts[0] + " " + logParts[1];
                    LocalDateTime deviceTimestamp = LocalDateTime.parse(dateTimeString, FORMATTER);
                    String content = logParts[2];

                    // 괄호로 된 서버 타임스탬프가 포함된 경우 추출
                    Matcher matcher = TIMESTAMP_PATTERN.matcher(content);
                    LocalDateTime serverTimestamp = null;
                    if (matcher.matches()) {
                        String mainMessage = matcher.group(1);
                        String serverTimestampString = matcher.group(2);
                        if (serverTimestampString != null) {
                            serverTimestamp = LocalDateTime.parse(serverTimestampString, FORMATTER);
                        }
                        content = mainMessage.trim();  // 메시지에서 타임스탬프 제거
                    }

                    // Message 객체 생성
                    Message message = new Message(content, deviceTimestamp, serverTimestamp);
                    messages.add(message);

                    // 가장 빠른 생성시간 기록
                    if (earliestCreatedAt == null || deviceTimestamp.isBefore(earliestCreatedAt)) {
                        earliestCreatedAt = deviceTimestamp;
                    }

                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("날짜 형식이 올바르지 않습니다: " + logParts[0] + " " + logParts[1]);
                }
            }

            if (messages.isEmpty()) {
                throw new IllegalArgumentException("유효한 로그 메시지가 없습니다.");
            }

// 6. Log 객체 생성 및 저장
            Log log = new Log(deviceId, messages, logType, logFileHash);
            logRepository.save(log);

            System.out.println("로그 저장 완료: " + deviceId + ", " + logType);


        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }


    public String analyzeLog(String deviceId, LocalDateTime startTime, LocalDateTime endTime) throws Exception {
        List<Log> logs = logRepository.findLogsWithinDuration(deviceId, startTime, endTime);
        logs.sort(Comparator.comparing(Log::getCreatedAt));
        return reportService.generateReport(deviceId, logs, startTime, endTime);
    }



    public String readLog(String deviceId, String logType) {
        List<Log> logs = logRepository.findByDeviceIdAndLogType(deviceId, logType);
        return logs.toString();
    }




    public void deleteAll() {
        logRepository.deleteAll();
    }
}