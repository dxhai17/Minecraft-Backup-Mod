package com.example.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/**
 * Cô lập toàn bộ API Client-only (Minecraft, SystemToast) vào đúng 1 chỗ,
 * nằm trong src/client/java. Backup.java (chạy trên cả server lẫn client,
 * nằm ở src/main/java) KHÔNG được gọi Minecraft.getInstance() trực tiếp —
 * vì dòng đó sẽ null/crash trên dedicated server (không có client).
 *
 * Backup.java phải tự kiểm tra server.isDedicatedServer() trước khi gọi bất
 * kỳ method nào ở đây, để đảm bảo class này chỉ được nạp khi chắc chắn đang
 * chạy phía có client (singleplayer/integrated server).
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
                                Component.literal("Đã sao lưu thế giới: " + worldName),
                                Component.literal("Kích cỡ: " + formattedSize)
                        )
                )
        );
    }
}