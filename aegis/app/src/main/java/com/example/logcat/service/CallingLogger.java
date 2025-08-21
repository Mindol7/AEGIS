package com.example.logcat.service;

import android.app.Notification; // 알림바를 위한 패키지
import android.app.NotificationChannel; // 알림바를 생성하기 위한 패키지
import android.app.NotificationManager; // 알림을 표시할 지 여부 결정
import android.app.Service; // 백그라운드에서 작업을 수행하게 함
import android.content.BroadcastReceiver; // 브로드캐스트 수신
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter; // 반응할 특정 이벤트 필터
import android.os.Build;
import android.os.IBinder; // 바인딩된 데이터 반환
import android.telephony.PhoneStateListener; // 전화 상태 모니터링
import android.telephony.TelephonyManager; // 기기의 전화 상태, 네트워크 상태, 데이터 활성화 여부 확인 간으
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.logcat.manager.HashGenerator;
import com.example.logcat.manager.LogHandler;
import com.example.logcat.manager.ServerTransmitter;
import com.example.logcat.R; // 리소스 파일

import java.io.IOException;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CallingLogger extends Service {
    private static final String CHANNEL_ID = "MonitoringAppServiceChannel";
    private static final String TAG = "CallLoggingService";
    private TelephonyManager telephonyManager;
    @SuppressWarnings("deprecation")
    private PhoneStateListener phoneStateListener;
    private BroadcastReceiver outgoingCallReceiver;

    private String lastDialedNumber;
    private String incomingCallNumber;
    private String serverTimestamp;
    private long callStartTime;
    private LogHandler logHandler;
    private HashGenerator hashGenerator;
    private ServerTransmitter serverTransmitter;

    /* TODO: 번호를 해시로 보호 하기 ?
    *
    * */

    @Override
    public void onCreate() {
        super.onCreate();

        createNotificationChannel();
        Notification notification = getNotification();
        startForeground(1, notification);

        hashGenerator = new HashGenerator();
        serverTransmitter = new ServerTransmitter(this);
        logHandler = new LogHandler(this, serverTransmitter,"CallingLog.txt");
        logHandler.initializeLogFile();
        Log.d(TAG, "서비스 생성됨");

        initializePhoneStateListener();
        initializeOutgoingCallReceiver();
    }

    @SuppressWarnings("deprecation")
    private void initializePhoneStateListener() { // call 함수 호출
        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        phoneStateListener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String phoneNumber) { // 변화가 있을 때 호출됨
                Log.d(TAG, "onCallStateChanged: state=" + state + ", phoneNumber=" + phoneNumber);
                lastDialedNumber = phoneNumber;
                switch (state) {
                    case TelephonyManager.CALL_STATE_IDLE: // 통화 종료 또는 전화 받지 않음
                        Log.d(TAG, "CALL_STATE_IDLE: End the call or not answer the phone");
                        if (callStartTime > 0) {
                            long callEndTime = System.currentTimeMillis();
                            if (lastDialedNumber != null) { // 발신 통화 종료
                                try {
                                    logCallDetails(lastDialedNumber, callStartTime, callEndTime, "Termination of the call");
                                } catch (IOException | NoSuchAlgorithmException e) {
                                    throw new RuntimeException(e);
                                }
                            } else if (incomingCallNumber != null) { // 수신 통화 종료
                                try {
                                    logCallDetails(incomingCallNumber, callStartTime, callEndTime, "Termination of the call");
                                } catch (IOException | NoSuchAlgorithmException e) {
                                    throw new RuntimeException(e);
                                }
                            }
                            callStartTime = 0;
                            lastDialedNumber = null;
                            incomingCallNumber = null;
                        } else if (incomingCallNumber != null) {
                            // 수신 전화 거절 또는 받지 않음
                            try {
                                logCallDetails(incomingCallNumber, 0, 0, "Refuse incoming calls or don't answer");
                            } catch (IOException | NoSuchAlgorithmException e) {
                                throw new RuntimeException(e);
                            }
                            incomingCallNumber = null;
                        }
                        break;

                    case TelephonyManager.CALL_STATE_OFFHOOK: // 통화 연결
                        Log.d(TAG, "CALL_STATE_OFFHOOK: 통화 연결");
                        callStartTime = System.currentTimeMillis();
                        if (incomingCallNumber != null) {
                            try {
                                logCallDetails(incomingCallNumber, callStartTime, 0, "start an incoming call");
                            } catch (IOException | NoSuchAlgorithmException e) {
                                throw new RuntimeException(e);
                            }
                        } else if (lastDialedNumber != null) {
                            try {
                                logCallDetails(lastDialedNumber, callStartTime, 0, "start an outgoing call");
                            } catch (IOException | NoSuchAlgorithmException e) {
                                throw new RuntimeException(e);
                            }
                        }
                        break;

                    case TelephonyManager.CALL_STATE_RINGING: // 수신 전화
                        Log.d(TAG, "CALL_STATE_RINGING: 전화 울림");
                        if (phoneNumber != null) {
                            incomingCallNumber = phoneNumber;
                            try {
                                logCallDetails(incomingCallNumber, 0, 0, "Ringing an incoming call");
                            } catch (IOException | NoSuchAlgorithmException e) {
                                throw new RuntimeException(e);
                            }
                        }
                        break;
                }
            }
        };

        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE); // 이거 덕분에 listen 할 수 있음
    }

    @SuppressWarnings("deprecation")
    private void initializeOutgoingCallReceiver() {
        outgoingCallReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) { // 이벤트가 발생하면 호출됨
                if (Intent.ACTION_NEW_OUTGOING_CALL.equals(intent.getAction())) {
                    lastDialedNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER);
                    Log.d(TAG, "발신 전화: " + lastDialedNumber);
                    try {
                        logCallDetails(lastDialedNumber, 0, 0, "Ringing an outgoing call");
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    } catch (NoSuchAlgorithmException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter(Intent.ACTION_NEW_OUTGOING_CALL);
        registerReceiver(outgoingCallReceiver, filter);
    }

    private void logCallDetails(String number, long startTime, long endTime, String callType) throws IOException, NoSuchAlgorithmException {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        String startTimeFormatted = startTime > 0
                ? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(startTime))
                : "N/A";
        String endTimeFormatted = endTime > 0
                ? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(endTime))
                : "N/A";
        long durationSeconds = (endTime > 0 && startTime > 0) ? (endTime - startTime) / 1000 : 0;


        // 서버에 저장할거면 주석 해제
        String message = " Call Type: " + callType
                + " Number: " + number
                + " Start Time: " + startTimeFormatted
                + " End Time: " + endTimeFormatted
                + " Duration: " + durationSeconds + " seconds";
        serverTimestamp = ServerTransmitter.getServerTimestamp();
        String messageForHash = timestamp + message + " ; serverTimestamp: " + serverTimestamp +"\n";;

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

    @SuppressWarnings("deprecation")
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (telephonyManager != null && phoneStateListener != null) {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
        }
        if (outgoingCallReceiver != null) {
            unregisterReceiver(outgoingCallReceiver);
        }
        Log.d(TAG, "서비스 종료됨");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}