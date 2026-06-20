package xyz.tcheeric.cashu.ledger.trace.publisher.outbox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory {@link OutboxStore} for tests and ephemeral use. Not durable across
 * restarts. Thread-safe via a {@link ReentrantLock} (avoids virtual-thread pinning
 * under {@code synchronized}, per the project concurrency guidance).
 */
public final class InMemoryOutboxStore implements OutboxStore {

    private final Map<String, OutboxRecord> rows = new LinkedHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    @Override
    public boolean enqueue(OutboxRecord record) {
        lock.lock();
        try {
            if (rows.containsKey(record.operationId())) {
                return false;
            }
            rows.put(record.operationId(), record);
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long pendingCount() {
        lock.lock();
        try {
            return rows.values().stream().filter(r -> r.status() == OutboxStatus.PENDING).count();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<OutboxRecord> claimBatch(int max, long nowEpochMs) {
        lock.lock();
        try {
            return rows.values().stream()
                    .filter(r -> r.status() == OutboxStatus.PENDING)
                    .filter(r -> r.nextAttemptAtEpochMs() <= nowEpochMs)
                    .sorted(Comparator.comparingLong(OutboxRecord::createdAtEpochMs)
                            .thenComparing(OutboxRecord::operationId))
                    .limit(max)
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<OutboxRecord> stuck(int minAttempts, int limit) {
        lock.lock();
        try {
            return rows.values().stream()
                    .filter(r -> r.status() == OutboxStatus.PENDING)
                    .filter(r -> r.attempts() >= minAttempts)
                    .sorted(Comparator.comparingInt(OutboxRecord::attempts).reversed()
                            .thenComparing(OutboxRecord::operationId))
                    .limit(limit)
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void markDelivered(String operationId) {
        lock.lock();
        try {
            OutboxRecord existing = rows.get(operationId);
            if (existing != null) {
                rows.put(operationId, existing.delivered());
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void recordFailure(String operationId, long nextAttemptAtEpochMs) {
        lock.lock();
        try {
            OutboxRecord existing = rows.get(operationId);
            if (existing != null) {
                rows.put(operationId, existing.withFailedAttempt(nextAttemptAtEpochMs));
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<OutboxRecord> find(String operationId) {
        lock.lock();
        try {
            return Optional.ofNullable(rows.get(operationId));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<String> deleteOldestPending() {
        lock.lock();
        try {
            List<OutboxRecord> pending = new ArrayList<>(rows.values().stream()
                    .filter(r -> r.status() == OutboxStatus.PENDING)
                    .sorted(Comparator.comparingLong(OutboxRecord::createdAtEpochMs)
                            .thenComparing(OutboxRecord::operationId))
                    .toList());
            if (pending.isEmpty()) {
                return Optional.empty();
            }
            String oldest = pending.get(0).operationId();
            rows.remove(oldest);
            return Optional.of(oldest);
        } finally {
            lock.unlock();
        }
    }
}
