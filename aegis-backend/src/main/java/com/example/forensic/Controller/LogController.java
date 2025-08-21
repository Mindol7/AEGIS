package com.example.forensic.Controller;

import com.example.forensic.Service.HashService;
import com.example.forensic.dto.LogRequest;
import com.example.forensic.Service.LogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping(value = "/logs", produces = "application/json")
public class LogController {

    private static final Logger logger = LoggerFactory.getLogger(LogController.class);

    @Autowired
    private LogService logService;

    @Autowired
    private HashService hashService;

    public LogController(LogService logService) {
        this.logService = logService;
    }

    /**
     * 로그 작성 및 해시 저장
     */
//    @PostMapping
//    public ResponseEntity<String> handleLog(@RequestBody LogRequest logRequest) {
//        try {
//            String result = logService.appendLogAndSaveHash(logRequest);
//            return ResponseEntity.ok(result);
//        } catch (IOException | NoSuchAlgorithmException e) {
//            logger.error("로그 저장 실패: {}", e.getMessage());
//            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("🚨 로그 저장 실패: " + e.getMessage());
//        }
//    }

    @PostMapping("/upload")
    public ResponseEntity<String> handleLogUpload(
            @RequestParam("logFile") MultipartFile logFile,
            @RequestParam("hashFile") MultipartFile hashFile) {  // 해시 파일을 필수로 변경
        // 로그 파일과 해시 파일 처리
        String logResult = logService.appendLogAsync(logFile, hashFile);  // appendLog 메서드에 해시 파일을 넘김

        return ResponseEntity.ok("✅ 로그 및 해시 파일 업로드 성공\n" + logResult);
    }




    /**
     * 로그 파일 조회
     */
    @GetMapping("/{deviceId}/{logType}")
    public ResponseEntity<String> getLogContents(@PathVariable String deviceId, @PathVariable String logType) {
        String fileContents = logService.readLog(deviceId, logType);
        return ResponseEntity.ok(fileContents);
    }

    /**
     * 특정 시간 범위 내에서 로그 분석
     */
    @GetMapping("/analyze/{deviceId}/{startTime}/{endTime}")
    public ResponseEntity<String> analyzeLogs(
            @PathVariable String deviceId,
            @PathVariable String startTime,
            @PathVariable String endTime) {

        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
            LocalDateTime start = LocalDateTime.parse(startTime, formatter);
            LocalDateTime end = LocalDateTime.parse(endTime, formatter);

            logger.info("📌 로그 분석 요청: Device={}, Start={}, End={}", deviceId, start, end);

            String report = String.valueOf(logService.analyzeLog(deviceId, start, end));
            return ResponseEntity.ok(report);
        } catch (Exception e) {
            logger.error("🚨 로그 분석 실패: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("🚨 잘못된 시간 형식 또는 분석 중 오류 발생: " + e.getMessage());
        }
    }

    /**
     * 로그 무결성 검증 (해시 비교)
     */
    @GetMapping("/verify/{deviceId}/{logType}/{hash}")
    public ResponseEntity<String> verifyLogIntegrity(
            @PathVariable String deviceId,
            @PathVariable String logType,
            @PathVariable String hash) {

        try {
            boolean isValid = hashService.verifyLogIntegrity(deviceId, logType, hash);

            if (isValid) {
                return ResponseEntity.ok("✅ 무결성 검증 성공: 해시가 일치합니다.");
            } else {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("❌ 무결성 검증 실패: 해시가 존재하지 않습니다.");
            }
        } catch (Exception e) {
            logger.error("🚨 로그 무결성 검증 실패: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("🚨 무결성 검증 중 오류 발생: " + e.getMessage());
        }
    }


    @GetMapping("/timestamp")
    public ResponseEntity<String> getServerTimestamp() {
        String currentTimestamp = LocalDateTime.now()
                .plusHours(9)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        return ResponseEntity.ok(currentTimestamp);
    }


//    @GetMapping("/timestamp")
//    public ResponseEntity<String> getServerTimestamp() {
//        String currentTimestamp = LocalDateTime.now()
////                .plusHours(9)
//                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
//
//        return ResponseEntity.ok(currentTimestamp);
//    }



    @DeleteMapping("/all")
    public ResponseEntity<String> deleteAllLogs() {
        logService.deleteAll();
        return ResponseEntity.ok("✅ 모든 로그가 삭제되었습니다.");
    }




}
