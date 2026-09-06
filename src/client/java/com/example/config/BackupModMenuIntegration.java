package com.example.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

public class BackupModMenuIntegration implements ModMenuApi {

    // Quy tắc đặt tên bucket chuẩn S3: 3-63 ký tự, chữ thường/số/dấu gạch ngang,
    // không bắt đầu/kết thúc bằng dấu gạch ngang. Áp dụng luôn cho Backblaze vì
    // Backblaze tương thích S3 API.
    private static final Pattern BUCKET_NAME_PATTERN =
            Pattern.compile("^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$");

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parentScreen -> {
            BackupConfig config = ConfigManager.INSTANCE;

            ConfigBuilder builder = ConfigBuilder.create()
                    .setParentScreen(parentScreen)
                    .setTitle(Component.literal("Cài đặt Backup World"));

            ConfigEntryBuilder eb = builder.entryBuilder();

            // ===================== CATEGORY: CHUNG =====================
            ConfigCategory general = builder.getOrCreateCategory(Component.literal("Chung"));

            general.addEntry(eb.startBooleanToggle(
                            Component.literal("Tự động backup khi Save & Quit"),
                            config.backupOnQuit)
                    .setDefaultValue(true)
                    .setSaveConsumer(newValue -> config.backupOnQuit = newValue)
                    .build());

            general.addEntry(eb.startIntSlider(
                            Component.literal("Số bản backup local giữ lại tối đa"),
                            config.maxLocalBackups, 1, 100)
                    .setDefaultValue(15)
                    .setSaveConsumer(newValue -> config.maxLocalBackups = newValue)
                    .build());

            // Enum selector: chỉ 1 provider được chọn tại 1 thời điểm.
            general.addEntry(eb.startEnumSelector(
                            Component.literal("Nhà cung cấp Cloud"),
                            BackupConfig.CloudProvider.class,
                            config.activeProvider)
                    .setDefaultValue(BackupConfig.CloudProvider.NONE)
                    .setEnumNameProvider(value -> switch ((BackupConfig.CloudProvider) value) {
                        case NONE -> Component.literal("Chỉ lưu local");
                        case DROPBOX -> Component.literal("Dropbox");
                        case BACKBLAZE -> Component.literal("Backblaze B2");
                    })
                    .setSaveConsumer(newValue -> config.activeProvider = newValue)
                    .build());

            // ===================== CATEGORY: DROPBOX =====================
            ConfigCategory dropbox = builder.getOrCreateCategory(Component.literal("Dropbox"));

            dropbox.addEntry(eb.startStrField(
                            Component.literal("Access Token"),
                            config.dropboxToken)
                    .setDefaultValue("")
                    // Validate: nếu Dropbox đang được chọn làm provider active, token bắt buộc không rỗng.
                    .setErrorSupplier(value -> {
                        if (config.activeProvider == BackupConfig.CloudProvider.DROPBOX
                                && (value == null || value.isBlank())) {
                            return Optional.of(Component.literal(
                                    "Dropbox đang được chọn làm nền tảng active — không được để trống Access Token."));
                        }
                        return Optional.empty();
                    })
                    .setSaveConsumer(newValue -> config.dropboxToken = newValue)
                    .build());

            // ===================== CATEGORY: BACKBLAZE B2 =====================
            ConfigCategory backblaze = builder.getOrCreateCategory(Component.literal("Backblaze B2"));

            backblaze.addEntry(eb.startStrField(
                            Component.literal("Endpoint"),
                            config.backblazeEndpoint)
                    .setDefaultValue("")
                    .setErrorSupplier(value -> {
                        if (config.activeProvider == BackupConfig.CloudProvider.BACKBLAZE
                                && (value == null || value.isBlank())) {
                            return Optional.of(Component.literal("Không được để trống khi Backblaze đang active."));
                        }
                        return Optional.empty();
                    })
                    .setSaveConsumer(newValue -> config.backblazeEndpoint = newValue)
                    .build());

            backblaze.addEntry(eb.startStrField(
                            Component.literal("Access Key (keyID)"),
                            config.backblazeKeyId)
                    .setDefaultValue("")
                    .setErrorSupplier(value -> {
                        if (config.activeProvider == BackupConfig.CloudProvider.BACKBLAZE
                                && (value == null || value.isBlank())) {
                            return Optional.of(Component.literal("Không được để trống khi Backblaze đang active."));
                        }
                        return Optional.empty();
                    })
                    .setSaveConsumer(newValue -> config.backblazeKeyId = newValue)
                    .build());

            backblaze.addEntry(eb.startStrField(
                            Component.literal("Secret Key (applicationKey)"),
                            config.backblazeApplicationKey)
                    .setDefaultValue("")
                    .setErrorSupplier(value -> {
                        if (config.activeProvider == BackupConfig.CloudProvider.BACKBLAZE
                                && (value == null || value.isBlank())) {
                            return Optional.of(Component.literal("Không được để trống khi Backblaze đang active."));
                        }
                        return Optional.empty();
                    })
                    .setSaveConsumer(newValue -> config.backblazeApplicationKey = newValue)
                    .build());

            backblaze.addEntry(eb.startStrField(
                            Component.literal("Tên bucket"),
                            config.backblazeBucket)
                    .setDefaultValue("")
                    .setErrorSupplier(value -> {
                        if (config.activeProvider != BackupConfig.CloudProvider.BACKBLAZE) {
                            return Optional.empty();
                        }
                        if (value == null || value.isBlank()) {
                            return Optional.of(Component.literal("Không được để trống khi Backblaze đang active."));
                        }
                        if (!BUCKET_NAME_PATTERN.matcher(value).matches()) {
                            return Optional.of(Component.literal(
                                    "Tên bucket không hợp lệ: chỉ chữ thường, số, dấu gạch ngang, 3-63 ký tự."));
                        }
                        return Optional.empty();
                    })
                    .setSaveConsumer(newValue -> config.backblazeBucket = newValue)
                    .build());

            // Đường dẫn lưu trữ là CỐ ĐỊNH theo world name (đúng quyết định thiết kế),
            // hiển thị dạng preview read-only để user hiểu file sẽ nằm ở đâu — không
            // cho gõ tay để tránh lỗi đường dẫn không hợp lệ.
            general.addEntry(eb.startTextDescription(
                            Component.literal(
                                    "Vị trí lưu trên cloud luôn tự động là: backups/<tên-thế-giới>/ — không thể tuỳ chỉnh tay để tránh lỗi đường dẫn."))
                    .build());

            builder.setSavingRunnable(() -> {
                ConfigManager.save();

                // Test kết nối thật ngay sau khi lưu — chỉ khi Backblaze đang active
                // và đã điền đủ key (tránh gọi API với field rỗng).
                if (config.activeProvider == BackupConfig.CloudProvider.BACKBLAZE
                        && config.isActiveProviderConfigured()) {
                    testBackblazeConnection(config.backblazeKeyId, config.backblazeApplicationKey);
                }
            });

            return builder.build();
        };
    }

    /**
     * Gọi thử API xác thực của Backblaze để báo ngay cho user biết key đúng/sai,
     * thay vì phải đợi tới lần Save & Quit thật mới biết. Chạy trên thread riêng
     * để không đứng hình UI trong lúc chờ mạng.
     */
    private void testBackblazeConnection(String keyId, String appKey) {
        new Thread(() -> {
            try {
                // Backblaze Native API yêu cầu Basic Auth (Base64 của keyId:appKey)
                String auth = Base64.getEncoder().encodeToString((keyId + ":" + appKey).getBytes());
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("https://api.backblazeb2.com/b2api/v3/b2_authorize_account"))
                        .header("Authorization", "Basic " + auth)
                        .GET()
                        .build();

                HttpResponse<String> response = HttpClient.newHttpClient()
                        .send(request, HttpResponse.BodyHandlers.ofString());

                boolean success = response.statusCode() == 200;
                String message = success
                        ? "Thành công: Key hợp lệ!"
                        : "Thất bại: Sai Key (mã " + response.statusCode() + ")";

                showToast("Test Backblaze", message);
            } catch (Exception e) {
                showToast("Lỗi mạng", "Không thể kết nối tới Backblaze để kiểm tra.");
            }
        }).start();
    }

    private void showToast(String title, String message) {
        // Đẩy về thread chính của game để tránh crash — API render/GUI của
        // Minecraft không an toàn khi gọi từ thread nền.
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