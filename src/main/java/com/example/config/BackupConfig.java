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

    // ===== Chung =====
    public boolean backupOnQuit = true;
    public int maxLocalBackups = 15;
    public CloudProvider activeProvider = CloudProvider.NONE;

    // ===== Dropbox =====
    public String dropboxToken = "";

    // ===== Backblaze B2 (S3-compatible) =====
    public String backblazeEndpoint = "";
    public String backblazeKeyId = "";
    public String backblazeApplicationKey = "";
    public String backblazeBucket = "";

    /**
     * Kiểm tra xem provider đang active đã đủ thông tin xác thực chưa.
     * Dùng lại được ở cả UI (hiển thị cảnh báo) lẫn lúc trigger backup thật
     * (tránh gọi upload với config rỗng/thiếu field).
     */
    public boolean isActiveProviderConfigured() {
        return switch (activeProvider) {
            case NONE -> true;
            case DROPBOX -> dropboxToken != null && !dropboxToken.isBlank();
            case BACKBLAZE -> backblazeEndpoint != null && !backblazeEndpoint.isBlank()
                    && backblazeKeyId != null && !backblazeKeyId.isBlank()
                    && backblazeApplicationKey != null && !backblazeApplicationKey.isBlank()
                    && backblazeBucket != null && !backblazeBucket.isBlank();
        };
    }
}