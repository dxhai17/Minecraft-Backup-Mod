package com.example;

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

		// Trigger đúng lúc bấm "Save & Quit to Title" — bản chất singleplayer
		// vẫn chạy 1 integrated server ngầm nên sự kiện này vẫn fire bình thường.
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			boolean gameRuleEnabled = server.getGameRules().get(AUTO_BACKUP);
			boolean configEnabled = ConfigManager.INSTANCE.backupOnQuit;

			if (!gameRuleEnabled || !configEnabled) {
				LOGGER.info("Auto backup đang tắt (gamerule={}, config={}), bỏ qua.",
						gameRuleEnabled, configEnabled);
				return;
			}

			// Server có thể có nhiều level (world/nether/end), nhưng "tên world"
			// mà người chơi hiểu là tên thư mục save gốc — lấy đúng qua getWorldData().
			String worldName = server.getWorldData().getLevelName();

			LOGGER.info("Bắt đầu backup local cho world '{}'...", worldName);
			ZipUtils.backupWorld(worldName, ConfigManager.INSTANCE.maxLocalBackups);
			// Bước upload lên Dropbox/Backblaze sẽ nối tiếp ở đây sau, dùng file
			// trả về từ backupWorld(...) làm input cho CloudUploader.
		});
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}