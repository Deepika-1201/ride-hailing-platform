package com.ridehailing.platform.outbox;

import com.ridehailing.platform.EventEnvelope;
import com.ridehailing.platform.EventLog;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class JdbcEventLog implements EventLog {

    private final OutboxRows rows;

    JdbcEventLog(OutboxRows rows) {
        this.rows = rows;
    }

    @Override
    public List<EventEnvelope> byPartitionKey(UUID key) {
        return rows.byPartitionKey(key).stream().map(OutboxRows.Row::envelope).toList();
    }
}
