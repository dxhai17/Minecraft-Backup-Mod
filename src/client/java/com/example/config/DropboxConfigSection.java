package com.example.config;

import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.http.HttpRequest;

public final class DropboxConfigSection {

    private DropboxConfigSection() {}

    public static void addEntries(ConfigCategory category, ConfigEntryBuilder eb, BackupConfig config) {
        category.addEntry(eb.startStrField(
                        Component.translatable("backup.config.dropbox.access_token"),
                        config.dropboxToken)
                .setDefaultValue("")
                .setSaveConsumer(newValue -> config.dropboxToken = newValue)
                .build());
    }

    /**
     * Test kết nối bằng endpoint users/get_current_account — request đơn giản,
     * không side-effect, dùng đúng cho việc kiểm tra token còn hợp lệ hay không.
     */
    public static void testConnection(String accessToken) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.dropboxapi.com/2/users/get_current_account"))
                .header("Authorization", "Bearer " + accessToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        CloudConnectionTester.test("Dropbox", request);
    }
}