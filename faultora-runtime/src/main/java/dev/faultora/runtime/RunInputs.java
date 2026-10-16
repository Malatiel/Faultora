package dev.faultora.runtime;

import dev.faultora.spec.model.InputDeclaration;
import dev.faultora.spec.model.ScenarioDocument;

import java.util.LinkedHashMap;
import java.util.Map;

/** Declared defaults, required values and unknown-name checks shared by local adapters. */
public final class RunInputs {
    public static final class InvalidInput extends IllegalArgumentException {
        private final String name;
        private final boolean missing;

        private InvalidInput(String name, boolean missing) {
            super(missing ? "Required input is missing" : "Input is not declared");
            this.name = name;
            this.missing = missing;
        }

        public String name() {
            return name;
        }

        public boolean missing() {
            return missing;
        }
    }

    private RunInputs() {
    }

    public static Map<String, Object> resolve(ScenarioDocument scenario, Map<String, Object> supplied) {
        Map<String, InputDeclaration> declared = scenario.inputs() == null ? Map.of() : scenario.inputs();
        for (String name : supplied.keySet()) {
            if (!declared.containsKey(name)) {
                throw new InvalidInput(name, false);
            }
        }
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (var entry : declared.entrySet()) {
            String name = entry.getKey();
            if (supplied.containsKey(name)) {
                resolved.put(name, supplied.get(name));
            } else if (entry.getValue().defaultValue() != null) {
                resolved.put(name, entry.getValue().defaultValue());
            } else if (entry.getValue().required()) {
                throw new InvalidInput(name, true);
            }
        }
        return resolved;
    }
}
