package panelxpro.metadata.dto;

import java.io.Serializable;
import java.util.List;

public class PanelFieldDto implements Serializable {

	private static final long serialVersionUID = 1L;

	private String code;
	private String name;
	private String dataType;
	private String description;
	private boolean required;
	private List<PanelFieldDto> nestingFields;
	private String assocModelId;

	public String getCode() {
		return code;
	}

	public PanelFieldDto setCode(String code) {
		this.code = code;
		return this;
	}

	public String getName() {
		return name;
	}

	public PanelFieldDto setName(String name) {
		this.name = name;
		return this;
	}

	public String getDataType() {
		return dataType;
	}

	public PanelFieldDto setDataType(String dataType) {
		this.dataType = dataType;
		return this;
	}

	public String getDescription() {
		return description;
	}

	public PanelFieldDto setDescription(String description) {
		this.description = description;
		return this;
	}

	public boolean isRequired() {
		return required;
	}

	public PanelFieldDto setRequired(boolean required) {
		this.required = required;
		return this;
	}

	public List<PanelFieldDto> getNestingFields() {
		return nestingFields;
	}

	public PanelFieldDto setNestingFields(List<PanelFieldDto> nestingFields) {
		this.nestingFields = nestingFields;
		return this;
	}

	public String getAssocModelId() {
		return assocModelId;
	}

	public PanelFieldDto setAssocModelId(String assocModelId) {
		this.assocModelId = assocModelId;
		return this;
	}
}
