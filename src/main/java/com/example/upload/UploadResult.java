package com.example.upload;

/**
 * Kết quả upload cloud, thay cho boolean đơn thuần trong CloudUploader.upload().
 * Nhờ đó CloudUploadManager có 1 câu lý do NGẮN GỌN (khác log đầy đủ đã ghi
 * bên trong từng Uploader) để đưa lên Toast, không cần đoán lại lý do lỗi.
 *
 * "shortReason" CHỈ có giá trị khi success = false, và PHẢI đủ ngắn để hiện
 * gọn 1 dòng Toast (khuyến nghị dưới ~60 ký tự) — KHÔNG nhồi nguyên message/
 * exception dài như log console vẫn làm.
 */
public final class UploadResult {

    private final boolean success;
    private final String shortReason;

    private UploadResult(boolean success, String shortReason) {
        this.success = success;
        this.shortReason = shortReason;
    }

    public static UploadResult success() {
        return new UploadResult(true, null);
    }

    public static UploadResult failure(String shortReason) {
        return new UploadResult(false, shortReason);
    }

    public boolean isSuccess() {
        return success;
    }

    /** @return lý do ngắn gọn khi thất bại, hoặc null nếu success = true. */
    public String getShortReason() {
        return shortReason;
    }
}