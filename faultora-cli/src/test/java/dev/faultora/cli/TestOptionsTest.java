package dev.faultora.cli;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestOptionsTest {

    @Test
    void previewDoesNotEnableARealRun() {
        assertThat(TestOptions.parse(List.of("--scenario", "scenario.yaml", "--preview")).preview()).isTrue();
        assertThat(TestOptions.parse(List.of("--scenario", "scenario.yaml")).preview()).isFalse();
    }

    @Test
    void faultDescriptionsMatchTheProvidersWithoutOpeningAManagementClient() {
        var options = TestOptions.parse(List.of("--scenario", "scenario.yaml"));
        assertThat(RunPolicies.faultTypes(options)).containsExactlyInAnyOrderElementsOf(
                RunPolicies.faultProviders(options).get("local").capabilities());
        var network = TestOptions.parse(List.of("--scenario", "scenario.yaml",
                "--toxiproxy-url", "http://admin.example.com"));
        assertThat(RunPolicies.faultTypes(network)).containsAll(RunPolicies.faultTypes(options))
                .containsAll(dev.faultora.faults.toxiproxy.ToxiproxyFaultProvider.supportedFaultTypes());
        assertThat(RunPolicies.faultTypes(options)).doesNotContain("network-reset");
    }

    @Test
    void aPlainTargetBindsEveryCatalogTarget() {
        TestOptions options = TestOptions.parse(List.of(
                "--scenario", "scenario.yaml", "--target", "http://localhost:8081"));

        assertThat(options.targetUrl()).isEqualTo("http://localhost:8081");
        assertThat(options.targetUrls()).isEmpty();
    }

    @Test
    void anIdentifiedTargetBindsOnlyThatCatalogTarget() {
        TestOptions options = TestOptions.parse(List.of(
                "--scenario", "scenario.yaml",
                "--target", "http://localhost:8081",
                "--target", "ledger=http://localhost:7777"));

        assertThat(options.targetUrl()).isEqualTo("http://localhost:8081");
        assertThat(options.targetUrls()).containsExactly(
                org.assertj.core.api.Assertions.entry("ledger", "http://localhost:7777"));
    }

    @Test
    void aUrlContainingAQueryStringIsNotMistakenForATargetBinding() {
        TestOptions options = TestOptions.parse(List.of(
                "--scenario", "scenario.yaml", "--target", "http://host/api?tenant=acme"));

        assertThat(options.targetUrl()).isEqualTo("http://host/api?tenant=acme");
        assertThat(options.targetUrls()).isEmpty();
    }

    @Test
    void inputsKeepTheTypeTheUserTyped() {
        TestOptions options = TestOptions.parse(List.of(
                "--scenario", "scenario.yaml",
                "--input", "retries=3",
                "--input", "verbose=true",
                "--input", "currency=EUR"));

        assertThat(options.inputs()).containsEntry("retries", 3L);
        assertThat(options.inputs()).containsEntry("verbose", true);
        assertThat(options.inputs()).containsEntry("currency", "EUR");
    }

    @Test
    void aMissingScenarioIsAConfigurationError() {
        assertThatThrownBy(() -> TestOptions.parse(List.of("--target", "http://localhost")))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("--scenario is required");
    }

    @Test
    void anUnknownOptionIsRejectedRatherThanIgnored() {
        assertThatThrownBy(() -> TestOptions.parse(List.of(
                "--scenario", "scenario.yaml", "--wat")))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("Unknown option: --wat");
    }

    @Test
    void helpShortCircuitsBeforeRequiringAScenario() {
        assertThat(TestOptions.parse(List.of("--help")).helpRequested()).isTrue();
    }
}
