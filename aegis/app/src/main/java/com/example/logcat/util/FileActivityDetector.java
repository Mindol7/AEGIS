package com.example.logcat.util;

import android.os.FileObserver;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

public class FileActivityDetector extends FileObserver {
    private static final String TAG = "GlobalFileMonitor";
    private final File directory;
    private OnLogMessageListener logMessageListener;

    // 🔹 중복 억제 필터용 캐시
    private final Map<String, Long> recentEventMap = new HashMap<>();
    private static final long SUPPRESSION_WINDOW_MS = 1000;  // 1초 이내 동일 이벤트 억제

    public FileActivityDetector(File path) {
        super(path, ALL_EVENTS);
        this.directory = new File(String.valueOf(path));
    }

    public interface OnLogMessageListener {
        void onLogMessageReceived(String message) throws IOException, NoSuchAlgorithmException;
    }

    public void setLogMessageListener(OnLogMessageListener listener) {
        this.logMessageListener = listener;
    }

    @Override
    public void onEvent(int event, String path) {
        if (path == null) return;

        File targetFile = new File(directory, path);

        // 디렉터리 무시
        if (targetFile.isDirectory()) return;

        if ((event & CREATE) != 0) {
            sendLog("File Created: " + targetFile.getAbsolutePath());
            Log.d(TAG, "AAA Created");
        }
        if ((event & ACCESS) != 0) {
            sendLog("File Accessed (read_from): " + targetFile.getAbsolutePath());
            Log.d(TAG, "File Created");
            Log.d(TAG, "AAA Created");
        }
        if ((event & ATTRIB) != 0) {
            sendLog("File Metadata Changed (metadata_changed): " + targetFile.getAbsolutePath());
            Log.d(TAG, "AAA Created");
        }
        if ((event & CLOSE_NOWRITE) != 0) {
            sendLog("File Closed without Writing (closed_without_writing): " + targetFile.getAbsolutePath());
            Log.d(TAG, "AAA Created");
        }
        if ((event & CLOSE_WRITE) != 0) {
            sendLog("File Closed after Writing (closed_after_write): " + targetFile.getAbsolutePath());
            Log.d(TAG, "AAA Created");
        }
        if ((event & DELETE) != 0) {
            sendLog("File Deleted (file_deleted): " + targetFile.getAbsolutePath());
            Log.d(TAG, "AAA Created");
        }
        if ((event & DELETE_SELF) != 0) {
            sendLog(" Monitored File Deleted (monitored_file_deleted): " + targetFile.getAbsolutePath());
            Log.d(TAG, "AAA Created");
        }
        if ((event & MODIFY) != 0) {
            sendLog("File Revised (written_to): " + targetFile.getAbsolutePath());
            Log.d(TAG, "AAA Created");
        }
        if ((event & MOVED_FROM) != 0) {
            sendLog("File Moved from (file_moved_here): " + targetFile.getAbsolutePath());
            Log.d(TAG, "AAA Created");
        }
        if ((event & MOVED_TO) != 0) {
            sendLog("File Moved to another location (file_moved_to_another_location): " + targetFile.getAbsolutePath());
            Log.d(TAG, "AAt Created");
        }
        if ((event & MOVE_SELF) != 0) {
            sendLog(" Monitored Path Moved (monitored_path_moved): " + targetFile.getAbsolutePath());
            Log.d(TAG, "AAA Created");
        }
        if ((event & OPEN) != 0) {
            sendLog("File Opened (file_opened): " + targetFile.getAbsolutePath());
            Log.d(TAG, "AAA Created");
        }
    }

    private void sendLog(String message) {
        long now = System.currentTimeMillis();
        Long lastTime = recentEventMap.get(message);

        if (lastTime != null && (now - lastTime < SUPPRESSION_WINDOW_MS)) {
            return;  // 최근에 동일한 메시지가 있었다면 무시
        }

        recentEventMap.put(message, now);

        Log.w(TAG, message);
        if (logMessageListener != null) {
            try {
                logMessageListener.onLogMessageReceived(message);
            } catch (IOException | NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }
    }
}