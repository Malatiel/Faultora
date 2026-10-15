package dev.faultora.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.faultora.engine.plan.PlanCompilationResult;
import dev.faultora.engine.plan.PlanCompiler;
import dev.faultora.model.catalog.ApiCatalog;
import dev.faultora.model.identifier.RunId;
import dev.faultora.model.security.ContentDigest;
import dev.faultora.model.security.ExtensionPolicy;
import dev.faultora.model.security.TargetPolicy;
import dev.faultora.spec.model.ScenarioDocument;
import dev.faultora.spec.parser.Diagnostic;
import dev.faultora.spec.parser.ScenarioParser;
import dev.faultora.spec.validator.ScenarioValidator;

import java.util.ArrayList;
import java.util.List;

/** Prepares a run without opening connectors, resolving secrets or activating faults. */
public final class RunPreparation {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RunPreparation() { }

    /** Policy must already have been authenticated and narrowed by the caller. */
    public record Request(
            String scenarioContent, List<CatalogAssembly.Document> documents,
            String fallbackTargetUrl, ExtensionPolicy extensions, TargetPolicy targets,
            RunId runId, long seed, String catalogDigest) {
        public Request {
            documents = List.copyOf(documents);
        }
    }

    /** Parser diagnostics and compiler diagnostics retain their respective source locations. */
    public record Result(
            ScenarioDocument scenario, ApiCatalog catalog,
            List<Diagnostic> diagnostics, PlanCompilationResult compilation) {
        public Result {
            diagnostics = List.copyOf(diagnostics);
        }

        public boolean isSuccess() {
            return compilation != null && compilation.isSuccess();
        }
    }

    public static Result prepare(Request request) {
        ExtensionRegistry.refuseWhatIsNotEnforced(request.extensions());
        var parsed = new ScenarioParser().parse(request.scenarioContent());
        List<Diagnostic> diagnostics = new ArrayList<>(parsed.diagnostics());
        if (!parsed.isSuccess()) {
            return new Result(null, null, diagnostics, null);
        }
        var validated = new ScenarioValidator().validate(parsed.document());
        diagnostics.addAll(validated.diagnostics());
        if (!validated.isSuccess()) {
            return new Result(null, null, diagnostics, null);
        }
        ScenarioDocument scenario = validated.document();
        ApiCatalog catalog = request.documents().isEmpty()
                ? CatalogAssembly.fromScenario(scenario, request.fallbackTargetUrl())
                : CatalogAssembly.assemble(request.documents(), request.extensions());
        String catalogDigest = request.catalogDigest() == null
                ? digest(catalog) : request.catalogDigest();
        var compilation = new PlanCompiler().compile(
                scenario, catalog, request.targets(), request.runId(), request.seed(),
                ContentDigest.sha256Uri(request.scenarioContent()), catalogDigest);
        return new Result(scenario, catalog, diagnostics, compilation);
    }

    private static String digest(ApiCatalog catalog) {
        try {
            return ContentDigest.sha256Uri(MAPPER.writeValueAsString(catalog));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Catalog cannot be fingerprinted", impossible);
        }
    }
}
