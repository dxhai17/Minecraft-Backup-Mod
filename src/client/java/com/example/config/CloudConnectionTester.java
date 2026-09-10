package com.example.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Logic test-connection dùng chung cho mọi provider — chỉ khác nhau ở request
 * (URL + auth header), phần gọi HTTP/xử lý response/hiện Toast giống hệt nhau
 * nên tách ra đây, tránh lặp code giữa Dropbox và Backblaze.
 */
public final class CloudConnectionTester {

    private CloudConnectionTester() {}

    /**
     * Gọi 1 request GET/POST đã build sẵn, chạy trên thread riêng để không đứng
     * hình UI trong lúc chờ mạng, rồi hiện Toast báo thành công/thất bại.
     *
     * @param providerName tên nền tảng thuần (vd: "Dropbox", "Backblaze") — dùng
     *                     để build cả title translatable ("Test %s") và message
     *                     lỗi mạng, KHÔNG truyền text đã dịch sẵn ở đây.
     * @param request      request đã build sẵn (khác nhau giữa các provider)
     */
    public static void test(String providerName, HttpRequest request) {
        new Thread(() -> {
            Component title = Component.translatable("backup.config.test_connection.title", providerName);

            try {
                HttpResponse<String> response = HttpClient.newHttpClient()
                        .send(request, HttpResponse.BodyHandlers.ofString());

                boolean success = response.statusCode() == 200;
                Component message = success
                        ? Component.translatable("backup.config.test_connection.success")
                        : Component.translatable("backup.config.test_connection.failure", response.statusCode());

                showToast(title, message);
            } catch (Exception e) {
                showToast(
                        Component.translatable("backup.config.test_connection.network_error.title"),
                        Component.translatable("backup.config.test_connection.network_error.description", providerName)
                );
            }
        }).start();
    }

    private static void showToast(Component title, Component message) {
        Minecraft.getInstance().execute(() ->
                Minecraft.getInstance().gui.toastManager().addToast(
                        new SystemToast(
                                SystemToast.SystemToastId.NARRATOR_TOGGLE,
                                title,
                                message
                        )
                )
        );
    }
}