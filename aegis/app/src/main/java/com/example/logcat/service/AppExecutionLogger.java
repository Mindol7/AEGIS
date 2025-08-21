package com.example.logcat.service;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.example.logcat.manager.HashGenerator;
import com.example.logcat.manager.LogHandler;
import com.example.logcat.manager.ServerTransmitter;

import java.io.IOException;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AppExecutionLogger extends AccessibilityService {
    private static final String TAG = "RunningManager";
    private HashGenerator hashGenerator;
    private ServerTransmitter serverTransmitter;
    private LogHandler logHandler;
    private String previousForegroundApp = ""; // 이전 포어그라운드 앱 저장
    private String serverTimestamp;

    @Override
    public void onCreate() {
        super.onCreate();
        hashGenerator = new HashGenerator();
        serverTransmitter = new ServerTransmitter(this);

        logHandler = new LogHandler(this, serverTransmitter, "AppExecutionLog.txt");
        logHandler.initializeLogFile();
        Log.d(TAG, "Service Created");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            Log.d(TAG, "handlwViewClicked 메소드 실행!!!");
            try {
                handleViewClicked(event);
            } catch (IOException | NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }

        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }

        String packageName = event.getPackageName() != null ? event.getPackageName().toString() : "";

        if (!packageName.isEmpty() && !packageName.equals(previousForegroundApp)) {
            // 기존 포어그라운드 앱이 변경되었을 경우 백그라운드로 전환됨을 기록
            if (!previousForegroundApp.isEmpty()) {
                String backgroundLog = " Background App: " + previousForegroundApp;
                Log.d(TAG, "백그라운드 전환 앱: " + previousForegroundApp);
                try {
                    sendLogMessage(backgroundLog);
                } catch (IOException | NoSuchAlgorithmException e) {
                    throw new RuntimeException(e);
                }
            }

            // 새로운 포어그라운드 앱 기록
            String foregroundLog = " Foreground App: " + packageName;
            Log.d(TAG, "현재 포어그라운드 앱: " + packageName);
            try {
                sendLogMessage(foregroundLog);
            } catch (IOException | NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
            previousForegroundApp = packageName;
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Accessibility Service interrupted");
    }

    private void handleViewClicked(AccessibilityEvent event) throws IOException, NoSuchAlgorithmException {
        String type = "AppRunningLog";
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());

        AccessibilityNodeInfo source = event.getSource();
        if (source == null) {
            Log.d(TAG, "루트 노드에서 가져옴");
            source = getRootInActiveWindow();
        }
        if (source != null) { // FrameLayout은 루트노드를 가져올 수 있음.
            // 텍스트 확인
            CharSequence buttonText = source.getText();
            if (buttonText == null) {
                buttonText = findTextInNode(source); // 텍스트 탐색
            }

            // 기본 정보를 로깅
            String viewId = source.getViewIdResourceName();
            CharSequence contentDescription = source.getContentDescription();
            CharSequence className = source.getClassName();
            boolean isClickable = source.isClickable();
            boolean isEnabled = source.isEnabled();
            boolean isFocusable = source.isFocusable();

            // 로그 파일로 저장
            String logMessage = "";
            if (buttonText != null) logMessage += " Text: " + buttonText;
            if (viewId != null) logMessage += " View ID: " + viewId;
            if (contentDescription != null) logMessage += " Content Description: " + contentDescription;
            if (className != null) logMessage += " Class Name: " + className;
            logMessage += " Clickable: " + isClickable + ", Enabled: " + isEnabled + ", Focusable: " + isFocusable;
            sendLogMessage(logMessage);

        } else {
            Log.d(TAG, "클릭한 UI 요소의 소스를 가져올 수 없습니다.");
        }
    }

    private String findTextInNode(AccessibilityNodeInfo node) {
        if (node == null) return null;

        // 현재 노드의 텍스트 확인
        CharSequence text = node.getText();
        if (text != null) {
            return text.toString();
        }

        CharSequence contentDescription = node.getContentDescription();
        if (contentDescription != null) {
            return contentDescription.toString();
        }

        // 부모 노드의 텍스트 확인
        AccessibilityNodeInfo parent = node.getParent();
        if (parent != null) {
            CharSequence parentText = parent.getText();
            if (parentText != null) {
                return parentText.toString();
            }
        }

        // 자식 노드 탐색
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                CharSequence childText = child.getText();
                if (childText != null) {
                    return childText.toString();
                }
            }
        }
        return null;
    }

    private void sendLogMessage(String message) throws IOException, NoSuchAlgorithmException {
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
}