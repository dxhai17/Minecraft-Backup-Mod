package com.example.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

public class BackupModMenuIntegration implements ModMenuApi {

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

            general.addEntry(eb.startTextDescription(
                            Component.literal(
                                    "Vị trí lưu trên cloud luôn tự động là: backups/<tên-thế-giới>/ — không thể tuỳ chỉnh tay để tránh lỗi đường dẫn."))
                    .build());

            // ===================== CATEGORY: DROPBOX =====================
            ConfigCategory dropbox = builder.getOrCreateCategory(Component.literal("Dropbox"));
            DropboxConfigSection.addEntries(dropbox, eb, config);

            // ===================== CATEGORY: BACKBLAZE B2 =====================
            ConfigCategory backblaze = builder.getOrCreateCategory(Component.literal("Backblaze B2"));
            BackblazeConfigSection.addEntries(backblaze, eb, config);

            builder.setSavingRunnable(() -> {
                // QUAN TRỌNG: Cloth Config luôn chạy setSaveConsumer của MỌI field
                // trước, rồi mới chạy savingRunnable này — nên tại đây config đã
                // được gán đúng giá trị mới nhất người dùng vừa nhập/chọn, kể cả
                // vừa đổi tab. Validate trực tiếp trên config, KHÔNG cần biến
                // "pending" trung gian nào.
                if (!config.isActiveProviderConfigured()) {
                    showToast("Không thể lưu",
                            "Nền tảng " + config.activeProvider + " đang thiếu thông tin xác thực bắt buộc.");
                    return; // KHÔNG gọi ConfigManager.save() — chặn ghi xuống đĩa.
                }

                if (config.activeProvider == BackupConfig.CloudProvider.BACKBLAZE
                        && !BackblazeConfigSection.BUCKET_NAME_PATTERN.matcher(config.backblazeBucket).matches()) {
                    showToast("Không thể lưu", "Tên bucket không hợp lệ.");
                    return;
                }

                ConfigManager.save();

                switch (config.activeProvider) {
                    case DROPBOX -> DropboxConfigSection.testConnection(config.dropboxToken);
                    case BACKBLAZE -> BackblazeConfigSection.testConnection(
                            config.backblazeEndpoint, config.backblazeKeyId,
                            config.backblazeApplicationKey, config.backblazeBucket);
                    case NONE -> { /* không cần test kết nối */ }
                }
            });

            return builder.build();
        };
    }

    private void showToast(String title, String message) {
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