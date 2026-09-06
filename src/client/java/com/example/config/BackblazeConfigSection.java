package com.example.config;

import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Base64;
import java.util.regex.Pattern;

public final class BackblazeConfigSection {

    // Quy tắc đặt tên bucket chuẩn S3: 3-63 ký tự, chữ thường/số/dấu gạch ngang,
    // không bắt đầu/kết thúc bằng dấu gạch ngang. Áp dụng luôn cho Backblaze vì
    // Backblaze tương thích S3 API.
    public static final Pattern BUCKET_NAME_PATTERN =
            Pattern.compile("^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$");

    private BackblazeConfigSection() {}

    public static void addEntries(ConfigCategory category, ConfigEntryBuilder eb, BackupConfig config) {
        category.addEntry(eb.startStrField(
                        Component.literal("Endpoint"),
                        config.backblazeEndpoint)
                .setDefaultValue("")
                .setSaveConsumer(newValue -> config.backblazeEndpoint = newValue)
                .build());

        category.addEntry(eb.startStrField(
                        Component.literal("Access Key (keyID)"),
                        config.backblazeKeyId)
                .setDefaultValue("")
                .setSaveConsumer(newValue -> config.backblazeKeyId = newValue)
                .build());

        category.addEntry(eb.startStrField(
                        Component.literal("Secret Key (applicationKey)"),
                        config.backblazeApplicationKey)
                .setDefaultValue("")
                .setSaveConsumer(newValue -> config.backblazeApplicationKey = newValue)
                .build());

        category.addEntry(eb.startStrField(
                        Component.literal("Tên bucket"),
                        config.backblazeBucket)
                .setDefaultValue("")
                .setSaveConsumer(newValue -> config.backblazeBucket = newValue)
                .build());
    }

    /**
     * Test kết nối bằng endpoint b2_authorize_account (Backblaze Native API) —
     * giữ nguyên đúng logic gốc: Basic Auth = Base64 của keyId:appKey.
     */
    public static void testConnection(String keyId, String appKey) {
        String auth = Base64.getEncoder().encodeToString((keyId + ":" + appKey).getBytes());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.backblazeb2.com/b2api/v3/b2_authorize_account"))
                .header("Authorization", "Basic " + auth)
                .GET()
                .build();

        CloudConnectionTester.test("Test Backblaze", request);
    }
}