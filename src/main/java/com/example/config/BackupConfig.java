package com.example.config;

public class BackupConfig {

    /**
     * Chỉ 1 provider được active tại 1 thời điểm — đúng quyết định thiết kế đã chốt.
     * Không dùng 2 boolean độc lập (enableDropbox/enableBackblaze) vì dễ dẫn tới
     * trạng thái mâu thuẫn (cả 2 cùng true). Dùng enum để loại trừ lẫn nhau.
     */
    public enum CloudProvider {
        NONE, DROPBOX, BACKBLAZE
    }

    /**
     * Dropbox hỗ trợ song song 2 cách xác thực — người dùng chọn 1 trong 2:
     * ACCESS_TOKEN (đơn giản, nhưng hết hạn sau 4 giờ kể từ khi Dropbox bỏ
     * long-lived token từ 09/2021) hoặc REFRESH_TOKEN (cần setup phức tạp hơn
     * qua OAuth, nhưng không tự hết hạn). Giữ cả 2 để ACCESS_TOKEN làm phương
     * án dự phòng nếu luồng refresh token gặp lỗi.
     */
    public enum DropboxAuthMode {
        ACCESS_TOKEN, REFRESH_TOKEN
    }

    // ===== Chung =====
    public boolean backupOnQuit = true;
    public int maxLocalBackups = 15;
    public CloudProvider activeProvider = CloudProvider.NONE;

    // ===== Dropbox =====
    public DropboxAuthMode dropboxAuthMode = DropboxAuthMode.ACCESS_TOKEN;
    public String dropboxToken = "";
    public String dropboxAppKey = "";
    public String dropboxAppSecret = "";
    public String dropboxRefreshToken = "";

    // ===== Backblaze B2 (S3-compatible) =====
    public String backblazeEndpoint = "";
    public String backblazeKeyId = "";
    public String backblazeApplicationKey = "";
    public String backblazeBucket = "";

    /**
     * Kiểm tra xem provider đang active đã đủ thông tin xác thực chưa.
     * Dùng lại được ở cả UI (hiển thị cảnh báo) lẫn lúc trigger backup thật
     * (tránh gọi upload với config rỗng/thiếu field).
     *
     * Với Dropbox, validate đúng theo dropboxAuthMode đang chọn — không đòi
     * hỏi cả 2 bộ field cùng lúc, vì người dùng chỉ điền 1 trong 2 chế độ.
     */
    public boolean isActiveProviderConfigured() {
        return switch (activeProvider) {
            case NONE -> true;
            case DROPBOX -> switch (dropboxAuthMode) {
                case ACCESS_TOKEN -> dropboxToken != null && !dropboxToken.isBlank();
                case REFRESH_TOKEN -> dropboxAppKey != null && !dropboxAppKey.isBlank()
                        && dropboxAppSecret != null && !dropboxAppSecret.isBlank()
                        && dropboxRefreshToken != null && !dropboxRefreshToken.isBlank();
            };
            case BACKBLAZE -> backblazeEndpoint != null && !backblazeEndpoint.isBlank()
                    && backblazeKeyId != null && !backblazeKeyId.isBlank()
                    && backblazeApplicationKey != null && !backblazeApplicationKey.isBlank()
                    && backblazeBucket != null && !backblazeBucket.isBlank();
        };
    }
}