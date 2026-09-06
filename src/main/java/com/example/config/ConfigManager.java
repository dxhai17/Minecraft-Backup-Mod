package com.example.config;

import com.example.Backup;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "backup_mod.json");
    public static BackupConfig INSTANCE = new BackupConfig();

    public static void load() {
        if (CONFIG_FILE.exists()) {
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                BackupConfig loaded = GSON.fromJson(reader, BackupConfig.class);

                // Gson tạo object bằng Unsafe allocation, KHÔNG chạy qua field
                // initializer của BackupConfig — nếu key thiếu trong JSON, field
                // nhận giá trị mặc định của kiểu Java (false cho boolean), không
                // phải giá trị "= true" khai trong class. Nếu fromJson trả về
                // null hoàn toàn (file rỗng/JSON hỏng), phải tự new lại để tránh
                // NullPointerException lan ra toàn bộ mod.
                INSTANCE = (loaded != null) ? loaded : new BackupConfig();

                // Log trực tiếp giá trị thật sau khi load — để xác nhận đúng
                // nguyên nhân thay vì đoán, thay vì phải mở tay file JSON.
                Backup.LOGGER.info("Đã nạp config: backupOnQuit={}, activeProvider={}",
                        INSTANCE.backupOnQuit, INSTANCE.activeProvider);
            } catch (IOException e) {
                e.printStackTrace();
                INSTANCE = new BackupConfig();
            }
        } else {
            save(); // Tạo file mới nếu chưa có
        }
    }

    public static void save() {
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(INSTANCE, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}