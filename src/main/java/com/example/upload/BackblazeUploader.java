package com.example.upload;

import com.example.Backup;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.File;
import java.net.URI;

/**
 * Upload lên Backblaze B2 bằng AWS SDK v2 (S3Client), trỏ vào endpoint
 * S3-compatible của Backblaze (vd: s3.us-east-005.backblazeb2.com) — ĐÚNG
 * theo hạ tầng đã chọn sẵn trong build.gradle (dependency software.amazon.awssdk:s3),
 * KHÔNG dùng Native B2 API (b2_authorize_account/b2_get_upload_url) như bản
 * nháp trước — 2 API đó không tương thích nhau và không nên trộn lẫn.
 *
 * "keyId" đóng vai trò AWS access key, "applicationKey" đóng vai trò AWS
 * secret key — đây là cách Backblaze cố tình thiết kế để tương thích ngược
 * với mọi SDK/tool vốn viết cho AWS S3, không cần đổi tên field trong
 * BackupConfig.
 */
public final class BackblazeUploader implements CloudUploader {

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

        String normalizedEndpoint = endpoint.startsWith("http://") || endpoint.startsWith("https://")
                ? endpoint
                : "https://" + endpoint;

        try (S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create(normalizedEndpoint))
                .region(Region.of(extractRegionOrDefault(endpoint)))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(keyId, applicationKey)))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .forcePathStyle(false)
                .build()) {

            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .contentType("application/zip")
                    .build();

            s3.putObject(request, RequestBody.fromFile(zipFile));

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

    /**
     * Endpoint Backblaze có dạng "s3.<region>.backblazeb2.com" — tách phần
     * region ra để truyền cho SDK. Nếu không khớp định dạng mong đợi (người
     * dùng nhập sai/khác), fallback về "us-east-1" — giá trị này gần như
     * không ảnh hưởng vì endpointOverride đã ghi đè việc chọn host thật sự.
     */
    private static String extractRegionOrDefault(String endpoint) {
        String[] parts = endpoint.replace("https://", "").replace("http://", "").split("\\.");
        if (parts.length >= 2 && parts[0].equals("s3")) {
            return parts[1];
        }
        return "us-east-1";
    }
}