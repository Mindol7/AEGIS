package com.example.logcat.manager;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;

import com.example.logcat.queue.OfflineLogDatabase;
import com.example.logcat.queue.OfflineLogEntity;
import com.example.logcat.queue.UploadQueueWorker;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.CertificatePinner;
import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 서버 전송 관리자.
 * - OkHttp + mTLS (클라이언트 인증서)
 * - SPKI 핀닝 (SHA256 핀)
 * - ClientCryptoPipeline으로 E2E 암호화 후 전송
 * - 네트워크 실패 시 SQLCipher 오프라인 큐에 저장 → WorkManager 재시도
 */
public class ServerTransmitter {

    private static final String TAG = "ServerTransmitter";
    private static final String BASE_URL = "***"; // Server

    public static final long MAX_OFFLINE_QUEUE_BYTES = 50L * 1024 * 1024; // 50 MB
    private static final String UPLOAD_PATH = "/logs/upload";
    private static final String TIMESTAMP_PATH = "/logs/timestamp";
    private static final String SERVERKEY_PATH = "/logs/serverkey";

    private static final String SPKI_PIN_PRIMARY = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String SPKI_PIN_BACKUP  = "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=";

    private final Context context;
    private volatile OkHttpClient httpClient;
    private volatile ClientCryptoPipeline cryptoPipeline;

    // 서버 공개키는 앱 생명주기 동안 불변 — static으로 인스턴스 간 공유
    // (UploadQueueWorker가 new ServerTransmitter()할 때마다 재fetch 방지)
    private static volatile byte[] cachedServerPublicKey;
    private static volatile long lastKeyFetchFailedAt = 0;
    private static final long KEY_FETCH_RETRY_INTERVAL_MS = 30_000;

    private int consecutiveFailures = 0;
    private static final int FAILURE_THRESHOLD = 3;

    public ServerTransmitter(Context context) {
        this.context = context;
    }

    // ──────────────────────────────────────────────
    // OkHttp 클라이언트 초기화 (mTLS + SPKI 핀닝)
    // ──────────────────────────────────────────────

    private synchronized OkHttpClient getHttpClient() {
        if (httpClient != null) return httpClient;
        try {
            if (BASE_URL.startsWith("http://")) {
                Log.d(TAG, "[HTTP] 클라이언트 초기화 (평문 HTTP, 개발 모드)");
                httpClient = new OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(20, TimeUnit.SECONDS)
                        .writeTimeout(20, TimeUnit.SECONDS)
                        .connectionPool(new ConnectionPool(6, 30, TimeUnit.SECONDS))
                        .retryOnConnectionFailure(true)
                        .build();
                return httpClient;
            }

            Log.d(TAG, "[HTTP] mTLS 클라이언트 초기화 시작");
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, null);

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);

            SSLContext sslCtx = SSLContext.getInstance("TLSv1.3");
            TrustManager[] trustManagers = tmf.getTrustManagers();
            sslCtx.init(kmf.getKeyManagers(), trustManagers, new SecureRandom());

            X509TrustManager trustManager = (X509TrustManager) trustManagers[0];

            CertificatePinner pinner = new CertificatePinner.Builder()
                    .add("220.149.236.152", SPKI_PIN_PRIMARY)
                    .add("220.149.236.152", SPKI_PIN_BACKUP)
                    .build();

            httpClient = new OkHttpClient.Builder()
                    .sslSocketFactory(sslCtx.getSocketFactory(), trustManager)
                    .certificatePinner(pinner)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build();

