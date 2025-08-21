package com.example.logcat.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.logcat.manager.HashGenerator;
import com.example.logcat.manager.LogHandler;
import com.example.logcat.manager.ServerTransmitter;
import com.example.logcat.R;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AntiForensicLogger extends Service {
    private static final String CHANNEL_ID = "MonitoringServiceChannel";
    private BroadcastReceiver timeChangeReceiver;
    private String serverTimestamp;
    private final Handler handler = new Handler();
    private long lastCheckedTime = System.currentTimeMillis();
    private LogHandler logHandler;
    private HashGenerator hashGenerator;
    private ServerTransmitter serverTransmitter;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d("MonitoringService", "Service started.");

        createNotificationChannel();
        Notification notification = getNotification();
        startForeground(1, notification);

        hashGenerator = new HashGenerator();
        serverTransmitter = new ServerTransmitter(this);
        logHandler = new LogHandler(this, serverTransmitter, "AntiForensicLog.txt");
        logHandler.initializeLogFile();

        // Monitoring anti-forensic actions
        monitorAntiForensicActions();
        monitorShutdownAndReboot();
        monitoringLogcatClear();
    }

    /* Timestamp Change 감지 */
    private void monitorAntiForensicActions() {
        timeChangeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();

                if (Intent.ACTION_TIME_CHANGED.equals(action) || Intent.ACTION_DATE_CHANGED.equals(action)) {
                    // Check if auto time setting is enabled
                    boolean isAutoTimeEnabled = android.provider.Settings.Global.getInt(
                            getContentResolver(),
                            android.provider.Settings.Global.AUTO_TIME,
                            0
                    ) == 1;

                    String logMessageForServer = " Anti-forensic event detected: " + action;
                    try {
                        sendLogMessage(logMessageForServer);
                    } catch (IOException | NoSuchAlgorithmException e) {
                        throw new RuntimeException(e);
                    }
                    logMessageForServer = " SystemClockTime: Setting time of day to sec=" + System.currentTimeMillis();
                    try {
                        sendLogMessage(logMessageForServer);
                    } catch (IOException | NoSuchAlgorithmException e) {
                        throw new RuntimeException(e);
                    }
                    logMessageForServer = " Auto time setting enabled: " + isAutoTimeEnabled;
                    try {
                        sendLogMessage(logMessageForServer);
                    } catch (IOException | NoSuchAlgorithmException e) {
                        throw new RuntimeException(e);
                    }
                    logMessageForServer = " Before System Time : " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(lastCheckedTime);
                    try {
                        sendLogMessage(logMessageForServer);
                    } catch (IOException | NoSuchAlgorithmException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_DATE_CHANGED);
        registerReceiver(timeChangeReceiver, filter);

        // Schedule periodic time check
        schedulePeriodicTimeCheck();
    }

    /* 전원 꺼짐 및 재부팅 감지 */
    private void monitorShutdownAndReboot() {
        BroadcastReceiver shutdownReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                String logMessageForServer = "";

                if (Intent.ACTION_SHUTDOWN.equals(action) || Intent.ACTION_REBOOT.equals(action)) {
                    // 서버로 보낼거면 주석 해제
                    String logMessage = " Device Shutdown or Reboot Detected.";
                    try {
                        sendLogMessage(logMessage);
                    } catch (IOException | NoSuchAlgorithmException e) {
                        throw new RuntimeException(e);
                    }
                    logHandler.handleCriticalEvents("Shutdown or Reboot detected");
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SHUTDOWN);
        filter.addAction(Intent.ACTION_REBOOT);
        registerReceiver(shutdownReceiver, filter);
    }

    private void schedulePeriodicTimeCheck() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                long currentTime = System.currentTimeMillis();
                lastCheckedTime = currentTime;
                handler.postDelayed(this, 1000); // Re-run every second
            }
        }, 1000);
    }

    /* logcat -c 탐지 */
    private void monitoringLogcatClear() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                boolean isLogcatCleared = isLogBufferCleared();
                if (isLogcatCleared) {
                    // 서버로 보낼거면 주석 해제
                    String logMessage = " Log Buffer Cleared Detected. (adb logcat -c)";
                    try {
                        sendLogMessage(logMessage);
                    } catch (IOException | NoSuchAlgorithmException e) {
                        throw new RuntimeException(e);
                    }
                    logHandler.handleCriticalEvents("logcat -c detected");
                }
                handler.postDelayed(this, 5000); // 5초 마다 확인
            }
        }, 5000);
    }

    private boolean isLogBufferCleared() {
        try {
            Process process = Runtime.getRuntime().exec("logcat -d -t 1"); // logcat을 실행할 수 있는 건가?
            int exitValue = process.waitFor();

            if (exitValue == 0) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line = reader.readLine();
                    return line == null || line.isEmpty();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    private void sendLogMessage(String message) throws IOException, NoSuchAlgorithmException {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        serverTimestamp = ServerTransmitter.getServerTimestamp();

        String messageForHash = timestamp + message + " ; serverTimestamp: " + serverTimestamp +"\n";
        /*
        * TODO: 개별적으로 구분되는 파일이름으로 변경해야함.
        *  해시는 파일 자체에 대해 계산해야함. (파일의 경로를 가져옴 -> 텍스트로 변환함 -> 해시 계산함.)
        *  즉, 먼저 텍스트를 파일에 저장하고 해시 계산한걸 따로 저장해준다.
        *  그리고, hash.txt에는 기존의 것을 append하지 말고 최신값으로 update해줘야함.
        * */

        logHandler.appendToLogFile(messageForHash);
        Path logFilePath = logHandler.getLogFilePath();

        String hash = hashGenerator.generateSHA256HashFromFile(logFilePath);
        logHandler.updateHashFile(hash);

        String fileName = logHandler.getFilename();
        logHandler.checkFileSizeAndHandle(fileName);
    }



    private Notification getNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Monitoring Service")
                .setContentText("Monitoring anti-forensic actions...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Monitoring Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (timeChangeReceiver != null) {
            unregisterReceiver(timeChangeReceiver);
        }
        handler.removeCallbacksAndMessages(null); // Stop periodic checks
        Log.d("MonitoringService", "Service stopped.");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}