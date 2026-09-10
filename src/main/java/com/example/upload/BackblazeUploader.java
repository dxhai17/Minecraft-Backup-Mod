package com.example.upload;

import com.example.Backup;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Upload lên Backblaze B2 bằng Native B2 API (b2_authorize_account ->
 * b2_get_upload_url -> upload file), dùng java.net.http.HttpClient thuần —
 * KHÔNG còn dùng AWS SDK / S3-compatible endpoint như bản trước.
 *
 * Lý do đổi: 2 API (Native B2 và S3-compatible) độc lập nhau, và Native B2
 * loại bỏ hoàn toàn nhu cầu đóng gói AWS SDK (Shadow/relocate) trong mod —
 * chỉ cần HttpClient có sẵn trong JDK.
 *
 * "endpoint" không còn được dùng ở đây (Native B2 luôn gọi cố định
 * api.backblazeb2.com, không cần người dùng tự nhập); tham số vẫn được giữ
 * trong constructor để không đổi chữ ký gọi ở nơi khác.
 *
 * Không dùng thư viện JSON ngoài (để không thêm dependency mới) — response
 * của B2 API có cấu trúc phẳng, đơn giản, nên parse bằng regex là đủ và an
 * toàn cho các field cần lấy (apiUrl, authorizationToken, uploadUrl,
 * authorizationToken của upload url).
 */
public final class BackblazeUploader implements CloudUploader {

    private static final String AUTHORIZE_URL =
            "https://api.backblazeb2.com/b2api/v3/b2_authorize_account";

    private final String endpoint;
    private final String keyId;
    private final String applicationKey;
    private final String bucketName;

    public BackblazeUploader(String endpoint, String keyId, String applicationKey, String bucketName) {
        this.endpoint = endpoint;
        this.keyId = keyId;
        this.applicationKey = applicationKey;
        this.bucketName = bucketName;
    }

    @Override
    public UploadResult upload(File zipFile, String worldName) {
        String objectKey = "backups/" + worldName + "/" + zipFile.getName();
        HttpClient client = HttpClient.newHttpClient();

        try {
            // Bước 1: b2_authorize_account -> lấy apiUrl + authorizationToken
            AuthInfo auth = authorizeAccount(client);

            // Bước 2: b2_get_upload_url -> lấy uploadUrl + uploadAuthToken riêng cho bucket
            UploadUrlInfo uploadUrlInfo = getUploadUrl(client, auth);

            // Bước 3: upload file thật lên uploadUrl vừa lấy
            uploadFile(client, uploadUrlInfo, zipFile, objectKey);

            Backup.LOGGER.info("Đã upload backup lên Backblaze B2: {}/{}", bucketName, objectKey);
            return UploadResult.success();

        } catch (Exception e) {
            Backup.LOGGER.error("Upload Backblaze thất bại: {}", e.getMessage(), e);
            String msg = e.getMessage();
            String shortReason = e.getClass().getSimpleName()
                    + (msg != null ? ": " + (msg.length() <= 50 ? msg : msg.substring(0, 50) + "…") : "");
            return UploadResult.failure(shortReason);
        }
    }

    private AuthInfo authorizeAccount(HttpClient client) throws Exception {
        String credentials = Base64.getEncoder().encodeToString(
                (keyId + ":" + applicationKey).getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(AUTHORIZE_URL))
                .header("Authorization", "Basic " + credentials)
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("b2_authorize_account thất bại (mã " + response.statusCode() + ")");
        }

        String body = response.body();
        return new AuthInfo(
                extractJsonField(body, "apiUrl"),
                extractJsonField(body, "authorizationToken"),
                extractJsonField(body, "accountId"));
    }

    private UploadUrlInfo getUploadUrl(HttpClient client, AuthInfo auth) throws Exception {
        // bucketId không có sẵn (chỉ có bucketName trong config) — Native B2
        // b2_get_upload_url yêu cầu bucketId, nên cần tra cứu qua b2_list_buckets
        // trước, lọc theo tên bucket để lấy đúng bucketId.
        String bucketId = findBucketId(client, auth);

        String requestBody = "{\"bucketId\":\"" + bucketId + "\"}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(auth.apiUrl + "/b2api/v3/b2_get_upload_url"))
                .header("Authorization", auth.authorizationToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("b2_get_upload_url thất bại (mã " + response.statusCode() + ")");
        }

        String body = response.body();
        return new UploadUrlInfo(
                extractJsonField(body, "uploadUrl"),
                extractJsonField(body, "authorizationToken"));
    }

    private String findBucketId(HttpClient client, AuthInfo auth) throws Exception {
        String requestBody = "{\"accountId\":\"" + auth.accountId + "\",\"bucketName\":\"" + bucketName + "\"}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(auth.apiUrl + "/b2api/v3/b2_list_buckets"))
                .header("Authorization", auth.authorizationToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("b2_list_buckets thất bại (mã " + response.statusCode() + ")");
        }

        String bucketId = extractJsonField(response.body(), "bucketId");
        if (bucketId == null) {
            throw new RuntimeException("Không tìm thấy bucket '" + bucketName + "'");
        }
        return bucketId;
    }

    private void uploadFile(HttpClient client, UploadUrlInfo uploadUrlInfo, File zipFile, String objectKey)
            throws Exception {
        byte[] fileBytes = Files.readAllBytes(zipFile.toPath());
        String sha1 = sha1Hex(fileBytes);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uploadUrlInfo.uploadUrl))
                .header("Authorization", uploadUrlInfo.uploadAuthToken)
                .header("X-Bz-File-Name", java.net.URLEncoder.encode(objectKey, StandardCharsets.UTF_8))
                .header("Content-Type", "application/zip")
                .header("X-Bz-Content-Sha1", sha1)
                .POST(HttpRequest.BodyPublishers.ofByteArray(fileBytes))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Upload file thất bại (mã " + response.statusCode() + ")");
        }
    }

    private static String sha1Hex(byte[] data) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
        byte[] hash = digest.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Trích 1 field string phẳng dạng "fieldName":"value" từ JSON response.
     * Đủ dùng cho response của B2 API (không có object lồng nhau ở các field
     * cần lấy), tránh phải thêm thư viện JSON parser mới vào dependency.
     */
    private static String extractJsonField(String json, String fieldName) {
        Pattern pattern = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static final class AuthInfo {
        final String apiUrl;
        final String authorizationToken;
        final String accountId;

        AuthInfo(String apiUrl, String authorizationToken, String accountId) {
            this.apiUrl = apiUrl;
            this.authorizationToken = authorizationToken;
            this.accountId = accountId;
        }
    }

    private static final class UploadUrlInfo {
        final String uploadUrl;
        final String uploadAuthToken;

        UploadUrlInfo(String uploadUrl, String uploadAuthToken) {
            this.uploadUrl = uploadUrl;
            this.uploadAuthToken = uploadAuthToken;
        }
    }
}