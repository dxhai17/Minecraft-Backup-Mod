package com.example;

import com.example.backup.BackupCompleteEvents;
import com.example.backup.ZipUtils;
import com.example.config.ConfigManager;
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

			// Chỉ phát sự kiện — Backup.java (main) không biết và không cần biết
			// có ai lắng nghe hay không. BackupClient.java (client) tự quyết định
			// có hiện Toast hay không; trên dedicated server không ai đăng ký
			// lắng nghe (vì BackupClient.java không được nạp), nên an toàn tuyệt
			// đối, không cần tự kiểm tra isDedicatedServer() ở đây nữa.
			if (zipFile != null) {
				BackupCompleteEvents.BACKUP_COMPLETE.invoker().onBackupComplete(worldName, zipFile);
			}
			// Bước upload lên Dropbox/Backblaze sẽ nối tiếp ở đây sau, dùng
			// zipFile làm input cho CloudUploader.
		});
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}