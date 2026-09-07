package com.example.config;

import com.example.Backup;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

import java.net.URI;
import java.util.regex.Pattern;

public final class BackblazeConfigSection {

    // Quy tắc đặt tên bucket chuẩn S3: 3-63 ký tự, chữ thường/số/dấu gạch ngang,
    // không bắt đầu/kết thúc bằng dấu gạch ngang. Áp dụng luôn cho Backblaze vì
    // Backblaze tương thích S3 API.
    public static final Pattern BUCKET_NAME_PATTERN =
            Pattern.compile("^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$");

    private BackblazeConfigSection() {}

    public static void addEntries(ConfigCategory category, ConfigEntryBuilder eb, BackupConfig config) {
        category.addEntry(eb.startStrField(
                        Component.literal("Endpoint"),
                        config.backblazeEndpoint)
                .setDefaultValue("")
                .setSaveConsumer(newValue -> config.backblazeEndpoint = newValue)
                .build());

        category.addEntry(eb.startStrField(
                        Component.literal("Access Key (keyID)"),
                        config.backblazeKeyId)
                .setDefaultValue("")
                .setSaveConsumer(newValue -> config.backblazeKeyId = newValue)
                .build());

        category.addEntry(eb.startStrField(
                        Component.literal("Secret Key (applicationKey)"),
                        config.backblazeApplicationKey)
                .setDefaultValue("")
                .setSaveConsumer(newValue -> config.backblazeApplicationKey = newValue)
                .build());

        category.addEntry(eb.startStrField(
                        Component.literal("Tên bucket"),
                        config.backblazeBucket)
                .setDefaultValue("")
                .setSaveConsumer(newValue -> config.backblazeBucket = newValue)
                .build());
    }

    /**
     * Test kết nối bằng chính S3Client + HeadBucket — ĐÚNG API dùng cho
     * upload thật (BackblazeUploader), thay vì Native B2 API
     * (b2_authorize_account) như bản trước. Lý do bắt buộc phải đổi: 2 API
     * này xác thực độc lập nhau — 1 applicationKey có thể pass Native Auth
     * nhưng vẫn fail ở S3 (hoặc ngược lại) tuỳ quyền hạn được cấp trên
     * Backblaze, nên test bằng Native API không đảm bảo upload thật sẽ chạy
     * được, gây hiểu lầm cho người dùng khi họ bấm "Test" thấy thành công mà
     * lúc backup thật lại lỗi.
     *
     * HeadBucket được chọn vì đây là request rẻ nhất (không tải dữ liệu, chỉ
     * kiểm tra quyền truy cập + bucket tồn tại) — không side-effect, an toàn
     * để gọi lặp lại nhiều lần lúc người dùng đang gõ thử config.
     *
     * KHÔNG dùng chung CloudConnectionTester (thiết kế quanh HttpRequest/
     * HttpResponse thuần) vì S3Client có kiểu exception và luồng gọi khác
     * hẳn — cố ép dùng chung sẽ phải bọc thêm 1 lớp giả HttpRequest vô nghĩa.
     */
    public static void testConnection(String endpoint, String keyId, String applicationKey, String bucketName) {
        new Thread(() -> {
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

                s3.headBucket(HeadBucketRequest.builder().bucket(bucketName).build());
                showToast("Test Backblaze", "Thành công: Key và bucket hợp lệ!");

            } catch (Exception e) {
                Backup.LOGGER.warn("Test Backblaze thất bại: {}", e.getMessage());
                showToast("Test Backblaze", "Thất bại: " + shortErrorMessage(e));
            }
        }).start();
    }

    private static String extractRegionOrDefault(String endpoint) {
        String[] parts = endpoint.replace("https://", "").replace("http://", "").split("\\.");
        if (parts.length >= 2 && parts[0].equals("s3")) {
            return parts[1];
        }
        return "us-east-1";
    }

    /**
     * Rút gọn message lỗi để hiện trên Toast (không gian hiển thị hạn chế) —
     * message đầy đủ đã được log ra console/log file ở trên rồi.
     */
    private static String shortErrorMessage(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return e.getClass().getSimpleName();
        }
        return message.length() > 80 ? message.substring(0, 80) + "..." : message;
    }

    private static void showToast(String title, String message) {
        Minecraft.getInstance().execute(() ->
                Minecraft.getInstance().gui.toastManager().addToast(
                        new SystemToast(
                                SystemToast.SystemToastId.NARRATOR_TOGGLE,
                                Component.literal(title),
                                Component.literal(message)
                        )
                )
        );
    }
}