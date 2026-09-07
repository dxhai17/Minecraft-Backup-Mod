package com.example.upload;

import com.example.Backup;
import com.example.config.BackupConfig;
import com.example.config.ConfigManager;
import com.example.backup.CloudUploadEvents;
import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * Điểm vào duy nhất cho việc "backup local xong -> upload cloud". Đăng ký
 * lắng nghe BackupCompleteEvents ở Backup.onInitialize() (main, KHÔNG phải
 * client) — vì upload phải chạy được cả trên dedicated server, khác với
 * ToastHelper/BackupClient chỉ chạy phía client.
 *
 * Vì đây chỉ chọn ĐÚNG 1 uploader theo activeProvider (enum loại trừ lẫn
 * nhau, đã đảm bảo ở BackupConfig + validate ở BackupModMenuIntegration),
 * nên KHÔNG BAO GIỜ có chuyện upload lên cả Dropbox lẫn Backblaze cùng lúc.
 *
 * CƠ CHẾ GIỮ JVM SỐNG: SERVER_STOPPING chạy đồng bộ trên server thread, và
 * quá trình tắt JVM có thể diễn ra ngay sau khi listener này return. Do đó
 * upload phải chạy trên 1 Thread non-daemon riêng (JVM sẽ tự đợi thread
 * non-daemon chạy xong trước khi thoát tiến trình ở mức OS), NHƯNG ta vẫn
 * chủ động join() với timeout ngay tại đây để:
 *   1. Có kiểm soát rõ ràng thời điểm server thread tiếp tục (thay vì mù mờ
 *      trông chờ hành vi ngầm của JVM).
 *   2. Tránh trường hợp mạng treo vô thời hạn khiến server không bao giờ
 *      tắt được — sau UPLOAD_TIMEOUT, log cảnh báo và return, để server tiếp
 *      tục quy trình tắt bình thường (thread vẫn chạy nền, JVM vẫn đợi nó ở
 *      mức thấp hơn vì là non-daemon — đây là đánh đổi được chấp nhận, ưu
 *      tiên "không bao giờ treo cứng server" hơn "đảm bảo upload luôn xong").
 */
public final class CloudUploadManager {

    private static final long UPLOAD_TIMEOUT_SECONDS = 60;

    private CloudUploadManager() {}

    /**
     * Gọi từ Backup.java ngay sau khi backup local thành công.
     * KHÔNG throw exception ra ngoài — mọi lỗi tự log bên trong.
     */
    public static void handleBackupComplete(String worldName, File zipFile) {
        BackupConfig config = ConfigManager.INSTANCE;

        if (config.activeProvider == BackupConfig.CloudProvider.NONE) {
            return; // Không cấu hình cloud nào — chỉ lưu local, không làm gì thêm.
        }

        if (!config.isActiveProviderConfigured()) {
            // Phòng vệ thêm dù UI đã chặn lưu config thiếu field — tránh trường
            // hợp file config bị sửa tay ngoài UI dẫn tới thiếu credential.
            Backup.LOGGER.warn("Nền tảng cloud '{}' đang thiếu thông tin xác thực, bỏ qua upload.",
                    config.activeProvider);
            return;
        }

        CloudUploader uploader = switch (config.activeProvider) {
            case DROPBOX -> new DropboxUploader(config.dropboxToken);
            case BACKBLAZE -> new BackblazeUploader(
                    config.backblazeEndpoint, config.backblazeKeyId, config.backblazeApplicationKey, config.backblazeBucket);
            case NONE -> null; // Không tới được đây vì đã return ở trên, giữ để switch đủ nhánh.
        };

        if (uploader == null) {
            return;
        }

        // Non-daemon (mặc định của "new Thread") để JVM đợi thread này nếu
        // join() dưới đây timeout trước khi thread xong việc.
        Thread uploadThread = new Thread(
                () -> runUpload(uploader, zipFile, worldName, config.activeProvider),
                "cloud-upload-" + config.activeProvider
        );
        uploadThread.start();

        try {
            uploadThread.join(TimeUnit.SECONDS.toMillis(UPLOAD_TIMEOUT_SECONDS));
            if (uploadThread.isAlive()) {
                Backup.LOGGER.warn(
                        "Upload lên {} chưa xong sau {}s, server tiếp tục tắt. " +
                                "Upload vẫn chạy nền cho tới khi hoàn tất hoặc JVM bị buộc kill.",
                        config.activeProvider, UPLOAD_TIMEOUT_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Backup.LOGGER.warn("Server thread bị interrupt trong lúc chờ upload cloud, bỏ qua chờ tiếp.");
        }
    }

    private static void runUpload(CloudUploader uploader, File zipFile, String worldName,
                                  BackupConfig.CloudProvider provider) {
        Backup.LOGGER.info("Bắt đầu upload backup '{}' lên {}...", zipFile.getName(), provider);
        UploadResult result = uploader.upload(zipFile, worldName);

        if (result.isSuccess()) {
            Backup.LOGGER.info("Upload lên {} hoàn tất.", provider);
        } else {
            Backup.LOGGER.error("Upload lên {} thất bại: {}", provider, result.getShortReason());
        }

        // Bắn event riêng cho UI (Toast) — tách khỏi log ở trên: log cần đầy đủ
        // (đã ghi chi tiết ngay trong từng Uploader), Toast chỉ cần bản RÚT GỌN.
        // An toàn gọi từ background thread này vì ToastHelper (phía client) tự
        // đẩy về render thread bên trong nó, giống cơ chế showBackupSuccessToast().
        CloudUploadEvents.UPLOAD_COMPLETE.invoker()
                .onUploadComplete(displayName(provider), result.isSuccess(), result.getShortReason());
    }

    /** Tên hiển thị lên Toast — KHÁC với BackupConfig.CloudProvider.toString() (kỹ thuật). */
    private static String displayName(BackupConfig.CloudProvider provider) {
        return switch (provider) {
            case DROPBOX -> "Dropbox";
            case BACKBLAZE -> "Backblaze B2";
            case NONE -> "Cloud"; // Không tới được đây trong luồng thực tế, giữ để switch đủ nhánh.
        };
    }
}