package panelxpro.metadata.dto;

import java.io.Serializable;

public class DomainInfoDto implements Serializable {

	private static final long serialVersionUID = 1L;

	private String code;
	private String name;
	private String description;

	public String getCode() {
		return code;
	}

	public DomainInfoDto setCode(String code) {
		this.code = code;
		return this;
	}

	public String getName() {
		return name;
	}

	public DomainInfoDto setName(String name) {
		this.name = name;
		return this;
	}

	public String getDescription() {
		return description;
	}

	public DomainInfoDto setDescription(String description) {
		this.description = description;
		return this;
	}
}
