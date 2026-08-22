package work;

import java.util.Map;

public record QueryTemplate(
        String name,
        String template) {

    public String render(Map<String, ?> args) {
        String out = template == null ? "" : template;

        if (args == null) {
            return out;
        }

        for (Map.Entry<String, ?> e : args.entrySet()) {
            String key = "${" + e.getKey() + "}";
            String value = e.getValue() == null
                    ? ""
                    : String.valueOf(e.getValue());

            out = out.replace(key, value);
        }

        return out;
    }
}