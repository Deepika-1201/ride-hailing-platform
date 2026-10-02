package com.ridehailing.audit.app;

import java.time.Period;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Months of audit partitions kept ready ahead of the current one, and how long entries are kept (LLD §5.7). */
@ConfigurationProperties(prefix = "ride.audit")
public record AuditProperties(@DefaultValue("2") int partitionsAhead, @DefaultValue("3y") Period retention) {
}
