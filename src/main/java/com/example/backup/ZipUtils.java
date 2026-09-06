package com.example.backup;

import com.example.Backup;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Nén thư mục world thành 1 file zip local, lưu vào thư mục /backups riêng,
 * NGANG HÀNG với /saves (vd: run/backups/, run/saves/<world>/) — không lồng
 * bên trong thư mục world như nút "Make Backup" gốc của game.
 *
 * QUAN TRỌNG: tên FILE zip có timestamp (để không đè bản cũ), nhưng bên trong
 * zip chỉ bọc đúng 1 thư mục cha tên là WORLD NAME — không kèm timestamp.
 * Giải nén ra là dán thẳng được vào /saves, không cần đổi tên tay — khớp
 * đúng hành vi nút "Make Backup" gốc của Minecraft.
 *
 * Nếu tên thư mục trong zip lệch khỏi tên world thật (ví dụ lỡ kèm timestamp),
 * người dùng phải tự đổi tên khi restore, dẫn tới LevelName bên trong
 * level.dat (tên world gốc, không đổi theo tên thư mục ngoài) lệch khỏi tên
 * thư mục thực tế trong /saves — gây lỗi getLevelName() không khớp thư mục
 * thật khi backup lại lần sau.
 *
 * Toàn bộ method ở đây được thiết kế để KHÔNG BAO GIỜ ném exception ra ngoài —
 * chỉ trả về true/false — vì đây là tính năng phụ chạy lúc server đang tắt,
 * không được phép làm crash quá trình thoát game bình thường.
 */
public final class ZipUtils {

    private static final DateTimeFormatter FILE_NAME_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private ZipUtils() {}

    /**
     * @param worldName tên thư mục world (vd: "world", "Hacker")
     * @param maxLocalBackups sau khi nén xong, dọn bớt bản cũ nếu vượt số này
     * @return file zip vừa tạo, hoặc null nếu thất bại (đã log lỗi bên trong)
     */
    public static File backupWorld(String worldName, int maxLocalBackups) {
        try {
            Path gameDir = FabricLoader.getInstance().getGameDir();
            Path worldDir = gameDir.resolve("saves").resolve(worldName);

            if (!Files.isDirectory(worldDir)) {
                Backup.LOGGER.warn("Không tìm thấy thư mục world '{}' để backup, bỏ qua.", worldName);
                return null;
            }

            Path backupDir = gameDir.resolve("backups");
            Files.createDirectories(backupDir);

            // Tên FILE zip (có timestamp, để không đè lên bản cũ) và tên THƯ MỤC
            // CHA bên trong zip (đúng bằng tên world, KHÔNG kèm timestamp) là 2
            // giá trị tách biệt — đây chính là cách nút "Make Backup" gốc của
            // game làm, để giải nén ra luôn đúng đặt tên thư mục world, dán
            // thẳng vào /saves mà không cần đổi tên tay.
            String fileName = LocalDateTime.now().format(FILE_NAME_TIMESTAMP) + "_" + worldName;
            Path zipPath = backupDir.resolve(fileName + ".zip");

            zipDirectory(worldDir, backupDir, zipPath, worldName);

            Backup.LOGGER.info("Đã tạo backup local: {}", zipPath);

            cleanupOldBackups(backupDir, worldName, maxLocalBackups);

            return zipPath.toFile();

        } catch (Exception e) {
            // Bắt Exception rộng có chủ đích: tuyệt đối không để lỗi lọt ra ngoài
            // làm ảnh hưởng luồng SERVER_STOPPING đang gọi hàm này.
            Backup.LOGGER.error("Backup world '{}' thất bại: {}", worldName, e.getMessage(), e);
            return null;
        }
    }

    private static void zipDirectory(Path worldDir, Path backupDir, Path zipPath, String worldFolderName)
            throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath.toFile()))) {
            zos.setLevel(Deflater.DEFAULT_COMPRESSION); // thuật toán Deflate

            try (Stream<Path> walk = Files.walk(worldDir)) {
                List<Path> files = walk
                        .filter(Files::isRegularFile)
                        // Bỏ qua chính thư mục backups/ để tránh zip đệ quy các bản zip cũ
                        .filter(path -> !path.startsWith(backupDir))
                        // Bỏ qua session.lock — Minecraft dùng để khóa world, không cần backup
                        .filter(path -> !path.getFileName().toString().equals("session.lock"))
                        .toList();

                for (Path file : files) {
                    String relativePath = worldDir.relativize(file).toString().replace('\\', '/');
                    // Bọc trong 1 thư mục cha đúng bằng TÊN WORLD (không timestamp) —
                    // giải nén ra là dán thẳng được vào /saves, không cần đổi tên tay.
                    String entryName = worldFolderName + "/" + relativePath;
                    zos.putNextEntry(new ZipEntry(entryName));
                    Files.copy(file, zos);
                    zos.closeEntry();
                }
            }
        }
    }

    private static void cleanupOldBackups(Path backupDir, String worldName, int maxLocalBackups) throws IOException {
        if (maxLocalBackups <= 0) {
            return; // 0 hoặc âm nghĩa là không giới hạn — không dọn gì cả
        }

        // /backups giờ chứa chung nhiều world — chỉ đếm/xoá đúng file của worldName này,
        // dựa vào tên file kết thúc bằng "_<worldName>.zip" (đúng định dạng đã tạo ở trên).
        String suffix = "_" + worldName + ".zip";

        try (Stream<Path> walk = Files.list(backupDir)) {
            List<Path> zipFiles = walk
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .sorted(Comparator.comparingLong(ZipUtils::lastModifiedSafe))
                    .toList();

            int excess = zipFiles.size() - maxLocalBackups;
            for (int i = 0; i < excess; i++) {
                Path oldest = zipFiles.get(i);
                Files.deleteIfExists(oldest);
                Backup.LOGGER.info("Đã xoá backup local cũ vượt giới hạn: {}", oldest.getFileName());
            }
        }
    }

    private static long lastModifiedSafe(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}