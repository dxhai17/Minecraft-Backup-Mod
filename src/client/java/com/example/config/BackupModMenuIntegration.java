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
                    .setTitle(Component.translatable("backup.config.title"));

            ConfigEntryBuilder eb = builder.entryBuilder();

            // ===================== CATEGORY: CHUNG =====================
            ConfigCategory general = builder.getOrCreateCategory(Component.translatable("backup.config.category.general"));

            general.addEntry(eb.startBooleanToggle(
                            Component.translatable("backup.config.general.backup_on_quit"),
                            config.backupOnQuit)
                    .setDefaultValue(true)
                    .setSaveConsumer(newValue -> config.backupOnQuit = newValue)
                    .build());

            general.addEntry(eb.startIntSlider(
                            Component.translatable("backup.config.general.max_local_backups"),
                            config.maxLocalBackups, 1, 100)
                    .setDefaultValue(15)
                    .setSaveConsumer(newValue -> config.maxLocalBackups = newValue)
                    .build());

            general.addEntry(eb.startEnumSelector(
                            Component.translatable("backup.config.general.active_provider"),
                            BackupConfig.CloudProvider.class,
                            config.activeProvider)
                    .setDefaultValue(BackupConfig.CloudProvider.NONE)
                    .setEnumNameProvider(value -> switch ((BackupConfig.CloudProvider) value) {
                        case NONE -> Component.translatable("backup.config.provider.none");
                        case DROPBOX -> Component.translatable("backup.config.provider.dropbox");
                        case BACKBLAZE -> Component.translatable("backup.config.provider.backblaze");
                    })
                    .setSaveConsumer(newValue -> config.activeProvider = newValue)
                    .build());

            // ===================== CATEGORY: DROPBOX =====================
            ConfigCategory dropbox = builder.getOrCreateCategory(Component.translatable("backup.config.category.dropbox"));
            DropboxConfigSection.addEntries(dropbox, eb, config);

            // ===================== CATEGORY: BACKBLAZE B2 =====================
            ConfigCategory backblaze = builder.getOrCreateCategory(Component.translatable("backup.config.category.backblaze"));
            BackblazeConfigSection.addEntries(backblaze, eb, config);

            builder.setSavingRunnable(() -> {
                if (!config.isActiveProviderConfigured()) {
                    showToast(
                            Component.translatable("backup.config.toast.save_failed.title"),
                            Component.translatable("backup.config.toast.save_failed.missing_credentials",
                                    config.activeProvider));
                    return; // KHÔNG gọi ConfigManager.save() — chặn ghi xuống đĩa.
                }

                if (config.activeProvider == BackupConfig.CloudProvider.BACKBLAZE
                        && !BackblazeConfigSection.BUCKET_NAME_PATTERN.matcher(config.backblazeBucket).matches()) {
                    showToast(
                            Component.translatable("backup.config.toast.save_failed.title"),
                            Component.translatable("backup.config.toast.save_failed.invalid_bucket"));
                    return;
                }

                ConfigManager.save();

                switch (config.activeProvider) {
                    case DROPBOX -> DropboxConfigSection.testConnection(config);
                    case BACKBLAZE -> BackblazeConfigSection.testConnection(
                            config.backblazeEndpoint, config.backblazeKeyId,
                            config.backblazeApplicationKey, config.backblazeBucket);
                    case NONE -> { /* không cần test kết nối */ }
                }
            });

            return builder.build();
        };
    }

    private void showToast(Component title, Component message) {
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