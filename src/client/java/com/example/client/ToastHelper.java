package com.example.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/**
 * Cô lập toàn bộ API Client-only (Minecraft, SystemToast) vào đúng 1 chỗ,
 * nằm trong src/client/java. Backup.java (chạy trên cả server lẫn client,
 * nằm ở src/main/java) KHÔNG được gọi Minecraft.getInstance() trực tiếp —
 * vì dòng đó sẽ null/crash trên dedicated server (không có client).
 * Backup.java phải tự kiểm tra server.isDedicatedServer() trước khi gọi bất
 * kỳ method nào ở đây, để đảm bảo class này chỉ được nạp khi chắc chắn đang
 * chạy phía có client (singleplayer/integrated server).
 * Toast dùng Component.translatable(key, args) thay vì Component.literal(...)
 * — tự tra en_us.json/vi_vn.json theo locale của client, KHÔNG ảnh hưởng
 * log console (LOGGER vẫn cứng tiếng Việt như cũ, không đi qua lang system
 * của Minecraft).
 */
public final class ToastHelper {

    private ToastHelper() {}

    /**
     * Hiện toast góc màn hình báo backup thành công, kèm tên world và kích cỡ.
     * Tự đẩy về render thread — an toàn gọi từ Server thread (nơi
     * ServerLifecycleEvents.SERVER_STOPPING đang chạy).
     */
    public static void showBackupSuccessToast(String worldName, String formattedSize) {
        Minecraft.getInstance().execute(() ->
                Minecraft.getInstance().gui.toastManager().addToast(
                        new SystemToast(
                                SystemToast.SystemToastId.NARRATOR_TOGGLE,
                                Component.translatable("backup.toast.local_success.title", worldName),
                                Component.translatable("backup.toast.local_success.description", formattedSize)
                        )
                )
        );
    }

    /**
     * Hiện toast báo kết quả upload cloud. Gọi được từ bất kỳ thread nào (kể
     * cả thread upload nền do CloudUploadManager tạo) vì tự đẩy về render
     * thread — giống showBackupSuccessToast(). KHÔNG thay thế toast đó: 2
     * toast độc lập, báo 2 việc khác nhau (backup local xong vs upload cloud
     * xong), có thể lần lượt hiện cả 2 trong 1 lượt Save & Quit.
     */
    public static void showUploadResultToast(String providerDisplayName, boolean success, String shortReason) {
        Component title = success
                ? Component.translatable("backup.toast.upload_success.title", providerDisplayName)
                : Component.translatable("backup.toast.upload_failure.title", providerDisplayName);
        Component description = success
                ? Component.empty()
                : Component.translatable("backup.toast.upload_failure.description",
                shortReason != null ? shortReason : "");

        Minecraft.getInstance().execute(() ->
                Minecraft.getInstance().gui.toastManager().addToast(
                        new SystemToast(
                                SystemToast.SystemToastId.NARRATOR_TOGGLE,
                                title,
                                description
                        )
                )
        );
    }
}