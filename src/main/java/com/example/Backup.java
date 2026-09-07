package com.example;

import com.example.backup.BackupCompleteEvents;
import com.example.backup.ZipUtils;
import com.example.config.ConfigManager;
import com.example.upload.CloudUploadManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class Backup implements ModInitializer {
	public static final String MOD_ID = "backup";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final GameRule<Boolean> AUTO_BACKUP = GameRuleBuilder
			.forBoolean(true)
			.category(GameRuleCategory.MISC)
			.buildAndRegister(id("auto_backup"));

	@Override
	public void onInitialize() {
		ConfigManager.load();
		LOGGER.info("Backup Mod initialized with GameRule!");

		// Đăng ký upload cloud ngay tại main (KHÔNG phải client) — vì upload
		// phải chạy được cả trên dedicated server, khác hẳn ToastHelper (chỉ
		// đăng ký bên BackupClient.java, src/client/java). Hai listener này
		// độc lập, không biết gì về nhau, cùng lắng nghe 1 event.
		BackupCompleteEvents.BACKUP_COMPLETE.register(CloudUploadManager::handleBackupComplete);

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			boolean gameRuleEnabled = server.getGameRules().get(AUTO_BACKUP);
			boolean configEnabled = ConfigManager.INSTANCE.backupOnQuit;

			if (!gameRuleEnabled || !configEnabled) {
				LOGGER.info("Auto backup đang tắt (gamerule={}, config={}), bỏ qua.",
						gameRuleEnabled, configEnabled);
				return;
			}

			String worldName = server.getWorldData().getLevelName();

			LOGGER.info("Bắt đầu backup local cho world '{}'...", worldName);
			File zipFile = ZipUtils.backupWorld(worldName, ConfigManager.INSTANCE.maxLocalBackups);

			// Phát sự kiện — Backup.java (main) không biết và không cần biết có
			// ai lắng nghe: CloudUploadManager (main, upload cloud) và/hoặc
			// BackupClient (client, hiện Toast) tự quyết định làm gì. Trên
			// dedicated server, BackupClient không được nạp nên chỉ
			// CloudUploadManager chạy — an toàn tuyệt đối.
			if (zipFile != null) {
				BackupCompleteEvents.BACKUP_COMPLETE.invoker().onBackupComplete(worldName, zipFile);
			}
		});
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}