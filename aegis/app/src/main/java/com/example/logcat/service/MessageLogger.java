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
import android.telephony.SmsMessage;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.logcat.manager.HashGenerator;
import com.example.logcat.manager.ServerTransmitter;
import com.example.logcat.manager.LogHandler;
import com.example.logcat.R;

import java.io.IOException;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MessageLogger extends Service {
    private static final String CHANNEL_ID = "MonitoringAppServiceChannel";
    private static final String TAG = "MessageLoggingService";
    private BroadcastReceiver smsReceiver;
    private ContentObserver smsSentObserver;
    private boolean isSmsReceiverInitialized = false;
    private String lastProcessedMessage = null;
    private Uri lastProcessedUri = null;
    private long lastProcessedTimestamp = 0;
    private LogHandler logHandler;
    private HashGenerator hashGenerator;
    private ServerTransmitter serverTransmitter;
    private String serverTimestamp;

    @Override
    public void onCreate() {
        super.onCreate();

        createNotificationChannel();
        Notification notification = getNotification();
        startForeground(1, notification);

        hashGenerator = new HashGenerator();
        serverTransmitter = new ServerTransmitter(this);
        logHandler = new LogHandler(this, serverTransmitter,"MessageLog.txt");
        logHandler.initializeLogFile();

        Log.d(TAG, "Service Created");

        initializeSMSReceiver();
        initializeSMSSentObserver();
    }

    private void initializeSMSReceiver() {
        if (isSmsReceiverInitialized) {
            Log.w(TAG, "SMS Receiver is already initialized.");
            return;
        }

        smsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) {
                    Object[] pdus = (Object[]) intent.getExtras().get("pdus");
                    if (pdus != null) {
                        for (Object pdu : pdus) {
                            SmsMessage sms = SmsMessage.createFromPdu((byte[]) pdu);
                            String sender = sms.getOriginatingAddress();
                            String message = sms.getMessageBody();

                            if (isDuplicateMessage(message, System.currentTimeMillis())) {
                                Log.w(TAG, "Duplicate SMS detected. Skipping...");
                                continue;
                            }
                            try {
                                logSMSDetails(sender, message, "Received");
                            } catch (IOException | NoSuchAlgorithmException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter("android.provider.Telephony.SMS_RECEIVED");
        registerReceiver(smsReceiver, filter);
        isSmsReceiverInitialized = true;
        Log.d(TAG, "SMS Receiver initialized.");
    }

    private void initializeSMSSentObserver() {
        ContentResolver resolver = getContentResolver();
        smsSentObserver = new ContentObserver(new Handler()) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                super.onChange(selfChange, uri);

                long currentDeviceTime = System.currentTimeMillis(); // 현재 디바이스 시간 가져오기
                long startTime = currentDeviceTime - 5000; // 5초 전
                long endTime = currentDeviceTime + 5000;   // 5초 후

                try (Cursor cursor = resolver.query(
                        Uri.parse("content://sms"),
                        new String[]{"date", "type", "address", "body"},
                        "type = 2 AND date BETWEEN ? AND ?",  // 현재 디바이스 시간 기준 ±5초 범위의 메시지만 조회
                        new String[]{String.valueOf(startTime), String.valueOf(endTime)},
                        "date DESC")) {  // 최신 메시지부터 정렬

                    if (cursor != null && cursor.moveToFirst()) {
                        long messageTimestamp = cursor.getLong(cursor.getColumnIndexOrThrow("date"));
                        String address = cursor.getString(cursor.getColumnIndexOrThrow("address"));
                        String body = cursor.getString(cursor.getColumnIndexOrThrow("body"));

                        // 1️⃣ 이미 기록된 메시지는 무시 (중복 방지)
                        if (isDuplicateSentMessage(uri, body)) {
                            Log.w(TAG, "Duplicate Sent SMS detected. Skipping...");
                            return;
                        }

                        // 2️⃣ 로그 기록
                        logSMSDetails(address, body, "Sent");

                        // 3️⃣ 가장 최신 메시지를 기준으로 타임스탬프 업데이트
                        lastProcessedTimestamp = messageTimestamp;
                        lastProcessedUri = uri;
                        lastProcessedMessage = body;
                    } else {
                        Log.w(TAG, "No SMS found within the last 5 seconds.");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to retrieve sent SMS: " + e.getMessage());
                }
            }
        };

        resolver.registerContentObserver(Uri.parse("content://sms"), true, smsSentObserver);
        Log.d(TAG, "SMS Sent Observer Registered");
    }

    private boolean isDuplicateMessage(String message, long timestamp) {
        if (message.equals(lastProcessedMessage) && (timestamp - lastProcessedTimestamp < 2000)) {
            return true;
        }
        lastProcessedMessage = message;
        lastProcessedTimestamp = timestamp;
        return false;
    }

    private boolean isDuplicateSentMessage(Uri uri, String body) {
        if (lastProcessedUri != null && lastProcessedUri.equals(uri) && body.equals(lastProcessedMessage)) {
            return true;
        }
        lastProcessedUri = uri;
        lastProcessedMessage = body;
        return false;
    }

    private void logSMSDetails(String sender, String message, String type) throws IOException, NoSuchAlgorithmException {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        // 서버에 저장할거면 주석 해제
        String logMessageForServer = " SMS " + type + " to/from: " + sender + " Message: " + message;
        serverTimestamp = ServerTransmitter.getServerTimestamp();
        String messageForHash = timestamp + logMessageForServer + " ; serverTimestamp: " + serverTimestamp +"\n";

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
                .setContentText("Monitoring App Activity actions...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Monitoring Service Channel", NotificationManager.IMPORTANCE_LOW
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
        if (smsReceiver != null) {
            unregisterReceiver(smsReceiver);
        }
        if (smsSentObserver != null) {
            getContentResolver().unregisterContentObserver(smsSentObserver);
        }
        Log.d(TAG, "Service Terminated");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
