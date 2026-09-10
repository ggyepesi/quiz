package datasource;

/** A typed literal graph value retained in the provider's stable lexical representation. */
public record LiteralValue(String datatype, String lexicalForm) implements GraphValue {
    public LiteralValue(String lexicalForm) {
        this("", lexicalForm);
    }

    public LiteralValue {
        datatype = datatype == null ? "" : datatype.trim();
        lexicalForm = lexicalForm == null ? "" : lexicalForm;
    }
}
