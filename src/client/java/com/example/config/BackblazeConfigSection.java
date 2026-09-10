package com.example.config;

import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

public final class BackblazeConfigSection {

    public static final Pattern BUCKET_NAME_PATTERN =
            Pattern.compile("^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$");

    private BackblazeConfigSection() {}

    public static void addEntries(ConfigCategory category, ConfigEntryBuilder eb, BackupConfig config) {
        category.addEntry(eb.startStrField(
                        Component.translatable("backup.config.backblaze.endpoint"),
                        config.backblazeEndpoint)
                .setDefaultValue("")
                .setSaveConsumer(newValue -> config.backblazeEndpoint = newValue)
                .build());

        category.addEntry(eb.startStrField(
                        Component.translatable("backup.config.backblaze.key_id"),
                        config.backblazeKeyId)
                .setDefaultValue("")
                .setSaveConsumer(newValue -> config.backblazeKeyId = newValue)
                .build());

        category.addEntry(eb.startStrField(
                        Component.translatable("backup.config.backblaze.application_key"),
                        config.backblazeApplicationKey)
                .setDefaultValue("")
                .setSaveConsumer(newValue -> config.backblazeApplicationKey = newValue)
                .build());

        category.addEntry(eb.startStrField(
                        Component.translatable("backup.config.backblaze.bucket_name"),
                        config.backblazeBucket)
                .setDefaultValue("")
                .setSaveConsumer(newValue -> config.backblazeBucket = newValue)
                .build());
    }

    /**
     * Test kết nối bằng Native B2 API — endpoint b2_authorize_account.
     * Đây là request đúng nghĩa "test key hợp lệ hay không": không cần biết
     * bucket, không side-effect, an toàn gọi lặp lại nhiều lần lúc người
     * dùng đang gõ thử config. Không kiểm tra tên bucket ở bước này — việc
     * đó đã được validate riêng bằng BUCKET_NAME_PATTERN trước khi lưu.
     * Dùng chung CloudConnectionTester (giống Dropbox)
     */
    public static void testConnection(String endpoint, String keyId, String applicationKey, String bucketName) {
        String credentials = Base64.getEncoder().encodeToString(
                (keyId + ":" + applicationKey).getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.backblazeb2.com/b2api/v3/b2_authorize_account"))
                .header("Authorization", "Basic " + credentials)
                .GET()
                .build();

        CloudConnectionTester.test("Backblaze", request);
    }
}