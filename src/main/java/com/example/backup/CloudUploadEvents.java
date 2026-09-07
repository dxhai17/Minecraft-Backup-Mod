package com.example.backup;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

/**
 * Sự kiện phát ra khi 1 lần upload cloud (Dropbox/Backblaze) hoàn tất — dù
 * thành công hay thất bại. Nằm ở src/main/java như BackupCompleteEvents,
 * KHÔNG import class client nào: CloudUploadManager (main, chạy cả trên
 * dedicated server) là nơi phát event; phần hiện Toast (client-only) tự
 * đăng ký lắng nghe ở phía client — main không cần biết ai đang nghe.
 *
 * "shortReason" chỉ có giá trị khi success = false (xem UploadResult).
 */
public final class CloudUploadEvents {

    public interface Listener {
        void onUploadComplete(String providerDisplayName, boolean success, String shortReason);
    }

    public static final Event<Listener> UPLOAD_COMPLETE = EventFactory.createArrayBacked(
            Listener.class,
            listeners -> (providerDisplayName, success, shortReason) -> {
                for (Listener listener : listeners) {
                    listener.onUploadComplete(providerDisplayName, success, shortReason);
                }
            }
    );

    private CloudUploadEvents() {}
}