package com.kiran.vault.audit;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Append-only, hash-chained audit log. Each event's hash covers its contents and the previous event's hash,
 * so editing or deleting any row breaks every hash after it and {@link #verify()} reports where.
 */
@Service
public class AuditService {

    public record ChainStatus(boolean valid, long events, Long firstBrokenEventId) {
    }

    private static final String GENESIS = "0".repeat(64);

    private final AuditRepository repo;

    public AuditService(AuditRepository repo) {
        this.repo = repo;
    }

    /** Synchronized so concurrent writers can't fork the chain (use a DB sequence / advisory lock when scaled out). */
    @Transactional
    public synchronized void record(String actor, String action, String recordId, String detail) {
        AuditEvent last = repo.findTopByOrderByIdDesc();
        String prev = last == null ? GENESIS : last.getHash();
        Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        repo.save(new AuditEvent(at, actor, action, recordId, detail, prev,
                hash(prev, at, actor, action, recordId, detail)));
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> forRecord(String recordId) {
        return repo.findAll(Sort.by("id")).stream().filter(e -> Objects.equals(recordId, e.getRecordId())).toList();
    }

    @Transactional(readOnly = true)
    public ChainStatus verify() {
        List<AuditEvent> events = repo.findAll(Sort.by("id"));
        String prev = GENESIS;
        for (AuditEvent e : events) {
            String expected = hash(prev, e.getAt(), e.getActor(), e.getAction(), e.getRecordId(), e.getDetail());
            if (!e.getPrevHash().equals(prev) || !e.getHash().equals(expected)) {
                return new ChainStatus(false, events.size(), e.getId());
            }
            prev = e.getHash();
        }
        return new ChainStatus(true, events.size(), null);
    }

    static String hash(String prev, Instant at, String actor, String action, String recordId, String detail) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String canonical = String.join("|", prev, at.toString(), actor, action,
                    Objects.toString(recordId, ""), Objects.toString(detail, ""));
            return HexFormat.of().formatHex(md.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
