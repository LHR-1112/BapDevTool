package octo.cm.ruleengine;

import java.io.Serializable;

public class InputParamMeta implements Serializable {
    private static final long serialVersionUID = 1L;

    private String name;
    private String type;
    private String desc;
    private boolean nullable;

    public String getName() {
        return name;
    }

    public InputParamMeta setName(String name) {
        this.name = name;
        return this;
    }

    public String getType() {
        return type;
    }

    public InputParamMeta setType(String type) {
        this.type = type;
        return this;
    }

    public String getDesc() {
        return desc;
    }

    public InputParamMeta setDesc(String desc) {
        this.desc = desc;
        return this;
    }

    public boolean isNullable() {
        return nullable;
    }

    public InputParamMeta setNullable(boolean nullable) {
        this.nullable = nullable;
        return this;
    }
}
