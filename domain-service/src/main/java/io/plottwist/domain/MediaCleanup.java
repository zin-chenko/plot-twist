package io.plottwist.domain;

import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class MediaCleanup {
    private final JdbcTemplate jdbc;
    private final ObjectStorage storage;
    private final TransactionTemplate transactions;

    MediaCleanup(JdbcTemplate jdbc, ObjectStorage storage, TransactionTemplate transactions, MeterRegistry metrics) {
        this.jdbc = jdbc; this.storage = storage; this.transactions = transactions;
        metrics.gauge("profile.media.cleanup.pending", this, worker -> {
            Long count = worker.jdbc.queryForObject("SELECT count(*) FROM media_objects WHERE state <> 'ACTIVE'", Long.class);
            return count == null ? 0 : count;
        });
    }

    @Scheduled(fixedDelayString="${storage.cleanup-poll-ms:30000}")
    public void clean() {
        for (int i = 0; i < 10; i++) {
            Boolean progress = transactions.execute(status -> {
                var rows = jdbc.query("SELECT id, object_key FROM media_objects WHERE state <> 'ACTIVE' AND delete_after < now() ORDER BY delete_after FOR UPDATE SKIP LOCKED LIMIT 1",
                    (row, n) -> new ObjectRef(row.getObject("id", UUID.class), row.getString("object_key")));
                if (rows.isEmpty()) return false;
                var object = rows.getFirst();
                try {
                    storage.delete(object.key());
                    jdbc.update("DELETE FROM media_objects WHERE id = ?", object.id());
                } catch (software.amazon.awssdk.core.exception.SdkException unavailable) {
                    jdbc.update("UPDATE media_objects SET delete_after = now() + interval '1 minute' WHERE id = ?", object.id());
                }
                return true;
            });
            if (!Boolean.TRUE.equals(progress)) break;
        }
    }
    private record ObjectRef(UUID id, String key) {}
}
