package dev.faultora.model.catalog;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Safety classification for operations.
 * Importers propose; scenarios must explicitly authorize DESTRUCTIVE and UNKNOWN.
 */
public enum SafetyClassification {
    READ_ONLY,
    MUTATING,
    DESTRUCTIVE,
    UNKNOWN
}
