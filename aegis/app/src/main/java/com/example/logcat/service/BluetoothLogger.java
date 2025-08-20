package com.example.logcat.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.content.pm.PackageManager;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;
import androidx.core.app.NotificationCompat;

import com.example.logcat.R;
import com.example.logcat.manager.HashGenerator;
import com.example.logcat.manager.LogHandler;
import com.example.logcat.manager.ServerTransmitter;

import java.io.IOException;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class BluetoothLogger extends Service {
    private static final String CHANNEL_ID = "MonitoringBluetoothServiceChannel";
    private static final String TAG = "BluetoothLoggingService";
    private String serverTimestamp;

    public HashGenerator hashGenerator;
    private ServerTransmitter serverTransmitter;
    private LogHandler logHandler;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothA2dp a2dpProfile;
    private BluetoothHeadset headsetProfile;

    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @RequiresPermission(value = "android.permission.BLUETOOTH_CONNECT")
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            // 권한 확인
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "BLUETOOTH_CONNECT permission not granted in onReceive");
                return;
            }

            try {
                if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action) || BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device != null) {

                        // 서버로 보낼거면 주석 해제
                        String logMessageForServer = (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action) ?
                                " Bluetooth connected to: " : " Bluetooth disconnected from: ")
                                + device.getName() + " [" + device.getAddress() + "]";

                        logSMSDetails(logMessageForServer);
                    } else {
                        Log.e(TAG, "Device is null on " + action);
                    }
                } else if (BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED.equals(action)) {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);

                    if (state == BluetoothA2dp.STATE_PLAYING) {
                        // 스트리밍이 시작됨
                        // 로컬 저장소에 저장할거면 주석 해제

                        // 서버로 저장할거면 주석 해제
                        String logMessageForServer = " A2DP streaming started on device: " + device.getName();
                        logSMSDetails(logMessageForServer);
                    } else if (state == BluetoothA2dp.STATE_NOT_PLAYING) {
                        // 스트리밍이 중단됨

                        // 서버로 저장할거면 주석 해제
                        String logMessageForServer = " A2DP streaming stopped on device: " + device.getName();
                        logSMSDetails(logMessageForServer);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to process Bluetooth event", e);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        createNotificationChannel();
        startForeground(1, getNotification("Monitoring Bluetooth connections..."));

        hashGenerator = new HashGenerator();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        serverTransmitter = new ServerTransmitter(this);

        logHandler = new LogHandler(this, serverTransmitter, "BluetoothLog.txt");
        logHandler.initializeLogFile();
        startBluetoothMonitoring();

        // Register for Bluetooth events
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED);
        registerReceiver(bluetoothReceiver, filter);

        Log.d(TAG, "Service created");
    }

    private void startBluetoothMonitoring() {
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth is not supported on this device");
            stopSelf();
            return;
        }

        // A2DP 프로파일 초기화
        bluetoothAdapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile == BluetoothProfile.A2DP) {
                    a2dpProfile = (BluetoothA2dp) proxy;
                }
            }

            @Override
            public void onServiceDisconnected(int profile) {
                Log.d(TAG, "A2DP profile disconnected");
            }
        }, BluetoothProfile.A2DP);

        // HEADSET 프로파일 초기화
        bluetoothAdapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile == BluetoothProfile.HEADSET) {
                    headsetProfile = (BluetoothHeadset) proxy;
                }
            }

            @Override
            public void onServiceDisconnected(int profile) {
                Log.d(TAG, "HEADSET profile disconnected");
            }
        }, BluetoothProfile.HEADSET);
    }

    private void logSMSDetails(String message) throws IOException, NoSuchAlgorithmException {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        serverTimestamp = ServerTransmitter.getServerTimestamp();
        String messageForHash = timestamp + message + " ; serverTimestamp: " + serverTimestamp +"\n";

        logHandler.appendToLogFile(messageForHash);
        Path logFilePath = logHandler.getLogFilePath();

        String hash = hashGenerator.generateSHA256HashFromFile(logFilePath);
        logHandler.updateHashFile(hash);

        String fileName = logHandler.getFilename();
        logHandler.checkFileSizeAndHandle(fileName);
    }

    private void stopBluetoothMonitoring() {
        if (a2dpProfile != null) {
            bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, a2dpProfile);
            a2dpProfile = null;
        }
        if (headsetProfile != null) {
            bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, headsetProfile);
            headsetProfile = null;
        }
        unregisterReceiver(bluetoothReceiver);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private Notification getNotification(String contentText) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Bluetooth Monitoring Service")
                .setContentText(contentText)
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Bluetooth Monitoring Service Channel",
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
        stopBluetoothMonitoring();
        Log.d(TAG, "Service destroyed");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}