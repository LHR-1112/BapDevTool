package com.bap.dev.ui;

import com.bap.dev.i18n.BapBundle;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nullable;
import panelxpro.metadata.dto.DomainInfoDto;

import javax.swing.*;
import java.awt.*;
import java.util.List;

public class MetadataDomainDialog extends DialogWrapper {

    private final ComboBox<DomainInfoDto> domainComboBox;
    private final List<DomainInfoDto> domains;

    public MetadataDomainDialog(@Nullable Project project, List<DomainInfoDto> domains) {
        super(project);
        this.domains = domains;
        this.domainComboBox = new ComboBox<>(domains.toArray(new DomainInfoDto[0]));
        domainComboBox.setRenderer(new DomainListCellRenderer());
        setTitle(BapBundle.message("dialog.domain.config.title"));
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        return FormBuilder.createFormBuilder()
                .addLabeledComponent(BapBundle.message("dialog.domain.config.label"), domainComboBox)
                .getPanel();
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (domainComboBox.getSelectedItem() == null) {
            return new ValidationInfo(
                    BapBundle.message("dialog.domain.config.error.empty"), domainComboBox);
        }
        return null;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return domainComboBox;
    }

    public String getDomainCode() {
        DomainInfoDto selected = (DomainInfoDto) domainComboBox.getSelectedItem();
        return selected != null ? selected.getCode() : null;
    }

    private static class DomainListCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                                                      int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof DomainInfoDto) {
                DomainInfoDto dto = (DomainInfoDto) value;
                String label = dto.getName() != null && !dto.getName().isEmpty()
                        ? dto.getName() + " (" + dto.getCode() + ")"
                        : dto.getCode();
                setText(label);
            }
            return this;
        }
    }
}
