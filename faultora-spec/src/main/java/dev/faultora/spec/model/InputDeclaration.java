package dev.faultora.spec.model;

import java.util.Map;

/**
 * Declares an input parameter for the scenario.
 *
 * @param type         input type (string, number, boolean, object)
 * @param description  human-readable description
 * @param required     whether the input is mandatory
 * @param defaultValue default value if not provided
 */
public record InputDeclaration(
        String type,
        String description,
        boolean required,
        Object defaultValue
) {}
