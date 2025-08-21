package com.example.logcat.manager;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class LogHandler {
    private static final String TAG = "LogFileManager";
    private static final long MAX_LOG_FILE_SIZE = 512 * 1024; // 512BYTES
    private final String filename;
    private final String directoryName;
    private final String hashFileName;
    private final ContentResolver contentResolver;
    private final Context context;
    private File logFile;
    private File hashFile;
    private File internalDir;
    private String androidID;
    private ServerTransmitter serverTransmitter;

    // 🔹 로그 및 해시 파일을 저장하는 맵과 파일 목록
    private final Map<String, File> logFiles = new HashMap<>();
    private final Map<String, File> hashFiles = new HashMap<>();
    private final List<String> logFileNames = new ArrayList<>();
    private final List<String> hashFileNames = new ArrayList<>();

    public LogHandler(Context context, ServerTransmitter serverTransmitter, String filename) {
        this.context = context;
        this.contentResolver = context.getContentResolver();
        this.serverTransmitter = serverTransmitter;

        /*TODO
         *  1. 파일명 이름 정하기: 파일, 해시 이름 ( Android_ID_파일명.txt, Android_ID_hash.txt) - OK
         *  2. 서버로 전송되면 writable하게 설정해서 기기 내부저장소에서는 삭제하도록 하기
         *  - 링버퍼 크기만큼 차면 전송되도록 하게 하기 (크기가 다 찬 파일만)
         *  - logcat -c 감지되면 전송하도록 하게 하기
         *  - 서버 Shutdown되면 전송하도록 하게 하기
         *  3. Documents/Logs 아래 경로에는 이제 저장안되도록 하기 - OK
         *  4. ACCESS TIME 계산 인식하도록 하기
         * */
        androidID = getAndroidID(context, contentResolver);
        this.filename = androidID + "_" + filename;
        this.directoryName = filename.replace(".txt", ""); // "AntiForensic.txt" → "AntiForensic"
        this.hashFileName = androidID + "_" + directoryName + "_" + "hash.txt";

        logFileNames.add(androidID + "_AntiForensicLog.txt");
        logFileNames.add(androidID + "_AppExecutionLog.txt");
        logFileNames.add(androidID + "_BluetoothLog.txt");
        logFileNames.add(androidID + "_CallingLog.txt");
        logFileNames.add(androidID + "_MessageLog.txt");
        logFileNames.add(androidID + "_FileLog.txt");


        hashFileNames.add(androidID + "_AntiForensicLog_hash.txt");
        hashFileNames.add(androidID + "_AppExecutionLog_hash.txt");
        hashFileNames.add(androidID + "_BluetoothLog_hash.txt");
        hashFileNames.add(androidID + "_CallingLog_hash.txt");
        hashFileNames.add(androidID + "_MessageLog_hash.txt");
        hashFileNames.add(androidID + "_FileLog_hash.txt");
    }

    public static String getAndroidID(Context context, ContentResolver contentResolver) {
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID);
    }

    public void initializeLogFile() {
        // 내부 저장소 경로 설정
        internalDir = new File(context.getFilesDir(), directoryName);

        if (!internalDir.exists() && !internalDir.mkdirs()) {
            Log.e(TAG, "Failed to create log directory: " + internalDir.getAbsolutePath());
            return;
        }

        logFile = new File(internalDir, filename);
        hashFile = new File(internalDir, hashFileName);

        try {
            if (logFile.createNewFile()) {
                Log.d(TAG, "✅ 새 로그 파일이 생성됨: " + logFile.getAbsolutePath());


            } else {
                Log.d(TAG, "⚠ 로그 파일이 이미 존재함: " + logFile.getAbsolutePath());
            }
        } catch (IOException e) {
            Log.e(TAG, "❌ 로그 파일 생성 중 오류 발생: " + e.getMessage());
        }

        try {
            if (hashFile.createNewFile()) {
                Log.d(TAG, "✅ 새 로그 파일이 생성됨: " + hashFile.getAbsolutePath());
            } else {
                Log.d(TAG, "⚠ 로그 파일이 이미 존재함: " + hashFile.getAbsolutePath());
            }
        } catch (IOException e) {
            Log.e(TAG, "❌ 로그 파일 생성 중 오류 발생: " + e.getMessage());
        }
        // 🔹 맵과 목록에 추가
        logFiles.put(filename, logFile);
        hashFiles.put(hashFileName, hashFile);



        applyReadOnly(logFile);
        applyReadOnly(hashFile);
        applyReadOnlyToDirectory(internalDir);
    }

    /**
     * 로그 메시지를 해당 파일에 추가
     */
    public void appendToLogFile(String message) {
        File logFilePath = new File(logFile.getAbsolutePath());
        // 🔹 파일이 읽기 전용인지 확인하고, 쓰기 가능하도록 설정
        applyWritableToDirectory(internalDir);
        applyWritable(logFile);

        Log.d(TAG, "🔍 logFile: Writable=" + logFile.canWrite() + ", Readable=" + logFile.canRead());

        try (FileOutputStream fos = new FileOutputStream(logFilePath, true)) {
            fos.write((message).getBytes(StandardCharsets.UTF_8));
            Log.d(TAG, "INTERNAL!!!! Successfully wrote to log file: " + message);
        } catch (IOException e) {
            Log.e(TAG, "INTERNAL!!!! Error writing to log file: " + e.getMessage());
        }

        applyReadOnly(logFile);
        applyReadOnlyToDirectory(internalDir);
    }

    public Path getLogFilePath() {
        File logFilePath = new File(logFile.getAbsolutePath());
        return Path.of(logFilePath.getPath());
    }

    public String getFilename() {
        return filename;
    }

    public void updateHashFile(String hash) {
        File hashFilePath = new File(hashFile.getAbsolutePath());

        // 🔹 파일이 읽기 전용인지 확인하고, 쓰기 가능하도록 설정
        applyWritableToDirectory(internalDir);
        applyWritable(hashFile);

        Log.d(TAG, "🔍 hashFile: Writable=" + hashFile.canWrite() + ", Readable=" + hashFile.canRead());

        try (FileOutputStream fos = new FileOutputStream(hashFilePath, false)) {
            fos.write((hash + "\n").getBytes(StandardCharsets.UTF_8));
            Log.d(TAG, "✅ Successfully wrote hash to file.");
        } catch (IOException e) {
            Log.e(TAG, "❌ Error writing hash to file: " + e.getMessage());
        }

        applyReadOnly(hashFile);
        applyReadOnlyToDirectory(internalDir);
    }

    /**
     * 로그 파일 크기가 512KB 이상인지 확인 후 처리
     */
    public void checkFileSizeAndHandle(String fileName) {
        File logFile = logFiles.get(fileName);
        String pureFileName = fileName.replace(".txt", "");
        String hashFileName = pureFileName + "_hash.txt";

        if (logFile != null && logFile.length() >= MAX_LOG_FILE_SIZE) {
            Log.d(TAG, "📏 " + fileName + " 파일 크기 초과 (512KB), 서버로 전송 및 삭제 진행");
            handleFileSizeEvents(fileName + " exceeded 512KB", fileName, hashFileName);
        }
    }

    private void createNewFile(String fileName, String hashFileName) {
        applyWritableToDirectory(internalDir);
        logFile = new File(internalDir, fileName);
        hashFile = new File(internalDir, hashFileName);

        try {
            if (logFile.createNewFile()) {
                Log.d(TAG, "✅ 새 로그 파일이 생성됨: " + logFile.getAbsolutePath());


            } else {
                Log.d(TAG, "⚠ 로그 파일이 이미 존재함: " + logFile.getAbsolutePath());
            }
        } catch (IOException e) {
            Log.e(TAG, "❌ 로그 파일 생성 중 오류 발생: " + e.getMessage());
        }

        try {
            if (hashFile.createNewFile()) {
                Log.d(TAG, "✅ 새 로그 파일이 생성됨: " + hashFile.getAbsolutePath());
            } else {
                Log.d(TAG, "⚠ 로그 파일이 이미 존재함: " + hashFile.getAbsolutePath());
            }
        } catch (IOException e) {
            Log.e(TAG, "❌ 로그 파일 생성 중 오류 발생: " + e.getMessage());
        }

        applyReadOnly(logFile);
        applyReadOnly(hashFile);
        applyReadOnlyToDirectory(internalDir);
    }

    /**
     * 512KB 초과 시에는 해당 파일과 그 파일의 해시 파일만 전송 하도록 함.
     */
    public void handleFileSizeEvents(String eventType, String logFileName, String hashFileName) {
        Log.d(TAG, "🚨 중요 이벤트 감지됨: " + eventType);

        if (serverTransmitter == null) {
            Log.e(TAG, "❌ serverManager가 null입니다! 파일을 서버에 전송할 수 없습니다.");
            return;
        }

        CountDownLatch latch = new CountDownLatch(logFileNames.size()); // 모든 파일 처리될 때까지 대기

        String logFilePath = internalDir.getParent() + "/" + extractLogName(logFileName);
        File logFile = new File(logFilePath, logFileName);

            // 대응하는 hashFile 찾기
        String expectedHashFileName = logFileName.replace(".txt", "_hash.txt"); // 로그 파일명 기반으로 해시 파일명 유추
        String hashFilePath = internalDir.getParent() + "/" + extractHashName(expectedHashFileName);
        File hashFile = new File(hashFilePath, expectedHashFileName);

        Log.d(TAG, "📁 로그 파일 경로: " + logFile.getAbsolutePath());
        Log.d(TAG, "📁 해시 파일 경로: " + hashFile.getAbsolutePath());

        if (logFile.exists() && hashFile.exists()) {
            // 비동기 파일 전송 후, 완료되면 latch 카운트 감소
            serverTransmitter.sendFilesAsync(logFile, hashFile, new ServerTransmitter.FileTransferCallback() {
                @Override
                public void onSuccess() {
                    deleteLogFiles(logFileName);
                    deleteHashFiles(expectedHashFileName);
                    Log.d(TAG, "🗑 " + logFileName + " 및 " + expectedHashFileName + " 삭제 완료");
                    createNewFile(logFileName, expectedHashFileName);
                    latch.countDown(); // 전송 완료 시 카운트 감소
                }

                @Override
                public void onFailure() {
                    Log.d(TAG, "❌ " + logFileName + " 및 " + expectedHashFileName + " 파일 전송 실패로 인해 삭제하지 않음");
                    latch.countDown(); // 실패 시에도 카운트 감소
                }
            });

        } else {
            if (!logFile.exists()) {
                Log.e(TAG, "❌ " + logFileName + " 전송할 로그 파일 없음");
            }
            if (!hashFile.exists()) {
                    Log.e(TAG, "❌ " + expectedHashFileName + " 전송할 해시 파일 없음");
            }
            latch.countDown(); // 파일이 존재하지 않아도 카운트 감소 (무한 대기 방지)
        }

        Log.d(TAG, "📌 모든 파일 전송 작업 완료됨.");
    }

    /**
     * logcat -c 감지, Shutdown, Reboot 발생 시 모든 파일 전송 및 삭제
     */
    public void handleCriticalEvents(String eventType) {
        Log.d(TAG, "🚨 중요 이벤트 감지됨: " + eventType);

        if (serverTransmitter == null) {
            Log.e(TAG, "❌ serverManager가 null입니다! 파일을 서버에 전송할 수 없습니다.");
            return;
        }

        CountDownLatch latch = new CountDownLatch(logFileNames.size()); // 모든 파일 처리될 때까지 대기

        for (String fileName : logFileNames) {
            String logFilePath = internalDir.getParent() + "/" + extractLogName(fileName);
            File logFile = new File(logFilePath, fileName);

            // 대응하는 hashFile 찾기
            String expectedHashFileName = fileName.replace(".txt", "_hash.txt"); // 로그 파일명 기반으로 해시 파일명 유추
            String hashFilePath = internalDir.getParent() + "/" + extractHashName(expectedHashFileName);
            File hashFile = new File(hashFilePath, expectedHashFileName);

            Log.d(TAG, "📁 로그 파일 경로: " + logFile.getAbsolutePath());
            Log.d(TAG, "📁 해시 파일 경로: " + hashFile.getAbsolutePath());

            if (logFile.exists() && hashFile.exists()) {
                // 비동기 파일 전송 후, 완료되면 latch 카운트 감소
                serverTransmitter.sendFilesAsync(logFile, hashFile, new ServerTransmitter.FileTransferCallback() {
                    @Override
                    public void onSuccess() {
                        deleteLogFiles(fileName);
                        deleteHashFiles(expectedHashFileName);
                        Log.d(TAG, "🗑 " + fileName + " 및 " + expectedHashFileName + " 삭제 완료");
                        createNewFile(fileName, expectedHashFileName);

                        latch.countDown(); // 전송 완료 시 카운트 감소
                    }

                    @Override
                    public void onFailure() {
                        Log.d(TAG, "❌ " + fileName + " 및 " + expectedHashFileName + " 파일 전송 실패로 인해 삭제하지 않음");
                        latch.countDown(); // 실패 시에도 카운트 감소
                    }
                });

            } else {
                if (!logFile.exists()) {
                    Log.e(TAG, "❌ " + fileName + " 전송할 로그 파일 없음");
                }
                if (!hashFile.exists()) {
                    Log.e(TAG, "❌ " + expectedHashFileName + " 전송할 해시 파일 없음");
                }
                latch.countDown(); // 파일이 존재하지 않아도 카운트 감소 (무한 대기 방지)
            }
        }

        try {
            latch.await(); // 모든 파일 전송이 완료될 때까지 대기
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        Log.d(TAG, "📌 모든 파일 전송 작업 완료됨.");
    }

    /**
     * 해당 로그 파일 삭제
     */
    public synchronized void deleteLogFiles(String fileName) {
        File logFile = new File(internalDir.getParent() + "/" + extractLogName(fileName), fileName);

        applyWritableToDirectory(logFile.getParentFile());
        applyWritable(logFile);  // 🔹 삭제 전 파일을 쓰기 가능하게 변경

        if (logFile.exists() && logFile.delete()) {
            Log.d(TAG, "🗑 로그 파일 삭제됨: " + fileName);
        } else {
            Log.e(TAG, "❌ 로그 파일 삭제 실패: " + fileName);
        }
        applyReadOnlyToDirectory(logFile.getParentFile());
    }

    /**
     * 해당 해시 파일 삭제
     */
    public synchronized void deleteHashFiles(String fileName) {
        File hashFile = new File(internalDir.getParent() + "/" + extractHashName(fileName), fileName);

        applyWritableToDirectory(hashFile.getParentFile());
        applyWritable(hashFile);  // 🔹 삭제 전 파일을 쓰기 가능하게 변경

        if (hashFile.exists() && hashFile.delete()) {
            Log.d(TAG, "🗑 해시 파일 삭제됨: " + fileName);
        } else {
            Log.e(TAG, "❌ 해시 파일 삭제 실패: " + fileName);
        }
        applyReadOnlyToDirectory(hashFile.getParentFile());
    }

    private void applyReadOnly(File file) {
        if (file.exists()) {
            file.setReadable(true, false);
            file.setWritable(false, false);
            file.setExecutable(false, false);
            file.setReadOnly();
            Log.d(TAG, "🔒 파일이 읽기 전용으로 설정됨: " + file.getAbsolutePath());
        }
    }

    private void applyWritable(File file) {
        if (file.exists()) {
            boolean writable = file.setWritable(true, false);
            if (writable) {
                Log.d(TAG, "✅ 파일이 쓰기 가능하도록 설정됨: " + file.getAbsolutePath());
            } else {
                Log.e(TAG, "❌ 파일을 쓰기 가능하게 변경하지 못함! 권한 문제 발생 가능.");
            }
        }
    }

    // 🔹 Apply read-only permissions to the directory (chmod 555)
    private void applyReadOnlyToDirectory(File directory) {
        if (directory.exists()) {
            directory.setReadable(true, false);
            directory.setWritable(false, false);
            directory.setExecutable(true, false);
            Log.d(TAG, "🔒 디렉토리가 읽기 전용으로 설정됨: " + directory.getAbsolutePath());
        }
    }

    private void applyWritableToDirectory(File directory) {
        if (directory.exists()) {
            boolean readable = directory.setReadable(true, false);
            boolean writable = directory.setWritable(true, false);
            boolean executable = directory.setExecutable(true, false);

            if (readable && writable && executable) {
                Log.d(TAG, "✅ 디렉토리가 쓰기 가능하도록 설정됨: " + directory.getAbsolutePath());
            } else {
                Log.e(TAG, "❌ 디렉토리 쓰기 가능 설정 실패! 권한 문제 발생 가능: " + directory.getAbsolutePath());
            }
        } else {
            Log.e(TAG, "❌ 디렉토리가 존재하지 않음: " + directory.getAbsolutePath());
        }
    }

    private String extractLogName(String fileName) {
        if (fileName == null) return "";

        int dotIndex = fileName.lastIndexOf(".");
        if (dotIndex > 0) {
            fileName = fileName.substring(0, dotIndex);
        }

        int underscoreIndex = fileName.indexOf("_");
        if (underscoreIndex != -1) {
            return fileName.substring(underscoreIndex + 1);
        }
        return fileName;
    }

    private String extractHashName(String fileName) {
        if (fileName == null) return "";

        int dotIndex = fileName.lastIndexOf(".");

        if (dotIndex > 0) {
            fileName = fileName.substring(0, dotIndex);
        }

        String[] tokens = fileName.split("_");

        if (tokens.length >= 2) {
            return tokens[1];
        } else {
            return fileName;
        }
    }
}
