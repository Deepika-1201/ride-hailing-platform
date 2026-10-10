package com.ridehailing.operations.app;

import com.ridehailing.platform.InvariantCheck;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** {@code GET /v1/ops/invariants} (LLD §17.3): every module's check, over every city. */
@Service
public class InvariantReports {

    private static final Logger LOG = LoggerFactory.getLogger(InvariantReports.class);
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final List<InvariantCheck> checks;
    private final Clock clock;

    InvariantReports(List<InvariantCheck> checks, Clock clock) {
        this.checks = checks.stream().sorted(Comparator.comparingInt(InvariantReports::number)).toList();
        this.clock = clock;
    }

    public InvariantReport check() {
        Instant checkedAt = clock.instant();
        List<Violation> violations = new ArrayList<>();
        for (InvariantCheck check : checks) {
            try {
                check.violations(null).forEach(detail -> violations.add(new Violation(check.id(), detail, ids(detail))));
            } catch (RuntimeException e) {
                // A check that can't finish proves nothing, so it is reported rather than passed over.
                LOG.warn("Invariant check {} failed", check.id(), e);
                violations.add(new Violation(check.id(), "The check couldn't run: " + e.getClass().getSimpleName(),
                        null));
            }
        }
        return new InvariantReport(checkedAt, checks.stream().map(InvariantCheck::id).toList(), violations);
    }

    private static List<UUID> ids(String detail) {
        List<UUID> ids = new ArrayList<>();
        Matcher matcher = UUID_TEXT.matcher(detail);
        while (matcher.find()) {
            UUID id = UUID.fromString(matcher.group());
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids.isEmpty() ? null : ids;
    }

    private static int number(InvariantCheck check) {
        return Integer.parseInt(check.id().substring(1));
    }

    public record InvariantReport(Instant checkedAt, List<String> checks, List<Violation> violations) {
    }

    public record Violation(String invariant, String detail, List<UUID> ids) {
    }
}
