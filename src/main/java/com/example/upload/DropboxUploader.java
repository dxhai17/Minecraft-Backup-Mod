package com.example.upload;

import com.example.Backup;
import com.example.config.BackupConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Upload lên Dropbox, tự chọn 1 trong 2 cách theo kích thước file:
 *  - File <= 150MB: Simple Upload API (files/upload) — 1 request duy nhất,
 *    như bản cũ.
 *  - File > 150MB: Upload Session API (upload_session/start -> append_v2
 *    lặp lại theo từng chunk -> finish) — bắt buộc vì Simple Upload chặn
 *    cứng ở 150MB/request (giới hạn của chính Dropbox, không phải giả định
 *    tự đặt trong code). Đọc file theo chunk (RandomAccessFile.seek + read)
 *    thay vì Files.readAllBytes toàn bộ, tránh cấp phát cả file (có thể vài
 *    GB) một lần vào heap.
 *
 * Hỗ trợ song song 2 chế độ xác thực (BackupConfig.DropboxAuthMode):
 *  - ACCESS_TOKEN: dùng thẳng token người dùng nhập, đơn giản nhưng hết hạn
 *    sau 4 giờ (Dropbox bỏ long-lived token từ 09/2021).
 *  - REFRESH_TOKEN: trước khi upload, tự đổi refresh token (không tự hết
 *    hạn) lấy access token mới qua oauth2/token, rồi mới upload như bình
 *    thường — người dùng không cần tự tay lấy lại token định kỳ.
 */
public final class DropboxUploader implements CloudUploader {

    // Giới hạn CỨNG của chính API Dropbox cho MỖI REQUEST — không phải giá
    // trị tự đặt để giới hạn tổng dung lượng file. File nhỏ hơn ngưỡng này
    // đi thẳng Simple Upload (1 request); file lớn hơn dùng Upload Session,
    // và ngưỡng này khi đó đóng vai trò kích thước MỖI CHUNK gửi đi.
    private static final long MAX_SINGLE_REQUEST_BYTES = 150L * 1024 * 1024;

    // Độ dài tối đa của phần "chi tiết" nhồi vào shortReason — Toast chỉ có
    // 1-2 dòng, không phải chỗ hiện nguyên response body/exception message.
    private static final int SHORT_REASON_DETAIL_MAX_LEN = 50;

    private final BackupConfig.DropboxAuthMode authMode;
    private final String accessToken;      // dùng khi authMode = ACCESS_TOKEN
    private final String appKey;           // dùng khi authMode = REFRESH_TOKEN
    private final String appSecret;        // dùng khi authMode = REFRESH_TOKEN
    private final String refreshToken;     // dùng khi authMode = REFRESH_TOKEN

    /** Constructor cho chế độ ACCESS_TOKEN — giữ nguyên cách gọi cũ. */
    public DropboxUploader(String accessToken) {
        this.authMode = BackupConfig.DropboxAuthMode.ACCESS_TOKEN;
        this.accessToken = accessToken;
        this.appKey = null;
        this.appSecret = null;
        this.refreshToken = null;
    }

    /** Constructor cho chế độ REFRESH_TOKEN. */
    public DropboxUploader(String appKey, String appSecret, String refreshToken) {
        this.authMode = BackupConfig.DropboxAuthMode.REFRESH_TOKEN;
        this.accessToken = null;
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.refreshToken = refreshToken;
    }

    @Override
    public UploadResult upload(File zipFile, String worldName) {
        try {
            // Với REFRESH_TOKEN, đổi lấy access token mới (4 giờ) ngay trước khi
            // upload — không cache lại vì mỗi lần backup chỉ gọi 1 lần duy nhất,
            // không cần tối ưu số request cho use case backup định kỳ này.
            String effectiveAccessToken = authMode == BackupConfig.DropboxAuthMode.REFRESH_TOKEN
                    ? refreshAccessToken()
                    : accessToken;

            // Đường dẫn remote: /backups/<worldName>/<tên-file-gốc> — tên file đã
            // có timestamp từ ZipUtils nên mỗi lần backup là 1 file mới, KHÔNG
            // đè file cũ trên Dropbox.
            String remotePath = "/backups/" + worldName + "/" + zipFile.getName();
            long fileSize = zipFile.length();

            if (fileSize <= MAX_SINGLE_REQUEST_BYTES) {
                uploadSimple(zipFile, remotePath, effectiveAccessToken);
            } else {
                uploadViaSession(zipFile, remotePath, effectiveAccessToken);
            }

            Backup.LOGGER.info("Đã upload backup lên Dropbox: {}", remotePath);
            return UploadResult.success();

        } catch (DropboxApiException e) {
            Backup.LOGGER.error("Dropbox upload thất bại (mã {}): {}", e.statusCode, e.responseBody);
            return UploadResult.failure("mã " + e.statusCode + ": "
                    + truncate(e.responseBody, SHORT_REASON_DETAIL_MAX_LEN));
        } catch (Exception e) {
            Backup.LOGGER.error("Upload Dropbox thất bại: {}", e.getMessage(), e);
            return UploadResult.failure(shortReasonFromException(e));
        }
    }

