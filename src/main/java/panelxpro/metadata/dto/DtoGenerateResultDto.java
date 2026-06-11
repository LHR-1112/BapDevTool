package panelxpro.metadata.dto;

import java.io.Serializable;
import java.util.List;

public class DtoGenerateResultDto implements Serializable {

	private static final long serialVersionUID = 1L;

	private List<DtoFileEntry> files;

	public List<DtoFileEntry> getFiles() {
		return files;
	}

	public DtoGenerateResultDto setFiles(List<DtoFileEntry> files) {
		this.files = files;
		return this;
	}

	public static class DtoFileEntry implements Serializable {

		private static final long serialVersionUID = 1L;

		private String fileName;
		private String packageName;
		private String sourceCode;

		public String getFileName() {
			return fileName;
		}

		public DtoFileEntry setFileName(String fileName) {
			this.fileName = fileName;
			return this;
		}

		public String getPackageName() {
			return packageName;
		}

		public DtoFileEntry setPackageName(String packageName) {
			this.packageName = packageName;
			return this;
		}

		public String getSourceCode() {
			return sourceCode;
		}

		public DtoFileEntry setSourceCode(String sourceCode) {
			this.sourceCode = sourceCode;
			return this;
		}
	}
}
