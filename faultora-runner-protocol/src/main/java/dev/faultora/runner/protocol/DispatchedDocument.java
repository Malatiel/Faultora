package dev.faultora.runner.protocol;

/**
 * One description a dispatch carries.
 * <p>
 * The family is the same word the loader uses — {@code openapi},
 * {@code asyncapi}, {@code observations} — because the runner hands it to the
 * same importer registry. Carrying the family rather than guessing from the
 * content means a document that could be read as either is read as the one the
 * dispatcher meant.
 *
 * @param family  which importer reads this
 * @param content the document, as written
 */
public record DispatchedDocument(String family, String content) {
}