    /** File <= 150MB: 1 request duy nhất, đọc toàn bộ vào RAM — chấp nhận được vì đã giới hạn kích thước. */
    private void uploadSimple(File zipFile, String remotePath, String accessToken) throws Exception {
        JsonObject apiArg = new JsonObject();
        apiArg.addProperty("path", remotePath);
        apiArg.addProperty("mode", "add");
        apiArg.addProperty("autorename", false);
        apiArg.addProperty("mute", true);

        byte[] fileBytes = java.nio.file.Files.readAllBytes(zipFile.toPath());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://content.dropboxapi.com/2/files/upload"))
                .header("Authorization", "Bearer " + accessToken)
                .header("Dropbox-API-Arg", httpHeaderSafeJson(apiArg.toString()))
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(fileBytes))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new DropboxApiException(response.statusCode(), response.body());
        }
    }

    /**
     * File > 150MB: chia thành nhiều chunk <= MAX_SINGLE_REQUEST_BYTES, gửi
     * tuần tự qua upload_session/start -> append_v2 (lặp lại) -> finish.
     * Đọc file bằng RandomAccessFile.seek + readFully theo từng chunk, thay
     * vì Files.readAllBytes toàn bộ file — tránh cấp phát cả file (có thể
     * vài GB) một lần vào heap, chỉ giữ tối đa 1 chunk (<=150MB) trong RAM
     * tại một thời điểm.
     */
    private void uploadViaSession(File zipFile, String remotePath, String accessToken) throws Exception {
        long fileSize = zipFile.length();
        HttpClient client = HttpClient.newHttpClient();

        try (RandomAccessFile raf = new RandomAccessFile(zipFile, "r")) {
            // ===== Bước 1: upload_session/start — gửi chunk đầu tiên =====
            long firstChunkSize = Math.min(MAX_SINGLE_REQUEST_BYTES, fileSize);
            byte[] firstChunk = readChunk(raf, 0, firstChunkSize);

            HttpRequest startRequest = HttpRequest.newBuilder()
                    .uri(URI.create("https://content.dropboxapi.com/2/files/upload_session/start"))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Dropbox-API-Arg", "{\"close\":false}")
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(firstChunk))
                    .build();

            HttpResponse<String> startResponse = client.send(startRequest, HttpResponse.BodyHandlers.ofString());
            if (startResponse.statusCode() != 200) {
                throw new DropboxApiException(startResponse.statusCode(), startResponse.body());
            }

            String sessionId = JsonParser.parseString(startResponse.body())
                    .getAsJsonObject().get("session_id").getAsString();
            long offset = firstChunkSize;

            // ===== Bước 2: upload_session/append_v2 — lặp lại cho phần còn lại,
            // TRỪ chunk cuối cùng (chunk cuối gộp luôn vào bước finish) =====
            while (fileSize - offset > MAX_SINGLE_REQUEST_BYTES) {
                byte[] chunk = readChunk(raf, offset, MAX_SINGLE_REQUEST_BYTES);

                JsonObject cursor = new JsonObject();
                cursor.addProperty("session_id", sessionId);
                cursor.addProperty("offset", offset);
                JsonObject appendArg = new JsonObject();
                appendArg.add("cursor", cursor);
                appendArg.addProperty("close", false);

                HttpRequest appendRequest = HttpRequest.newBuilder()
                        .uri(URI.create("https://content.dropboxapi.com/2/files/upload_session/append_v2"))
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Dropbox-API-Arg", httpHeaderSafeJson(appendArg.toString()))
                        .header("Content-Type", "application/octet-stream")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(chunk))
                        .build();

                HttpResponse<String> appendResponse = client.send(appendRequest, HttpResponse.BodyHandlers.ofString());
                if (appendResponse.statusCode() != 200) {
                    throw new DropboxApiException(appendResponse.statusCode(), appendResponse.body());
                }

                offset += chunk.length;
            }

            // ===== Bước 3: upload_session/finish — gửi chunk cuối cùng + commit =====
            long lastChunkSize = fileSize - offset;
            byte[] lastChunk = readChunk(raf, offset, lastChunkSize);

            JsonObject finishCursor = new JsonObject();
            finishCursor.addProperty("session_id", sessionId);
            finishCursor.addProperty("offset", offset);

            JsonObject commit = new JsonObject();
            commit.addProperty("path", remotePath);
            commit.addProperty("mode", "add");
            commit.addProperty("autorename", false);
            commit.addProperty("mute", true);

            JsonObject finishArg = new JsonObject();
            finishArg.add("cursor", finishCursor);
            finishArg.add("commit", commit);

            HttpRequest finishRequest = HttpRequest.newBuilder()
                    .uri(URI.create("https://content.dropboxapi.com/2/files/upload_session/finish"))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Dropbox-API-Arg", httpHeaderSafeJson(finishArg.toString()))
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(lastChunk))
                    .build();

            HttpResponse<String> finishResponse = client.send(finishRequest, HttpResponse.BodyHandlers.ofString());
            if (finishResponse.statusCode() != 200) {
                throw new DropboxApiException(finishResponse.statusCode(), finishResponse.body());
            }
        }
    }

    /** Đọc đúng 1 đoạn [offset, offset+length) từ file, không load phần còn lại vào RAM. */
    private static byte[] readChunk(RandomAccessFile raf, long offset, long length) throws Exception {
        byte[] buffer = new byte[(int) length];
        raf.seek(offset);
        raf.readFully(buffer);
        return buffer;
    }

    /**
     * Đổi refresh token (không tự hết hạn) lấy access token mới (4 giờ) qua
     * endpoint oauth2/token. Ném exception nếu thất bại — để lỗi này rơi vào
     * đúng nhánh catch chung của upload(), tránh trùng lặp xử lý lỗi.
     */
    private String refreshAccessToken() throws Exception {
        String basicAuth = Base64.getEncoder().encodeToString(
                (appKey + ":" + appSecret).getBytes(StandardCharsets.UTF_8));

        String requestBody = "grant_type=refresh_token&refresh_token=" + refreshToken;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.dropboxapi.com/oauth2/token"))
                .header("Authorization", "Basic " + basicAuth)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Làm mới access token thất bại (mã " + response.statusCode() + ")");
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        return json.get("access_token").getAsString();
    }

    /**
     * Escape mọi ký tự non-ASCII (và ký tự 0x7F) thành dạng UTF-8 — bắt
     * buộc theo tài liệu chính thức Dropbox cho header Dropbox-API-Arg.
     * java.net.http.HttpClient chặn thẳng mọi ký tự ngoài ASCII trong header
     * value (ném IllegalArgumentException), nên path/tên file có dấu (ví dụ
     * tên world tiếng Việt) BẮT BUỘC phải qua bước này trước khi gán vào
     * header, nếu không sẽ luôn thất bại với world có tên chứa dấu.
     */
    private static String httpHeaderSafeJson(String json) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == 0x7F || c > 0x7E) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Rút gọn message exception thành 1 câu ngắn, đủ hiểu, đủ ngắn cho Toast. */
    private static String shortReasonFromException(Exception e) {
        String msg = e.getMessage();
        return e.getClass().getSimpleName()
                + (msg != null ? ": " + truncate(msg, SHORT_REASON_DETAIL_MAX_LEN) : "");
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }

    /** Gói riêng lỗi HTTP từ Dropbox (status code + response body) để xử lý thống nhất ở 1 chỗ trong upload(). */
    private static final class DropboxApiException extends RuntimeException {
        final int statusCode;
        final String responseBody;

        DropboxApiException(int statusCode, String responseBody) {
            super("Dropbox API lỗi (mã " + statusCode + ")");
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }
    }
}