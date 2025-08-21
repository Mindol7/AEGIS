package com.example.logcat.service;

import android.app.Service;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Log;

import com.example.logcat.util.FileActivityDetector;
import com.example.logcat.manager.HashGenerator;
import com.example.logcat.manager.LogHandler;
import com.example.logcat.manager.ServerTransmitter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class FileSystemLogger extends Service {
    private static final String TAG = "FileLoggingService";

    private HashGenerator hashGenerator;
    private LogHandler logHandler;
    private ContentObserver mediaStoreObserver;
    private ServerTransmitter serverTransmitter;
    private String serverTimestamp;

    // 여러 디렉토리 감시용
    private final List<FileActivityDetector> observers = new ArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();

        hashGenerator = new HashGenerator();
        serverTransmitter = new ServerTransmitter(this);
        logHandler = new LogHandler(this, serverTransmitter, "FileLog.txt");
        logHandler.initializeLogFile();

        // 외부 저장소 루트부터 재귀적으로 감시 시작
        File rootDirectory = new File("/storage/emulated/0/");
        watchAllRecursively(rootDirectory);

        Log.d(TAG, "FileLoggingService started and monitoring file system recursively.");


        monitorMediaStoreChanges();
        monitorExternalStorageChanges();
    }

    /* Media File Change 감지 */
    private void monitorExternalStorageChanges() {
        ContentResolver resolver = getContentResolver();
        Uri externalUri = MediaStore.Files.getContentUri("external");

        ContentObserver externalObserver = new ContentObserver(new Handler()) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                super.onChange(selfChange, uri);

                if (uri != null) {
                    String fileDetails = getFileDetailsFromUri(uri);

                    // Documents/Logs 폴더는 제외
                    if (fileDetails != null && !fileDetails.contains("Documents/Logs")) {
                        String logMessageForServer = "File change detected: " + fileDetails;
                        try {
                            sendLogMessage(logMessageForServer);
                        } catch (IOException | NoSuchAlgorithmException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            }
        };

        resolver.registerContentObserver(externalUri, true, externalObserver);
        Log.d("MonitoringService", "External storage change observer registered");
    }

    private String getFileDetailsFromUri(Uri uri) {
        ContentResolver resolver = getContentResolver();
        StringBuilder fileDetails = new StringBuilder();

        String[] projection = {
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.RELATIVE_PATH
        };

        try (Cursor cursor = resolver.query(uri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                // 파일 이름 가져오기
                int nameIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME);
                if (nameIndex >= 0) {
                    String fileName = cursor.getString(nameIndex);
                    fileDetails.append("Name: ").append(fileName).append(", ");
                } else {
                    fileDetails.append("Name: Unknown, ");
                }

                // 상대 경로 가져오기
                int pathIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH);
                if (pathIndex >= 0) {
                    String relativePath = cursor.getString(pathIndex);
                    fileDetails.append("Path: ").append(relativePath);
                } else {
                    fileDetails.append("Path: Unknown");
                }
            } else {
                // Cursor가 비어 있을 때 URI만 기록
                fileDetails.append("Uri: ").append(uri.toString());
            }
        } catch (Exception e) {
            Log.e("MonitoringService", "Error retrieving file details: " + e.getMessage());
            fileDetails.append("Error retrieving details for Uri: ").append(uri.toString());
        }

        return fileDetails.toString();
    }

    private void monitorMediaStoreChanges() {
        ContentResolver resolver = getContentResolver();
        Uri mediaUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

        mediaStoreObserver = new ContentObserver(new Handler()) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                super.onChange(selfChange, uri);

                if (uri != null) {
                    String logMessageForServer = getMediaDetails(uri);
                    try {
                        sendLogMessage(logMessageForServer);
                    } catch (IOException | NoSuchAlgorithmException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        };
        resolver.registerContentObserver(mediaUri, true, mediaStoreObserver);
        Log.d("MonitoringService", "MediaStore change observer registered");
    }

    private String getMediaDetails(Uri uri) {
        ContentResolver resolver = getContentResolver();

        // 서버로 보낼거면 주석 해제
        String logMessage = "MediaStore changed: " + uri;

        // 명시적으로 필요한 필드 요청
        String[] projection = {
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.DATE_TAKEN // 사진이 찍힌 날짜와 시간
        };

        try (Cursor cursor = resolver.query(uri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                // DISPLAY_NAME 필드로 파일 이름 확인
                int displayNameIndex = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME);
                if (displayNameIndex >= 0) {
                    String fileName = cursor.getString(displayNameIndex);
                    logMessage += "File Name (DISPLAY_NAME): " + fileName;
                } else {
                    logMessage += "File Name column not found\n";
                }

                // RELATIVE_PATH 필드로 파일 경로 확인
                int relativePathIndex = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH);
                if (relativePathIndex >= 0) {
                    String relativePath = cursor.getString(relativePathIndex);
                    logMessage += "Relative Path: " + relativePath;
                } else {
                    logMessage += "Relative Path column not found\n";
                }

                // DATE_TAKEN 필드로 사진이 찍힌 날짜와 시간 확인
                int dateTakenIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN);
                if (dateTakenIndex >= 0) {
                    long dateTaken = cursor.getLong(dateTakenIndex);
                    logMessage += "Modifed After Date: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(dateTaken));
                } else {
                    logMessage += "Date Taken column not found\n";
                }
            } else {
                logMessage += "Cursor is null or no data found for URI\n";
            }
        } catch (Exception e) {
            e.printStackTrace();
            logMessage += "Error retrieving media details: " + e.getMessage();
        }

        return logMessage;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        for (FileActivityDetector observer : observers) {
            observer.stopWatching();
        }
        observers.clear();
        Log.d(TAG, "FileLoggingService stopped all observers.");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * 재귀적으로 하위 모든 디렉토리를 감시
     */
    private void watchAllRecursively(File dir) {
        if (dir == null || !dir.isDirectory()) return;

        // 숨김 디렉터리는 감시 제외
        if (dir.getName().startsWith(".")) return;

        FileActivityDetector observer = new FileActivityDetector(dir);
        observer.setLogMessageListener(this::sendLogMessage);
        observer.startWatching();
        observers.add(observer);

        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    watchAllRecursively(child);
                }
            }
        }
    }

    /**
     * FileActivityDetector로부터 전달받은 로그 메시지를 처리
     */
    private void sendLogMessage(String message) throws IOException, NoSuchAlgorithmException {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        serverTimestamp = ServerTransmitter.getServerTimestamp();

        String messageForLog = timestamp + " " + message + " ; serverTimestamp: " + serverTimestamp + "\n";

        logHandler.appendToLogFile(messageForLog);

        Path logFilePath = logHandler.getLogFilePath();
        String hash = hashGenerator.generateSHA256HashFromFile(logFilePath);
        logHandler.updateHashFile(hash);

        logHandler.checkFileSizeAndHandle(logHandler.getFilename());
    }
}
