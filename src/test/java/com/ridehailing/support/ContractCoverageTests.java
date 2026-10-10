package com.ridehailing.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;

/**
 * LLD §17.1: every handler is a documented operation, and after the whole suite every operation with a handler has
 * answered a success, every event type has been produced and every server message type received, each checked against
 * its contract. Ordered after every other class.
 */
@Order(Integer.MAX_VALUE)
class ContractCoverageTests extends IntegrationTest {

    /** Set by the build when no {@code --tests} filter runs only part of the suite. */
    private static final boolean WHOLE_SUITE = Boolean.getBoolean("ride.contract-coverage");
    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete");
    private static final Pattern VARIABLE = Pattern.compile("\\{[^}]+}");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlers;

    @Test
    void everyHandlerIsADocumentedOperationAndEveryV1OperationHasOne() {
        Set<String> handled = handled();

        assertThat(missing(handled, operations(true))).as("handlers that openapi.yaml doesn't document").isEmpty();
        assertThat(missing(operations(false), handled)).as("V1 operations without a handler").isEmpty();
    }

    @Test
    void everyHandledOperationAnsweredASuccessThatATestChecked() {
        assumeTrue(WHOLE_SUITE, "only after the whole suite");
        Set<String> succeeded = OpenApiContract.checked().stream().filter(checked -> checked.matches(".* 2\\d\\d"))
                .map(checked -> VARIABLE.matcher(checked.substring(0, checked.lastIndexOf(' '))).replaceAll("{}"))
                .collect(Collectors.toSet());

        assertThat(missing(handled(), succeeded)).as("operations no test saw succeed").isEmpty();
    }

    @Test
    void everyEventTypeWasProducedAndChecked() {
        assumeTrue(WHOLE_SUITE, "only after the whole suite");

        assertThat(missing(EventContract.documented(), EventContract.checked()))
                .as("event types no test read from the outbox and checked").isEmpty();
    }

    @Test
    void everyServerMessageTypeWasChecked() {
        assumeTrue(WHOLE_SUITE, "only after the whole suite");

        assertThat(WebSocketContract.documented()).hasSize(8);
        assertThat(missing(WebSocketContract.documented(), WebSocketContract.checked()))
                .as("server message types no test received and checked").isEmpty();
    }

    /** {@code METHOD /path/{}} of the API's handlers. */
    private Set<String> handled() {
        return handlers.getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPatternValues().stream().filter(path -> path.startsWith("/v1/"))
                        .flatMap(path -> mapping.getMethodsCondition().getMethods().stream()
                                .map(method -> method.name() + " " + VARIABLE.matcher(path).replaceAll("{}"))))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** {@code METHOD /path/{}} of the documented operations, of every version or only V1's (no {@code x-since}). */
    private static Set<String> operations(boolean everyVersion) {
        Set<String> operations = new TreeSet<>();
        OpenApiContract.spec().path("paths").properties().forEach(path -> path.getValue().properties()
                .forEach(operation -> {
                    JsonNode details = operation.getValue();
                    if (METHODS.contains(operation.getKey()) && (everyVersion || !details.has("x-since"))) {
                        operations.add(operation.getKey().toUpperCase(Locale.ROOT) + " "
                                + VARIABLE.matcher(path.getKey()).replaceAll("{}"));
                    }
                }));
        return operations;
    }

    private static Set<String> missing(Set<String> expected, Set<String> present) {
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(present);
        return missing;
    }
}
