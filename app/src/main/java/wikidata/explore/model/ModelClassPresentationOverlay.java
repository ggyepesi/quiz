package wikidata.explore.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Domain-local presentation only; never changes an imported declaration's identity. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public final class ModelClassPresentationOverlay {
    private String classDeclarationId = "";
    private CanonicalSpec.DisplayNameMode displayNameMode =
            CanonicalSpec.DisplayNameMode.LABEL;
    private String displayNameField = "";
    private String displayNameTemplate = "";

    public ModelClassPresentationOverlay() { }

    public ModelClassPresentationOverlay(String classDeclarationId,
            CanonicalSpec.DisplayNameMode mode, String field, String template) {
        classDeclarationId(classDeclarationId);
        displayNameMode(mode);
        displayNameField(field);
        displayNameTemplate(template);
    }

    public String classDeclarationId() { return DeclarationIds.clean(classDeclarationId); }
    public void classDeclarationId(String value) {
        classDeclarationId = DeclarationIds.clean(value);
    }
    public CanonicalSpec.DisplayNameMode displayNameMode() { return displayNameMode; }
    public void displayNameMode(CanonicalSpec.DisplayNameMode value) {
        displayNameMode = value == null ? CanonicalSpec.DisplayNameMode.LABEL : value;
    }
    public String displayNameField() { return clean(displayNameField); }
    public void displayNameField(String value) { displayNameField = clean(value); }
    public String displayNameTemplate() { return displayNameTemplate == null ? "" : displayNameTemplate; }
    public void displayNameTemplate(String value) {
        displayNameTemplate = value == null ? "" : value;
    }
    public ModelClassPresentationOverlay copy() {
        return new ModelClassPresentationOverlay(classDeclarationId(), displayNameMode(),
                displayNameField(), displayNameTemplate());
    }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
