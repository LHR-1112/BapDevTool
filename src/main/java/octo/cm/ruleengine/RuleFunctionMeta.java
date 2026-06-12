package octo.cm.ruleengine;

import java.io.Serializable;
import java.util.List;

public class RuleFunctionMeta implements Serializable {
    private static final long serialVersionUID = 1L;

    private String ruleCode;
    private String nameSpace;
    private String name;
    private String tag;
    private String description;
    private String codePath;
    private String invokeMethod;
    private List<InputParamMeta> inputParamMetas;
    private String returnType;
    private String useCase;
    private List<String> customLabels;

    public String getRuleCode() {
        return ruleCode;
    }

    public RuleFunctionMeta setRuleCode(String ruleCode) {
        this.ruleCode = ruleCode;
        return this;
    }

    public String getNameSpace() {
        return nameSpace;
    }

    public RuleFunctionMeta setNameSpace(String nameSpace) {
        this.nameSpace = nameSpace;
        return this;
    }

    public String getName() {
        return name;
    }

    public RuleFunctionMeta setName(String name) {
        this.name = name;
        return this;
    }

    public String getDescription() {
        return description;
    }

    public RuleFunctionMeta setDescription(String description) {
        this.description = description;
        return this;
    }

    public String getTag() {
        return tag;
    }

    public RuleFunctionMeta setTag(String tag) {
        this.tag = tag;
        return this;
    }

    public List<InputParamMeta> getInputParamMetas() {
        return inputParamMetas;
    }

    public RuleFunctionMeta setInputParamMetas(List<InputParamMeta> inputParamMetas) {
        this.inputParamMetas = inputParamMetas;
        return this;
    }

    public String getReturnType() {
        return returnType;
    }

    public RuleFunctionMeta setReturnType(String returnType) {
        this.returnType = returnType;
        return this;
    }

    public String getCodePath() {
        return codePath;
    }

    public RuleFunctionMeta setCodePath(String codePath) {
        this.codePath = codePath;
        return this;
    }

    public String getInvokeMethod() {
        return invokeMethod;
    }

    public RuleFunctionMeta setInvokeMethod(String invokeMethod) {
        this.invokeMethod = invokeMethod;
        return this;
    }

    public String getUseCase() {
        return useCase;
    }

    public RuleFunctionMeta setUseCase(String useCase) {
        this.useCase = useCase;
        return this;
    }

    public List<String> getCustomLabels() {
        return customLabels;
    }

    public RuleFunctionMeta setCustomLabels(List<String> customLabels) {
        this.customLabels = customLabels;
        return this;
    }
}
