package com.example.logcat.manager;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.firebase.crashlytics.buildtools.reloc.org.apache.http.client.HttpClient;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ServerTransmitter {
    private static final String TAG = "ServerManager";
    private static final String SERVER_URL = "http://220.149.236.152:52346/logs/upload";
    private static final String TIMESTAMP_URL = "http://220.149.236.152:52346/logs/timestamp";
    private static final String LINE_END = "\r\n";
    private static final String TWO_HYPHENS = "--";
    private static final String BOUNDARY = "----WebKitFormBoundary" + System.currentTimeMillis();

    private final Context context;
    private static final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());


    public ServerTransmitter(Context context) {
        this.context = context;
    }

    public void sendFilesAsync(File logFile, File hashFile, FileTransferCallback callback) {
        new Thread(() -> {
            boolean success = false;
            try {
                // 파일 업로드 로직 (예: HTTP POST 요청)
                success = uploadFilesToServer(logFile, hashFile);
            } catch (Exception e) {
                e.printStackTrace();
            }

            if (success) {
                callback.onSuccess();
            } else {
                callback.onFailure();
            }
        }).start();
    }

    public interface FileTransferCallback {
        void onSuccess();
        void onFailure();
    }

    private boolean uploadFilesToServer(File logFile, File hashFile) {
        if (logFile == null || !logFile.exists() || logFile.length() == 0 ||
                hashFile == null || !hashFile.exists() || hashFile.length() == 0) {
            Log.e(TAG, "🚨 파일이 존재하지 않거나 비어 있음");
            return false;
        }

        boolean isSuccess = false;

        try {
            // 1️⃣ 서버 연결 설정
            URL url = new URL(SERVER_URL);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoOutput(true);
            connection.setDoInput(true);
            connection.setUseCaches(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Connection", "keep-alive");
            connection.setRequestProperty("Accept", "*/*");
            connection.setRequestProperty("Accept-Encoding", "gzip, deflate, br");
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);

            // 2️⃣ 데이터 스트림 생성
            OutputStream outputStream = connection.getOutputStream();
            DataOutputStream dos = new DataOutputStream(outputStream);

            // 3️⃣ 파일 추가 (각 파일마다 MIME 타입 설정)
            writeFileToStream(dos, BOUNDARY, "logFile", logFile);
            writeFileToStream(dos, BOUNDARY, "hashFile", hashFile);

            // 4️⃣ 요청 끝
            dos.writeBytes(TWO_HYPHENS + BOUNDARY + TWO_HYPHENS + LINE_END);
            dos.flush();
            dos.close();

            // 5️⃣ 응답 받기
            int responseCode = connection.getResponseCode();
            Log.d(TAG, "📡 서버 응답 코드: " + responseCode);

            InputStream responseStream = (responseCode < HttpURLConnection.HTTP_BAD_REQUEST) ?
                    connection.getInputStream() : connection.getErrorStream();

            StringBuilder response = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(responseStream));
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();

            if (responseCode == HttpURLConnection.HTTP_OK) {
                isSuccess = true;
                Log.d(TAG, "📩 서버 응답: " + response.toString());
            } else {
                Log.e(TAG, "🚨 서버 오류 응답: " + response.toString());
            }

        } catch (Exception e) {
            Log.e(TAG, "❌ 파일 전송 중 오류 발생", e);
        }
        return isSuccess;
    }

    private static void writeFileToStream(DataOutputStream dos, String boundary, String fieldName, File file) throws Exception {
        FileInputStream fileInputStream = new FileInputStream(file);

        dos.writeBytes(TWO_HYPHENS + boundary + LINE_END);
        dos.writeBytes("Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + file.getName() + "\"" + LINE_END);

        // 파일의 MIME 타입 자동 설정
        String mimeType = URLConnection.guessContentTypeFromName(file.getName());
        if (mimeType == null) {
            mimeType = "application/octet-stream";  // 기본값 설정
        }
        dos.writeBytes("Content-Type: " + mimeType + LINE_END);
        dos.writeBytes(LINE_END);

        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = fileInputStream.read(buffer)) != -1) {
            dos.write(buffer, 0, bytesRead);
        }

        dos.writeBytes(LINE_END);
        fileInputStream.close();
    }

    public static String getServerTimestamp() {
        final String[] result = {null};
        CountDownLatch latch = new CountDownLatch(1); // 요청 완료까지 기다림

        new Thread(() -> {
            try {
                URL url = new URL(TIMESTAMP_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                int responseCode = conn.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();
                    result[0] = response.toString();
                } else {
                    System.err.println("Error: Server responded with code " + responseCode);
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                latch.countDown(); // 네트워크 요청 완료
            }
        }).start();

        try {
            latch.await(); // 요청이 끝날 때까지 대기
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        return result[0]; // 항상 정상적인 timestamp 반환
    }
}
