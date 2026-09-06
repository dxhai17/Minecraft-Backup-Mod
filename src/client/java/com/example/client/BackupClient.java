package com.example.client;

import com.example.backup.BackupCompleteEvents;
import com.example.backup.ZipUtils;
import net.fabricmc.api.ClientModInitializer;

public class BackupClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Đăng ký lắng nghe ngay tại đây — file này nằm ở src/client/java, cùng
		// phía với ToastHelper, nên gọi thẳng được, không vi phạm giới hạn
		// splitEnvironmentSourceSets() như khi Backup.java (main) cố gọi trực tiếp.
		BackupCompleteEvents.BACKUP_COMPLETE.register((worldName, zipFile) -> {
			String formattedSize = ZipUtils.formatFileSize(zipFile.length());
			ToastHelper.showBackupSuccessToast(worldName, formattedSize);
		});
	}
}