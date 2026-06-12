package panelxpro.metadata.dto;

import octo.cm.ruleengine.RuleFunctionMeta;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

public class ListFunctionsResult implements Serializable {

	private static final long serialVersionUID = 1L;

	private List<RuleFunctionMeta> functions;
	private List<String> functionNames;
	private Map<String, String> errorFunctions;
	private int totalCount;

	public List<RuleFunctionMeta> getFunctions() { return functions; }
	public ListFunctionsResult setFunctions(List<RuleFunctionMeta> functions) { this.functions = functions; return this; }

	public List<String> getFunctionNames() { return functionNames; }
	public ListFunctionsResult setFunctionNames(List<String> functionNames) { this.functionNames = functionNames; return this; }

	public Map<String, String> getErrorFunctions() { return errorFunctions; }
	public ListFunctionsResult setErrorFunctions(Map<String, String> errorFunctions) { this.errorFunctions = errorFunctions; return this; }

	public int getTotalCount() { return totalCount; }
	public ListFunctionsResult setTotalCount(int totalCount) { this.totalCount = totalCount; return this; }
}
