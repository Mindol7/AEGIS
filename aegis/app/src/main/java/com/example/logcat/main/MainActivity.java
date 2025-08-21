package com.example.logcat.main;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.logcat.service.CallingLogger;
import com.example.logcat.service.FileSystemLogger;
import com.example.logcat.service.MessageLogger;
import com.example.logcat.service.AntiForensicLogger;
import com.example.logcat.service.BluetoothLogger;
import com.example.logcat.service.AppExecutionLogger;

import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_CODE_PERMISSIONS = 101;
    private static final int REQUEST_ACCESSIBILITY_PERMISSION = 102; // 접근성 요청 코드
    private static final String TAG = "MainActivity";
    private boolean isAccessibilityRequested = false;
    private Handler accessibilityHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 권한 요청 시작
        checkAndRequestPermissions();
    }

    private void checkAndRequestPermissions() {
        String[] permissions = {
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
        };

        boolean allPermissionsGranted = true;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                allPermissionsGranted = false;
                break;
            }
        }

        if (!allPermissionsGranted) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_CODE_PERMISSIONS);
        } else {
            Log.i(TAG, "✅ 모든 권한 허용됨, 접근성 권한 확인");
            requestAccessibilityPermission();
        }
    }

    private void requestAccessibilityPermission() {
        if (isAccessibilityServiceEnabled()) {
            Log.i(TAG, "✅ 접근성 서비스 이미 활성화됨, 즉시 서비스 실행");
            startServices();
        } else {
            Toast.makeText(this, "접근성 권한이 필요합니다.", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivityForResult(intent, REQUEST_ACCESSIBILITY_PERMISSION);
            isAccessibilityRequested = true;
            startAccessibilityCheck(); // 접근성 설정을 감지하는 함수 실행
        }
    }

    /**
     * 접근성이 활성화되었는지 주기적으로 확인하는 함수
     */
    private void startAccessibilityCheck() {
        accessibilityHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isAccessibilityServiceEnabled()) {
                    Log.i(TAG, "✅ 접근성 권한 활성화 감지됨! 서비스 시작");
                    startServices();
                } else {
                    accessibilityHandler.postDelayed(this, 1000); // 1초마다 다시 체크
                }
            }
        }, 1000);
    }

    /**
     * 접근성 서비스가 활성화되었는지 확인하는 함수
     */
    private boolean isAccessibilityServiceEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        List<AccessibilityServiceInfo> enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);

        for (AccessibilityServiceInfo service : enabledServices) {
            ComponentName enabledService = ComponentName.unflattenFromString(service.getId());
            if (enabledService != null && enabledService.getPackageName().equals(getPackageName())) {
                return true; // 접근성 서비스 활성화됨
            }
        }
        return false; // 접근성 서비스 비활성화됨
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_ACCESSIBILITY_PERMISSION) {
            Log.i(TAG, "✅ 접근성 권한 설정 화면에서 돌아옴");
            startAccessibilityCheck(); // 다시 접근성 감지 시작
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            boolean allGranted = true;

            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                Log.d(TAG, "✅ 모든 권한이 허용되었습니다.");
                requestAccessibilityPermission();
            } else {
                Log.e(TAG, "❌ 권한이 거부되었습니다.");
                showPermissionDeniedDialog();
            }
        }
    }

    private void startServices() {
        startMonitoringService();
        startCallLoggingService();
        startMessageLoggingService();
        startBluetoothLoggingService();
        startRunningManager();
        startFileLoggingService();
    }

    private void startMonitoringService() {
        Intent serviceIntent = new Intent(this, AntiForensicLogger.class);
        startService(serviceIntent);
        Log.d(TAG, "✅ MonitoringService started.");
    }

    private void startCallLoggingService() {
        Intent serviceIntent = new Intent(this, CallingLogger.class);
        startService(serviceIntent);
        Log.d(TAG, "✅ CallLoggingService started.");
    }

    private void startMessageLoggingService() {
        Intent serviceIntent = new Intent(this, MessageLogger.class);
        startService(serviceIntent);
        Log.d(TAG, "✅ MessageLoggingService started.");
    }

    private void startBluetoothLoggingService() {
        Intent intent = new Intent(this, BluetoothLogger.class);
        startService(intent);
        Log.d(TAG, "✅ BluetoothLoggingService started");
    }
    private void startFileLoggingService() {
        Intent intent = new Intent(this, FileSystemLogger.class);
        startService(intent);
        Log.d(TAG, "✅ FileLoggingService started");
    }

    private void startRunningManager() {
        Intent intent = new Intent(this, AppExecutionLogger.class);
        startService(intent);
        Log.d(TAG, "✅ RunningManager started");
    }

    private void showPermissionDeniedDialog() {
        new AlertDialog.Builder(this)
                .setTitle("권한 필요")
                .setMessage("앱의 기능을 사용하려면 필요한 권한을 허용해야 합니다. 설정에서 권한을 활성화해주세요.")
                .setPositiveButton("설정으로 이동", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    Uri uri = Uri.fromParts("package", getPackageName(), null);
                    intent.setData(uri);
                    startActivity(intent);
                })
                .setNegativeButton("취소", (dialog, which) -> {
                    Toast.makeText(this, "권한이 없으면 앱을 사용할 수 없습니다.", Toast.LENGTH_SHORT).show();
                })
                .show();
    }
}
