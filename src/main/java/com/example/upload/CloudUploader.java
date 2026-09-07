package com.example.upload;

import java.io.File;

/**
 * Hợp đồng chung cho mọi provider upload cloud. Mỗi provider (Dropbox,
 * Backblaze B2...) có 1 class implement riêng — KHÔNG gộp chung logic
 * upload vào 1 class dùng if/else theo provider, vì request/response/lỗi
 * của mỗi API hoàn toàn khác nhau (xem DropboxUploader, BackblazeUploader).
 *
 * Method này chạy trên thread riêng do CloudUploadManager tạo ra — implementation
 * KHÔNG được tự spawn thêm thread, và PHẢI block (synchronous) cho tới khi
 * xong hẳn hoặc lỗi hẳn, để CloudUploadManager join() đúng thời điểm.
 */
public interface CloudUploader {

    /**
     * Upload 1 file zip lên cloud, dưới đường dẫn remote tương ứng
     * backups/<worldName>/<tên-file-zip>. Không được đè/xoá file cũ trên
     * cloud — mỗi lần backup là 1 file mới (tên đã có timestamp sẵn từ
     * ZipUtils nên không lo trùng tên).
     *
     * @param zipFile   file zip local vừa backup xong (đã tồn tại chắc chắn)
     * @param worldName tên world, dùng làm thư mục con trên cloud
     * @return true nếu upload thành công, false nếu thất bại (đã tự log lỗi
     *         bên trong implementation, caller không cần log lại)
     */
    boolean upload(File zipFile, String worldName);
}