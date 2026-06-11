package com.bap.dev.service;

import cell.panelxpro.metadata.IPanelMetadataService;
import panelxpro.metadata.dto.DtoGenerateResultDto;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * DTO 同步服务：负责面板 DTO 的拉取、比对和写入。
 */
public class DtoSyncService {

	private static final Logger LOG = Logger.getInstance(DtoSyncService.class);

	public enum DtoSyncStatus {
		NEW,        // 本地不存在
		MODIFIED,   // 内容不同
		UNCHANGED,  // 内容相同
		DELETED     // 远程不存在，本地多余
	}

	public static class DtoSyncEntry {
		private final DtoGenerateResultDto.DtoFileEntry remoteEntry;
		private final String localFilePath;
		private final DtoSyncStatus status;
		private final String localContent;

		public DtoSyncEntry(DtoGenerateResultDto.DtoFileEntry remoteEntry,
		                    String localFilePath,
		                    DtoSyncStatus status,
		                    String localContent) {
			this.remoteEntry = remoteEntry;
			this.localFilePath = localFilePath;
			this.status = status;
			this.localContent = localContent;
		}

		public DtoGenerateResultDto.DtoFileEntry getRemoteEntry() {
			return remoteEntry;
		}

		public String getLocalFilePath() {
			return localFilePath;
		}

		public DtoSyncStatus getStatus() {
			return status;
		}

		public String getLocalContent() {
			return localContent;
		}
	}

	/**
	 * 拉取远程 DTO 并与本地比对。
	 *
	 * @param service    RPC 服务代理
	 * @param moduleRoot 模块根目录（VirtualFile）
	 * @param domainCode 域编码
	 * @return 差异列表
	 */
	public List<DtoSyncEntry> fetchAndCompare(
			IPanelMetadataService service,
			VirtualFile moduleRoot,
			String domainCode
	) throws Exception {
		List<DtoSyncEntry> result = new ArrayList<>();
		Set<String> remoteFilePaths = new HashSet<>();

		DtoGenerateResultDto dtoResult = service.generateAllDtos(domainCode);
		if (dtoResult != null && dtoResult.getFiles() != null) {
			for (DtoGenerateResultDto.DtoFileEntry fileEntry : dtoResult.getFiles()) {
				String localPath = calculateLocalPath(moduleRoot, fileEntry);
				remoteFilePaths.add(localPath);
				DtoSyncEntry entry = compareWithLocal(fileEntry, localPath);
				result.add(entry);
			}
		}

		findDeletedFiles(moduleRoot, domainCode, remoteFilePaths, result);
		return result;
	}

	/**
	 * 将选中的文件写入本地磁盘。
	 * <p>
	 * 注意：本方法不处理线程调度，调用方需确保在正确线程中执行。
	 *
	 * @param project IntelliJ Project
	 * @param entries 用户选中的差异条目
	 * @return 成功写入的文件数量
	 */
	public int writeSelectedFiles(Project project, List<DtoSyncEntry> entries) {
		int successCount = 0;

		for (DtoSyncEntry entry : entries) {
			try {
				File ioFile = new File(entry.getLocalFilePath());

				if (entry.getStatus() == DtoSyncStatus.DELETED) {
					VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile);
					if (vf != null && vf.exists()) {
						vf.delete(this);
						successCount++;
					}
					continue;
				}

				File parentDir = ioFile.getParentFile();
				if (!parentDir.exists()) {
					parentDir.mkdirs();
				}

				byte[] content = entry.getRemoteEntry().getSourceCode().getBytes(StandardCharsets.UTF_8);
				Files.write(ioFile.toPath(), content);

				VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile);
				if (virtualFile != null) {
					virtualFile.refresh(false, false);
				}

				successCount++;
			} catch (Exception e) {
				LOG.error("写入文件失败: " + entry.getLocalFilePath(), e);
			}
		}

		return successCount;
	}

	private void findDeletedFiles(VirtualFile moduleRoot, String domainCode,
	                              Set<String> remoteFilePaths, List<DtoSyncEntry> result) {
		String[] packages = {
				"octocm." + domainCode + ".panel.dto",
				"octocm." + domainCode + ".panel.dto.nesting"
		};
		String basePath = moduleRoot.getPath() + "/src/src/";

		for (String pkg : packages) {
			String dirPath = basePath + pkg.replace('.', '/');
			File dir = new File(dirPath);
			if (!dir.exists() || !dir.isDirectory()) continue;

			File[] javaFiles = dir.listFiles((d, name) -> name.endsWith(".java"));
			if (javaFiles == null) continue;

			for (File javaFile : javaFiles) {
				String filePath = dirPath + "/" + javaFile.getName();
				if (remoteFilePaths.contains(filePath)) continue;

				try {
					String content = new String(Files.readAllBytes(javaFile.toPath()), StandardCharsets.UTF_8);
					DtoGenerateResultDto.DtoFileEntry syntheticEntry = new DtoGenerateResultDto.DtoFileEntry()
							.setFileName(javaFile.getName())
							.setPackageName(pkg)
							.setSourceCode("");
					result.add(new DtoSyncEntry(syntheticEntry, filePath, DtoSyncStatus.DELETED, content));
				} catch (Exception e) {
					LOG.warn("读取本地文件失败: " + filePath, e);
				}
			}
		}
	}

	private String calculateLocalPath(VirtualFile moduleRoot, DtoGenerateResultDto.DtoFileEntry entry) {
		String packagePath = entry.getPackageName().replace('.', '/');
		return moduleRoot.getPath() + "/src/src/" + packagePath + "/" + entry.getFileName();
	}

	private DtoSyncEntry compareWithLocal(DtoGenerateResultDto.DtoFileEntry remoteEntry, String localPath) {
		File localFile = new File(localPath);

		if (!localFile.exists()) {
			return new DtoSyncEntry(remoteEntry, localPath, DtoSyncStatus.NEW, null);
		}

		try {
			String localContent = new String(Files.readAllBytes(localFile.toPath()), StandardCharsets.UTF_8);
			String remoteContent = remoteEntry.getSourceCode();

			if (localContent.equals(remoteContent)) {
				return new DtoSyncEntry(remoteEntry, localPath, DtoSyncStatus.UNCHANGED, localContent);
			} else {
				return new DtoSyncEntry(remoteEntry, localPath, DtoSyncStatus.MODIFIED, localContent);
			}
		} catch (Exception e) {
			LOG.warn("读取本地文件失败: " + localPath, e);
			// 读取失败视为新文件
			return new DtoSyncEntry(remoteEntry, localPath, DtoSyncStatus.NEW, null);
		}
	}
}
