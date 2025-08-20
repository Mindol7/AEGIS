package com.example.forensic.Entity;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Getter
@Setter
@Document(collection = "logs")
public class Log {

    @Id
    private String id;
    private String deviceId;                 // 사용자별 로그 구분
    private List<Message> message;           // 메시지 목록
    private String logType;
    private String hash;
    @CreatedDate
    private LocalDateTime createdAt;
    @CreatedDate
    private LocalDateTime serverTimestamp;


    // 생성자
    public Log(String deviceId,  List<Message> message,
               String logType, String hash) {
        this.deviceId = deviceId;
        this.message = message;
        this.logType = logType;
        this.hash = hash;

    }


}
