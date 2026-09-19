package com.example.config;

import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.impl.builders.SubCategoryBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class DropboxConfigSection {

    private DropboxConfigSection() {}

    /**
     * UI chia làm 2 phần theo BackupConfig.DropboxAuthMode:
     *  - 1 enum selector ở trên cùng để CHỌN chế độ đang dùng (chỉ chế độ này
     *    được validate/dùng lúc backup thật, xem BackupConfig.isActiveProviderConfigured).
     *  - 2 subcategory gấp/mở được bên dưới (startSubCategory — đóng mặc định,
     *    người dùng tự mở phần cần điền), chứa field riêng từng chế độ. Cả 2
     *    bộ field đều hiển thị được cùng lúc, nhưng chỉ field của chế độ ĐANG
     *    CHỌN mới thực sự được dùng khi backup, tránh nhầm lẫn "điền rồi mà
     *    không thấy dùng".
     */
    public static void addEntries(ConfigCategory category, ConfigEntryBuilder eb, BackupConfig config) {
        category.addEntry(eb.startEnumSelector(
                        Component.translatable("backup.config.dropbox.auth_mode"),
                        BackupConfig.DropboxAuthMode.class,
                        config.dropboxAuthMode)
                .setDefaultValue(BackupConfig.DropboxAuthMode.ACCESS_TOKEN)
                .setEnumNameProvider(value -> switch ((BackupConfig.DropboxAuthMode) value) {
                    case ACCESS_TOKEN -> Component.translatable("backup.config.dropbox.auth_mode.access_token");
                    case REFRESH_TOKEN -> Component.translatable("backup.config.dropbox.auth_mode.refresh_token");
                })
                .setSaveConsumer(newValue -> config.dropboxAuthMode = newValue)
                .build());

        SubCategoryBuilder accessTokenGroup = eb.startSubCategory(
                        Component.translatable("backup.config.dropbox.auth_mode.access_token"))
                .setExpanded(config.dropboxAuthMode == BackupConfig.DropboxAuthMode.ACCESS_TOKEN);
        accessTokenGroup.add(eb.startStrField(
                        Component.translatable("backup.config.dropbox.access_token"),
                        config.dropboxToken)
                .setDefaultValue("")
                .setSaveConsumer(newValue -> config.dropboxToken = newValue)
                .build());
        category.addEntry(accessTokenGroup.build());

        SubCategoryBuilder refreshTokenGroup = eb.startSubCategory(
                        Component.translatable("backup.config.dropbox.auth_mode.refresh_token"))
                .setExpanded(config.dropboxAuthMode == BackupConfig.DropboxAuthMode.REFRESH_TOKEN);
        refreshTokenGroup.add(eb.startStrField(
                        Component.translatable("backup.config.dropbox.app_key"),
                        config.dropboxAppKey)
                .setDefaultValue("")
                .setSaveConsumer(newValue -> config.dropboxAppKey = newValue)
                .build());
        refreshTokenGroup.add(eb.startStrField(
                        Component.translatable("backup.config.dropbox.app_secret"),
                        config.dropboxAppSecret)
                .setDefaultValue("")
                .setSaveConsumer(newValue -> config.dropboxAppSecret = newValue)
                .build());
        refreshTokenGroup.add(eb.startStrField(
                        Component.translatable("backup.config.dropbox.refresh_token"),
                        config.dropboxRefreshToken)
                .setDefaultValue("")
                .setSaveConsumer(newValue -> config.dropboxRefreshToken = newValue)
                .build());
        category.addEntry(refreshTokenGroup.build());
    }

    /**
     * Test theo ĐÚNG chế độ đang chọn (config.dropboxAuthMode) — không test cả
     * 2 chế độ cùng lúc, vì chỉ chế độ đang chọn mới thực sự được dùng lúc
     * backup thật. Toast báo rõ đang test loại nào (Access Token / Refresh
     * Token) để người dùng không nhầm lẫn khi cả 2 bộ field đều đã điền.
     */
    public static void testConnection(BackupConfig config) {
        switch (config.dropboxAuthMode) {
            case ACCESS_TOKEN -> testAccessToken(config.dropboxToken);
            case REFRESH_TOKEN -> testRefreshToken(
                    config.dropboxAppKey, config.dropboxAppSecret, config.dropboxRefreshToken);
        }
    }

    /**
     * Test Access Token bằng endpoint users/get_current_account — request đơn
     * giản, không side-effect, dùng đúng cho việc kiểm tra token còn hợp lệ
     * hay không. Dùng chung CloudConnectionTester (giống Backblaze).
     */
    private static void testAccessToken(String accessToken) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.dropboxapi.com/2/users/get_current_account"))
                .header("Authorization", "Bearer " + accessToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        CloudConnectionTester.test("Dropbox (Access Token)", request);
    }

    /**
     * Test Refresh Token KHÔNG dùng chung CloudConnectionTester được vì cần 2
     * bước nối tiếp (đổi lấy access token trước, rồi mới coi là hợp lệ) thay
     * vì 1 HttpRequest đơn — CloudConnectionTester chỉ hỗ trợ 1 request. Tự
     * chạy thread riêng + hiện Toast, theo đúng pattern showToast đã dùng
     * nhất quán ở các ConfigSection khác trong project.
     */
    private static void testRefreshToken(String appKey, String appSecret, String refreshToken) {
        new Thread(() -> {
            try {
                String basicAuth = Base64.getEncoder().encodeToString(
                        (appKey + ":" + appSecret).getBytes(StandardCharsets.UTF_8));
                String requestBody = "grant_type=refresh_token&refresh_token=" + refreshToken;

                HttpRequest refreshRequest = HttpRequest.newBuilder()
                        .uri(URI.create("https://api.dropboxapi.com/oauth2/token"))
                        .header("Authorization", "Basic " + basicAuth)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();

                HttpResponse<String> refreshResponse = HttpClient.newHttpClient()
                        .send(refreshRequest, HttpResponse.BodyHandlers.ofString());

                if (refreshResponse.statusCode() != 200) {
                    showToast(
                            Component.translatable("backup.config.test_connection.title", "Dropbox (Refresh Token)"),
                            Component.translatable("backup.config.test_connection.failure", refreshResponse.statusCode()));
                    return;
                }

                // Refresh thành công đã đủ để coi là "key hợp lệ" — không cần
                // gọi thêm users/get_current_account, vì đổi được access token
                // mới từ refresh token là bằng chứng App Key/Secret/Refresh
                // Token đều đúng và còn quyền truy cập.
                showToast(
                        Component.translatable("backup.config.test_connection.title", "Dropbox (Refresh Token)"),
                        Component.translatable("backup.config.test_connection.success"));

            } catch (Exception e) {
                showToast(
                        Component.translatable("backup.config.test_connection.network_error.title"),
                        Component.translatable("backup.config.test_connection.network_error.description",
                                "Dropbox (Refresh Token)"));
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