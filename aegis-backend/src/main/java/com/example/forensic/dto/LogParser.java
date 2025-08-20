package com.example.forensic.dto;

import com.example.forensic.dto.LogRequest;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class LogParser {

    public List<LogRequest> parseLogFile(String filePath) throws IOException {
        List<LogRequest> logRequests = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new FileReader(filePath));
        String line;
        LogRequest currentLog = null;

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        while ((line = reader.readLine()) != null) {
            // 타임스탬프와 메시지를 분리하여 로그 파싱
            if (line.contains(" - [INFO]")) {
                // 기존 로그가 존재하면, 저장하고 새로운 로그 생성
                if (currentLog != null) {
                    logRequests.add(currentLog);
                }

                // 새 로그 생성 (타임스탬프와 메시지 추출)
                String[] parts = line.split(" - ");
                String timestampStr = parts[0].trim();
                String message = parts[1].trim();

                LocalDateTime createdAt = LocalDateTime.parse(timestampStr, formatter);

                currentLog = new LogRequest();
                currentLog.setCreatedAt(createdAt);
                currentLog.setMessage(message);
                currentLog.setLogType("INFO");  // 예시로 INFO로 설정, 타입 구분 추가 가능
            } else if (line.contains("serverTimestamp :")) {
                // serverTimestamp 파싱
                String serverTimestampStr = line.split(":")[1].trim();
                LocalDateTime serverTimestamp = LocalDateTime.parse(serverTimestampStr, formatter);
                if (currentLog != null) {
                    currentLog.setServerTimestamp(serverTimestamp);
                }
            } else if (line.contains("SystemClockTime:")) {
                // 다른 메시지의 경우 추가적으로 메시지 업데이트
                if (currentLog != null) {
                    currentLog.setMessage(currentLog.getMessage() + " " + line.trim());
                }
            }
        }

        // 마지막 로그 추가
        if (currentLog != null) {
            logRequests.add(currentLog);
        }

        reader.close();
        return logRequests;
    }
}
