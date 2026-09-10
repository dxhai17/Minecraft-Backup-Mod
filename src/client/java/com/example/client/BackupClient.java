package com.example.client;

import com.example.backup.BackupCompleteEvents;
import com.example.backup.ZipUtils;
import net.fabricmc.api.ClientModInitializer;
import com.example.backup.CloudUploadEvents;

public class BackupClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		BackupCompleteEvents.BACKUP_COMPLETE.register((worldName, zipFile) -> {
			String formattedSize = ZipUtils.formatFileSize(zipFile.length());
			ToastHelper.showBackupSuccessToast(worldName, formattedSize);
		});
		CloudUploadEvents.UPLOAD_COMPLETE.register(ToastHelper::showUploadResultToast);
	}
}