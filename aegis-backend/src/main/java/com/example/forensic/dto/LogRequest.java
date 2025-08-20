package com.example.forensic.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data  // Getter, Setter, toString, equals, hashCode 자동 생성
@NoArgsConstructor  // 기본 생성자 자동 생성
@AllArgsConstructor // 모든 필드를 포함하는 생성자 자동 생성
public class LogRequest {
    private String deviceId;  // 사용자 ID 대신 기기 ID 사용
    private LocalDateTime createdAt;
    private String message;
    private String logType;
    private String hash;
    private LocalDateTime serverTimestamp;

}