            Log.d(TAG, "[HTTP] mTLS 클라이언트 초기화 완료");
            return httpClient;
        } catch (Exception e) {
            Log.e(TAG, "[HTTP] 클라이언트 초기화 실패", e);
            throw new RuntimeException("HTTP client init failed", e);
        }
    }

    // ──────────────────────────────────────────────
    // 서버 공개키 획득 및 CryptoPipeline 초기화
    // ──────────────────────────────────────────────

    private synchronized ClientCryptoPipeline getCryptoPipeline() {
        if (cryptoPipeline != null) {
            Log.d(TAG, "[CRYPTO] 파이프라인 캐시 사용");
            return cryptoPipeline;
        }

        long now = System.currentTimeMillis();
        long elapsed = now - lastKeyFetchFailedAt;
        if (lastKeyFetchFailedAt > 0 && elapsed < KEY_FETCH_RETRY_INTERVAL_MS) {
            Log.w(TAG, "[CRYPTO] serverkey 재시도 대기 중 (남은 시간: "
                    + (KEY_FETCH_RETRY_INTERVAL_MS - elapsed) / 1000 + "초) → 오프라인 큐로 전환");
            return null;
        }

        try {
            Log.d(TAG, "[CRYPTO] 파이프라인 초기화 시작"
                    + (cachedServerPublicKey != null ? " (서버키 캐시 있음)" : " (서버키 fetch 필요)"));
            long t0 = System.currentTimeMillis();
            byte[] serverKey = fetchServerPublicKey();
            Log.d(TAG, "[CRYPTO] 서버키 획득 완료 (" + (System.currentTimeMillis() - t0) + "ms)");

            String deviceId = LogHandler.getAndroidID(context, context.getContentResolver());
            cryptoPipeline = new ClientCryptoPipeline(serverKey, deviceId);
            lastKeyFetchFailedAt = 0;
            Log.d(TAG, "[CRYPTO] 파이프라인 초기화 완료 (deviceId=" + deviceId + ")");
            return cryptoPipeline;
        } catch (Exception e) {
            Log.e(TAG, "[CRYPTO] 파이프라인 초기화 실패 — 원인: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage()
                    + (cachedServerPublicKey != null ? " (서버키 캐시 유지)" : " (서버키 없음)"));
            cryptoPipeline = null;
            lastKeyFetchFailedAt = System.currentTimeMillis();
            return null;
        }
    }

    private byte[] fetchServerPublicKey() throws Exception {
        if (cachedServerPublicKey != null) {
            Log.d(TAG, "[SERVERKEY] 캐시된 서버 공개키 사용 (재요청 없음)");
            return cachedServerPublicKey;
        }
        Log.d(TAG, "[SERVERKEY] 서버에서 공개키 요청: " + BASE_URL + SERVERKEY_PATH);
        long t0 = System.currentTimeMillis();
        OkHttpClient keyClient = getHttpClient().newBuilder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        Request req = new Request.Builder()
                .url(BASE_URL + SERVERKEY_PATH)
                .get()
                .build();
        try (Response resp = keyClient.newCall(req).execute()) {
            long elapsed = System.currentTimeMillis() - t0;
            if (!resp.isSuccessful() || resp.body() == null) {
                Log.e(TAG, "[SERVERKEY] 응답 실패: HTTP " + resp.code() + " (" + elapsed + "ms)");
                throw new RuntimeException("Server key fetch failed: " + resp.code());
            }
            String b64 = resp.body().string().trim();
            cachedServerPublicKey = Base64.decode(b64, Base64.NO_WRAP);
            Log.d(TAG, "[SERVERKEY] 공개키 수신 성공 (" + elapsed + "ms, " + cachedServerPublicKey.length + "bytes)");
            return cachedServerPublicKey;
        }
    }

    // ──────────────────────────────────────────────
    // 핵심 업로드 메서드
    // ──────────────────────────────────────────────

    public void sendLogAsync(String deviceId, String logType,
                              String logContent, String chainHash,
                              FileTransferCallback callback) {
        Log.d(TAG, "[ASYNC] 비동기 전송 시작: " + logType + " (contentLen=" + logContent.length() + ")");
        new Thread(() -> {
            boolean success = false;
            try {
                success = uploadEncryptedLog(deviceId, logType, logContent, chainHash);
            } catch (Exception e) {
                Log.e(TAG, "[ASYNC] 예외 발생, 오프라인 큐로 전환: " + logType, e);
            }
            if (success) {
                Log.d(TAG, "[ASYNC] 전송 성공: " + logType);
                if (callback != null) callback.onSuccess();
            } else {
                Log.w(TAG, "[ASYNC] 전송 실패, 오프라인 큐 저장: " + logType);
                queueOffline(deviceId, logType, logContent, chainHash);
                UploadQueueWorker.scheduleFlush(context);
                if (callback != null) callback.onFailure();
            }
        }).start();
    }

    public boolean uploadEncryptedLog(String deviceId, String logType,
                                       String logContent, String chainHash) {
        byte[] packetBytes = null;
        long t0 = System.currentTimeMillis();
        Log.d(TAG, "[UPLOAD] 시작: type=" + logType
                + " contentLen=" + logContent.length()
                + " thread=" + Thread.currentThread().getName());
        try {
            // 1. CryptoPipeline 획득
            ClientCryptoPipeline pipeline = getCryptoPipeline();
            if (pipeline == null) {
                Log.w(TAG, "[UPLOAD] CryptoPipeline 없음 → 오프라인 큐로 전환: " + logType);
                return false;
            }

            // 2. 암호화 (GZIP + ECDH + AES-GCM + ECDSA)
            long encStart = System.currentTimeMillis();
            packetBytes = pipeline.encrypt(logContent);
            Log.d(TAG, "[UPLOAD] 암호화 완료: " + (System.currentTimeMillis() - encStart) + "ms"
                    + ", 패킷=" + packetBytes.length + "bytes");

            // 3. SHA-256 해시 계산
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(logContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) sb.append(String.format("%02x", b));
            String contentHash = sb.toString();
            Log.d(TAG, "[UPLOAD] 해시 계산 완료: " + contentHash.substring(0, 16) + "...");

            // 4. 멀티파트 요청 구성
            String filename = deviceId + "_" + logType + ".enc";
            RequestBody logBody = RequestBody.create(packetBytes,
                    MediaType.parse("application/octet-stream"));
            RequestBody hashBody = RequestBody.create(contentHash.getBytes(),
                    MediaType.parse("text/plain"));
            RequestBody multipart = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("logFile", filename, logBody)
                    .addFormDataPart("hashFile", deviceId + "_" + logType + "_hash.txt", hashBody)
                    .build();

            Request req = new Request.Builder()
                    .url(BASE_URL + UPLOAD_PATH)
                    .addHeader("Connection", "close")
                    .post(multipart)
                    .build();

            Log.d(TAG, "[UPLOAD] HTTP POST 전송: " + BASE_URL + UPLOAD_PATH + " file=" + filename);
            long netStart = System.currentTimeMillis();
            try (Response resp = getHttpClient().newCall(req).execute()) {
                long netMs = System.currentTimeMillis() - netStart;
                boolean ok = resp.isSuccessful();
                Log.d(TAG, "[UPLOAD] 응답: HTTP " + resp.code()
                        + " (" + netMs + "ms) " + logType
                        + (ok ? " → 성공" : " → 실패"));
                if (ok) {
                    onUploadSuccess();
                    Log.d(TAG, "[UPLOAD] 완료: " + logType + " 총 소요=" + (System.currentTimeMillis() - t0) + "ms");
                } else {
                    Log.w(TAG, "[UPLOAD] 서버 거부: HTTP " + resp.code() + " type=" + logType);
                }
                return ok;
            }
        } catch (Exception e) {
            Log.e(TAG, "[UPLOAD] 실패: type=" + logType
                    + " 소요=" + (System.currentTimeMillis() - t0) + "ms"
                    + " 원인=" + e.getClass().getSimpleName() + ": " + e.getMessage());
            onUploadFailure();
            return false;
        } finally {
            if (packetBytes != null) Arrays.fill(packetBytes, (byte) 0);
        }
    }

    private synchronized void onUploadFailure() {
        consecutiveFailures++;
        Log.w(TAG, "[UPLOAD] 연속 실패 횟수: " + consecutiveFailures + "/" + FAILURE_THRESHOLD);
        if (consecutiveFailures >= FAILURE_THRESHOLD) {
            if (httpClient != null) {
                int evicted = httpClient.connectionPool().connectionCount();
                httpClient.connectionPool().evictAll();
                httpClient = null;
                Log.w(TAG, "[UPLOAD] 커넥션 풀 초기화 (제거된 연결=" + evicted + ")");
            }
            cryptoPipeline = null;
            consecutiveFailures = 0;
            Log.w(TAG, "[UPLOAD] 연속 " + FAILURE_THRESHOLD + "회 실패 — 파이프라인 초기화 완료");
        }
    }

    private synchronized void onUploadSuccess() {
        if (consecutiveFailures > 0) {
            Log.d(TAG, "[UPLOAD] 성공으로 연속 실패 카운터 초기화 (이전=" + consecutiveFailures + ")");
            consecutiveFailures = 0;
        }
    }

    // ──────────────────────────────────────────────
    // 오프라인 큐 저장
    // ──────────────────────────────────────────────

    public static boolean isNetworkAvailable(Context context) {
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
        boolean connected = ni != null && ni.isConnected();
        Log.d(TAG, "[NETWORK] 상태=" + (connected ? "온라인" : "오프라인")
                + (ni != null ? " (" + ni.getTypeName() + ")" : ""));
        return connected;
    }

    public void queueOffline(String deviceId, String logType,
                              String logContent, String chainHash) {
        try {
            Log.d(TAG, "[QUEUE] 오프라인 큐 저장 시작: " + logType + " (contentLen=" + logContent.length() + ")");
            String encrypted = CryptoManager.getInstance().encryptString(logContent);
            OfflineLogEntity entity = new OfflineLogEntity(deviceId, logType, encrypted, chainHash);
            com.example.logcat.queue.OfflineLogDao dao =
                    OfflineLogDatabase.getInstance(context).offlineLogDao();
            dao.insert(entity);

            int count = dao.countPending();
            long totalBytes = dao.totalContentBytes();

            if (totalBytes > MAX_OFFLINE_QUEUE_BYTES) {
                int toDelete = Math.max(1, count / 5);
                dao.deleteOldest(toDelete);
                Log.w(TAG, "[QUEUE] 용량 초과 ("
                        + (totalBytes / 1024 / 1024) + "MB/" + (MAX_OFFLINE_QUEUE_BYTES / 1024 / 1024)
                        + "MB) → 오래된 항목 " + toDelete + "개 삭제");
            } else {
                Log.d(TAG, "[QUEUE] 저장 완료: " + logType
                        + " (큐=" + count + "개, 용량=" + (totalBytes / 1024) + "KB)");
            }
        } catch (Exception e) {
            Log.e(TAG, "[QUEUE] 저장 실패: " + logType, e);
        }
    }

    // ──────────────────────────────────────────────
    // 기존 파일 기반 전송 (하위 호환 - LogHandler 호출용)
    // ──────────────────────────────────────────────

    public void sendFilesAsync(java.io.File logFile, java.io.File hashFile,
                                FileTransferCallback callback) {
        Log.d(TAG, "[FILES] 파일 기반 전송 시작: " + logFile.getName());
        new Thread(() -> {
            boolean success = false;
            try {
                String deviceId = LogHandler.getAndroidID(context, context.getContentResolver());
                String filename = logFile.getName();
                String[] parts = filename.split("_", 2);
                String logType = parts.length > 1 ? parts[1].replace(".txt", "") : "Unknown";

                byte[] content = java.nio.file.Files.readAllBytes(logFile.toPath());
                byte[] hashContent = java.nio.file.Files.readAllBytes(hashFile.toPath());
                String rawText = new String(content, java.nio.charset.StandardCharsets.UTF_8);
                String chainHash = new String(hashContent, java.nio.charset.StandardCharsets.UTF_8).trim();

                Arrays.fill(content, (byte) 0);
                Arrays.fill(hashContent, (byte) 0);

                CryptoManager crypto = CryptoManager.getInstance();
                StringBuilder decrypted = new StringBuilder();
                int lineCount = 0, failCount = 0;
                for (String line : rawText.split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) continue;
                    try {
                        decrypted.append(crypto.decryptToString(trimmed)).append("\n");
                        lineCount++;
                    } catch (Exception ex) {
                        decrypted.append(trimmed).append("\n");
                        failCount++;
                    }
                }
                Log.d(TAG, "[FILES] 파일 복호화: 성공=" + lineCount + "줄, 평문유지=" + failCount + "줄");
                String logText = decrypted.toString().trim();

                String normalizedContent = String.join("\n",
                    java.util.Arrays.stream(logText.split("\\r?\n"))
                        .collect(java.util.stream.Collectors.toList()));
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                byte[] hashBytes = md.digest(normalizedContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (byte b : hashBytes) sb.append(String.format("%02x", b));
                chainHash = sb.toString();

                success = uploadEncryptedLog(deviceId, logType, logText, chainHash);
            } catch (Exception e) {
                Log.e(TAG, "[FILES] 전송 실패: " + logFile.getName(), e);
            }
            if (success) {
                Log.d(TAG, "[FILES] 전송 성공: " + logFile.getName());
                callback.onSuccess();
            } else {
                Log.w(TAG, "[FILES] 전송 실패: " + logFile.getName());
                callback.onFailure();
            }
        }).start();
    }

    // ──────────────────────────────────────────────
    // 서버 타임스탬프 (오프셋 방식 통합)
    //
    // offset = serverTimeMs - elapsedRealtime() (최초 동기화 시 1회 계산)
    // logServerTime = elapsedRealtime() + offset  (이후 네트워크 호출 없이 계산)
    //
    // 재부팅: elapsedRealtime이 0으로 리셋되므로 offset 무효 → 재동기화 필요
    // 오프라인 재부팅: lastServerMs + currentElapsedRt 로 추정 ([estimated])
    // ──────────────────────────────────────────────

    private static final String PREFS_TS        = "aegis_ts_cache";
    private static final String KEY_OFFSET_MS   = "ts_offset_ms";      // 오프셋 (ms)
    private static final String KEY_SYNCED_AT   = "ts_synced_elapsed";  // 오프셋 계산 시점 elapsedRt
    private static final String KEY_LAST_SERVER = "ts_last_server_ms";  // 마지막 동기화 서버시간 (재부팅 추정용)

    private static final java.text.SimpleDateFormat TS_FMT =
            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());

    // 인메모리 오프셋 — 앱 재시작 전까지 유효
    private static volatile long tsOffsetMs     = Long.MIN_VALUE;
    private static volatile long tsSyncedAt     = -1; // elapsedRt at sync time

    /** 네트워크 복구 시 인메모리 오프셋 초기화 → 다음 호출에서 서버 재동기화. */
    public static void invalidateTimestampCache() {
        tsOffsetMs = Long.MIN_VALUE;
        tsSyncedAt = -1;
    }

    /**
     * 온라인/오프라인/재부팅 구분 없이 통합된 서버시각 반환.
     *
     * 정상(오프셋 유효):  elapsedRt + offset
     * 재부팅 후 오프라인: [estimated] lastServerMs + currentElapsedRt
     * 최초 오프라인:       null
     */
    public static String resolveServerTimestamp(android.content.Context context) {
        long elapsedRt = android.os.SystemClock.elapsedRealtime();
        boolean online = isNetworkAvailable(context);

        // 1. 인메모리 오프셋 유효 검사 (재부팅이면 elapsedRt < tsSyncedAt)
        if (tsOffsetMs != Long.MIN_VALUE && tsSyncedAt >= 0 && elapsedRt >= tsSyncedAt) {
            String calculated = TS_FMT.format(new java.util.Date(elapsedRt + tsOffsetMs));
            // 온라인이면 [estimated] 없이, 오프라인이면 [estimated] 표기
            String ts = online ? calculated : "[estimated] " + calculated;
            Log.d(TAG, "[TIMESTAMP] 오프셋 계산: " + ts + " (online=" + online + ")");
            return ts;
        }

        // 2. SharedPrefs에서 오프셋 복원 시도 (앱 재시작, 동일 부팅 세션)
        android.content.SharedPreferences prefs =
                context.getSharedPreferences(PREFS_TS, android.content.Context.MODE_PRIVATE);
        long savedOffset  = prefs.getLong(KEY_OFFSET_MS,   Long.MIN_VALUE);
        long savedSyncAt  = prefs.getLong(KEY_SYNCED_AT,   -1);

        if (savedOffset != Long.MIN_VALUE && savedSyncAt >= 0 && elapsedRt >= savedSyncAt) {
            tsOffsetMs  = savedOffset;
            tsSyncedAt  = savedSyncAt;
            String calculated = TS_FMT.format(new java.util.Date(elapsedRt + tsOffsetMs));
            String ts = online ? calculated : "[estimated] " + calculated;
            Log.d(TAG, "[TIMESTAMP] SharedPrefs 오프셋 복원: " + ts);
            return ts;
        }

        // 3. 오프셋 없음 (최초 실행 or 재부팅) — 네트워크로 동기화 시도
        String fetched = fetchTimestampDirect();
        if (fetched != null) {
            try {
                long serverMs    = TS_FMT.parse(fetched).getTime();
                long syncElapsed = android.os.SystemClock.elapsedRealtime();
                tsOffsetMs  = serverMs - syncElapsed;
                tsSyncedAt  = syncElapsed;
                prefs.edit()
                        .putLong(KEY_OFFSET_MS,   tsOffsetMs)
                        .putLong(KEY_SYNCED_AT,   tsSyncedAt)
                        .putLong(KEY_LAST_SERVER, serverMs)
                        .apply();
                Log.d(TAG, "[TIMESTAMP] 서버 동기화 완료: offset=" + tsOffsetMs + "ms ts=" + fetched);
                return fetched;
            } catch (Exception e) {
                Log.e(TAG, "[TIMESTAMP] 파싱 실패", e);
            }
        }

        // 4. 오프라인 재부팅 추정: lastServerMs + currentElapsedRt
        long lastServerMs = prefs.getLong(KEY_LAST_SERVER, -1);
        if (lastServerMs > 0) {
            String estimated = "[estimated] " + TS_FMT.format(new java.util.Date(lastServerMs + elapsedRt));
            Log.w(TAG, "[TIMESTAMP] 재부팅 후 오프라인 추정: " + estimated);
            return estimated;
        }

        Log.w(TAG, "[TIMESTAMP] 타임스탬프 없음 (최초 오프라인)");
        return null;
    }

    /** 서버에서 타임스탬프를 직접 fetch (동기, 최대 10초). 실패 시 null. */
    private static String fetchTimestampDirect() {
        Log.d(TAG, "[TIMESTAMP] 서버 fetch 시작");
        final String[] result = {null};
        CountDownLatch latch = new CountDownLatch(1);
        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            try {
                URL url = new URL(BASE_URL + TIMESTAMP_PATH);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);
                if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                    result[0] = sb.toString().trim();
                    Log.d(TAG, "[TIMESTAMP] fetch 성공: " + result[0]
                            + " (" + (System.currentTimeMillis() - t0) + "ms)");
                } else {
                    Log.w(TAG, "[TIMESTAMP] 서버 오류: HTTP " + conn.getResponseCode());
                }
            } catch (Exception e) {
                Log.w(TAG, "[TIMESTAMP] fetch 실패: " + e.getClass().getSimpleName()
                        + ": " + e.getMessage());
            } finally {
                latch.countDown();
            }
        }).start();
        try { latch.await(11, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        return result[0];
    }

    public interface FileTransferCallback {
        void onSuccess();
        void onFailure();
    }
}
