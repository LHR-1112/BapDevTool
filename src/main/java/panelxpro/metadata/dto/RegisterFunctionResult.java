package panelxpro.metadata.dto;

import octo.cm.ruleengine.RuleFunctionMeta;

import java.io.Serializable;
import java.util.List;

public class RegisterFunctionResult implements Serializable {

	private static final long serialVersionUID = 1L;

	private boolean success;
	private String message;
	private List<RuleFunctionMeta> functions;

	public boolean isSuccess() {
		return success;
	}

	public RegisterFunctionResult setSuccess(boolean success) {
		this.success = success;
		return this;
	}

	public String getMessage() {
		return message;
	}

	public RegisterFunctionResult setMessage(String message) {
		this.message = message;
		return this;
	}

	public List<RuleFunctionMeta> getFunctions() {
		return functions;
	}

	public RegisterFunctionResult setFunctions(List<RuleFunctionMeta> functions) {
		this.functions = functions;
		return this;
	}
}
