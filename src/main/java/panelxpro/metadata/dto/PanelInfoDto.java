package panelxpro.metadata.dto;

import java.io.Serializable;

public class PanelInfoDto implements Serializable {

	private static final long serialVersionUID = 1L;

	private String panelCode;
	private String panelName;
	private String mainModelId;
	private String nestingModelId;

	public String getPanelCode() {
		return panelCode;
	}

	public PanelInfoDto setPanelCode(String panelCode) {
		this.panelCode = panelCode;
		return this;
	}

	public String getPanelName() {
		return panelName;
	}

	public PanelInfoDto setPanelName(String panelName) {
		this.panelName = panelName;
		return this;
	}

	public String getMainModelId() {
		return mainModelId;
	}

	public PanelInfoDto setMainModelId(String mainModelId) {
		this.mainModelId = mainModelId;
		return this;
	}

	public String getNestingModelId() {
		return nestingModelId;
	}

	public PanelInfoDto setNestingModelId(String nestingModelId) {
		this.nestingModelId = nestingModelId;
		return this;
	}
}
