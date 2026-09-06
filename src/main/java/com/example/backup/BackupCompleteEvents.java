package com.example.backup;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

import java.io.File;

/**
 * Sự kiện phát ra khi backup local hoàn tất — nằm ở src/main/java, KHÔNG
 * import bất kỳ class client nào (Minecraft, SystemToast...). BackupClient.java
 * (ở src/client/java) tự đăng ký lắng nghe sự kiện này để hiện Toast, thay vì
 * Backup.java gọi thẳng sang package client — tránh lỗi "main phụ thuộc client"
 * mà splitEnvironmentSourceSets() của Loom không cho phép.
 */
public final class BackupCompleteEvents {

    public interface Listener {
        void onBackupComplete(String worldName, File zipFile);
    }

    public static final Event<Listener> BACKUP_COMPLETE = EventFactory.createArrayBacked(
            Listener.class,
            listeners -> (worldName, zipFile) -> {
                for (Listener listener : listeners) {
                    listener.onBackupComplete(worldName, zipFile);
                }
            }
    );

    private BackupCompleteEvents() {}
}