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
     * @param toastTitle tên nền tảng hiển thị trên Toast (vd: "Test Dropbox")
     * @param request    request đã build sẵn (khác nhau giữa các provider)
     */
    public static void test(String toastTitle, HttpRequest request) {
        new Thread(() -> {
            try {
                HttpResponse<String> response = HttpClient.newHttpClient()
                        .send(request, HttpResponse.BodyHandlers.ofString());

                boolean success = response.statusCode() == 200;
                String message = success
                        ? "Thành công: Key hợp lệ!"
                        : "Thất bại: Sai Key (mã " + response.statusCode() + ")";

                showToast(toastTitle, message);
            } catch (Exception e) {
                showToast("Lỗi mạng", "Không thể kết nối tới " + toastTitle + " để kiểm tra.");
            }
        }).start();
    }

    private static void showToast(String title, String message) {
        Minecraft.getInstance().execute(() ->
                Minecraft.getInstance().gui.toastManager().addToast(
                        new SystemToast(
                                SystemToast.SystemToastId.NARRATOR_TOGGLE,
                                Component.literal(title),
                                Component.literal(message)
                        )
                )
        );
    }
}