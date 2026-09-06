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

            String fileName = LocalDateTime.now().format(FILE_NAME_TIMESTAMP) + "_" + worldName;
            Path zipPath = backupDir.resolve(fileName + ".zip");

            zipDirectory(worldDir, backupDir, zipPath, worldName);

            Backup.LOGGER.info("Đã tạo backup local: {}", zipPath);

            cleanupOldBackups(backupDir, worldName, maxLocalBackups);

            return zipPath.toFile();

        } catch (Exception e) {
            Backup.LOGGER.error("Backup world '{}' thất bại: {}", worldName, e.getMessage(), e);
            return null;
        }
    }

    private static void zipDirectory(Path worldDir, Path backupDir, Path zipPath, String worldFolderName)
            throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath.toFile()))) {
            zos.setLevel(Deflater.DEFAULT_COMPRESSION);

            try (Stream<Path> walk = Files.walk(worldDir)) {
                List<Path> files = walk
                        .filter(Files::isRegularFile)
                        .filter(path -> !path.startsWith(backupDir))
                        .filter(path -> !path.getFileName().toString().equals("session.lock"))
                        .toList();

                for (Path file : files) {
                    String relativePath = worldDir.relativize(file).toString().replace('\\', '/');
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
            return;
        }

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

    /**
     * Format kích cỡ file dạng người đọc được (KB/MB/GB), dùng cho UI thông báo
     * sau khi backup xong. Không dùng thư viện ngoài — java.io.File.length()
     * trả về bytes, chỉ cần tự chia bậc.
     */
    public static String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String unit = "KMGT".charAt(exp - 1) + "B";
        return String.format("%.1f %s", bytes / Math.pow(1024, exp), unit);
    }
}