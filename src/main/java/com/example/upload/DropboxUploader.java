package com.example.upload;

import com.example.Backup;
import com.google.gson.JsonObject;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;

/**
 * Upload lên Dropbox bằng Simple Upload API (files/upload) — giới hạn 150MB
 * mỗi request theo tài liệu Dropbox. Đủ dùng cho world nhỏ/vừa; nếu sau này
 * cần hỗ trợ world lớn hơn 150MB, phải chuyển sang Upload Session
 * (upload_session/start -> append_v2 -> finish) — CHƯA làm ở version này,
 * đúng theo phạm vi đã chốt.
 */
public final class DropboxUploader implements CloudUploader {

    // Dropbox tự chặn ở phía server nếu vượt quá, nhưng check trước ở client
    // để fail nhanh + log rõ ràng, không tốn công gửi cả trăm MB rồi mới biết lỗi.
    private static final long MAX_SIMPLE_UPLOAD_BYTES = 150L * 1024 * 1024;

    // Độ dài tối đa của phần "chi tiết" nhồi vào shortReason — Toast chỉ có
    // 1-2 dòng, không phải chỗ hiện nguyên response body/exception message.
    private static final int SHORT_REASON_DETAIL_MAX_LEN = 50;

    private final String accessToken;

    public DropboxUploader(String accessToken) {
        this.accessToken = accessToken;
    }

    @Override
    public UploadResult upload(File zipFile, String worldName) {
        try {
            long fileSize = zipFile.length();
            if (fileSize > MAX_SIMPLE_UPLOAD_BYTES) {
                Backup.LOGGER.error(
                        "File backup '{}' ({} bytes) vượt giới hạn 150MB của Dropbox Simple Upload API. " +
                                "Bỏ qua upload — cần Upload Session cho file lớn hơn (chưa hỗ trợ).",
                        zipFile.getName(), fileSize);
                return UploadResult.failure("Vượt 150MB (Simple Upload)");
            }

            // Đường dẫn remote: /backups/<worldName>/<tên-file-gốc> — tên file đã
            // có timestamp từ ZipUtils nên mỗi lần backup là 1 file mới, KHÔNG
            // đè file cũ trên Dropbox.
            String remotePath = "/backups/" + worldName + "/" + zipFile.getName();

            JsonObject apiArg = new JsonObject();
            apiArg.addProperty("path", remotePath);
            apiArg.addProperty("mode", "add"); // "add" tự động báo lỗi conflict thay vì đè, thêm 1 lớp an toàn dù tên đã unique
            apiArg.addProperty("autorename", false);
            apiArg.addProperty("mute", true);

            byte[] fileBytes = Files.readAllBytes(zipFile.toPath());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://content.dropboxapi.com/2/files/upload"))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Dropbox-API-Arg", apiArg.toString())
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(fileBytes))
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                Backup.LOGGER.error("Dropbox upload thất bại (mã {}): {}",
                        response.statusCode(), response.body());
                return UploadResult.failure("mã " + response.statusCode() + ": "
                        + truncate(response.body(), SHORT_REASON_DETAIL_MAX_LEN));
            }

            Backup.LOGGER.info("Đã upload backup lên Dropbox: {}", remotePath);
            return UploadResult.success();

        } catch (Exception e) {
            Backup.LOGGER.error("Upload Dropbox thất bại: {}", e.getMessage(), e);
            return UploadResult.failure(shortReasonFromException(e));
        }
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
}