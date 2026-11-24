package com.kiran.vault.access;

import com.kiran.vault.audit.AuditService;
import com.kiran.vault.record.PiiField;
import com.kiran.vault.record.PiiRecordService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * Tiered, approval-based decryption ("break glass with a second pair of eyes"):
 * <ul>
 *     <li>An analyst asks for specific fields of a specific record, with a justification.</li>
 *     <li>The approver tier follows the most sensitive field: SSN needs a DIRECTOR; other PII needs a MANAGER.
 *         A director can approve anything a manager can.</li>
 *     <li>Requesters can't approve their own requests (segregation of duties).</li>
 *     <li>Approvals are single-use, only the requester can redeem them, and they expire after a short TTL.</li>
 *     <li>Every step is written to the hash-chained audit log, including denied attempts.</li>
 * </ul>
 */
@Service
public class AccessService {

    private static final List<String> ROLE_HIERARCHY = List.of("MANAGER", "DIRECTOR");

    private final AccessRequestRepository repo;
    private final PiiRecordService records;
    private final AuditService audit;
    private final Duration grantTtl;

    public AccessService(AccessRequestRepository repo, PiiRecordService records, AuditService audit,
                         @Value("${pii.access.grant-ttl:PT15M}") Duration grantTtl) {
        this.repo = repo;
        this.records = records;
        this.audit = audit;
        this.grantTtl = grantTtl;
    }

    public static String requiredRoleFor(Set<PiiField> fields) {
        boolean restricted = fields.stream().anyMatch(f -> f.sensitivity == PiiField.Sensitivity.RESTRICTED);
        return restricted ? PiiField.Sensitivity.RESTRICTED.approverRole : PiiField.Sensitivity.CONFIDENTIAL.approverRole;
    }

    @Transactional
    public AccessRequest request(String recordId, Set<PiiField> fields, String justification, String requester) {
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("At least one field must be requested");
        }
        records.masked(recordId); // 404 if the record doesn't exist
        AccessRequest req = repo.save(new AccessRequest(UUID.randomUUID().toString(), recordId, fields, requester,
                justification, requiredRoleFor(fields)));
        audit.record(requester, "ACCESS_REQUESTED", recordId,
                "request=" + req.getId() + " fields=" + req.getFields() + " requires=" + req.getRequiredRole()
                        + " justification=" + justification);
        return req;
    }

    @Transactional(readOnly = true)
    public List<AccessRequest> pending() {
        return repo.findByStatusOrderByCreatedAtAsc(AccessRequest.Status.PENDING);
    }

    @Transactional
    public AccessRequest approve(String requestId, String approver, Set<String> approverRoles, String note) {
        AccessRequest req = pendingRequest(requestId);
        checkApprover(req, approver, approverRoles);
        req.approve(approver, note, Instant.now().plus(grantTtl));
        audit.record(approver, "ACCESS_APPROVED", req.getRecordId(),
                "request=" + req.getId() + " fields=" + req.getFields() + " expiresAt=" + req.getExpiresAt());
        return req;
    }

    @Transactional
    public AccessRequest reject(String requestId, String approver, Set<String> approverRoles, String note) {
        AccessRequest req = pendingRequest(requestId);
        checkApprover(req, approver, approverRoles);
        req.reject(approver, note);
        audit.record(approver, "ACCESS_REJECTED", req.getRecordId(), "request=" + req.getId() + " note=" + note);
        return req;
    }

    /**
     * Redeems an approved grant. Uses {@code noRollbackFor} so the audit entry for a denied attempt is kept even
     * though the call fails.
     */
    @Transactional(noRollbackFor = {AccessDeniedException.class, IllegalStateException.class})
    public Map<PiiField, String> reveal(String requestId, String caller) {
        AccessRequest req = find(requestId);
        if (!req.getRequester().equals(caller)) {
            audit.record(caller, "REVEAL_DENIED", req.getRecordId(), "request=" + requestId + " reason=not-requester");
            throw new AccessDeniedException("Only the original requester can use this grant");
        }
        if (req.getStatus() == AccessRequest.Status.APPROVED && Instant.now().isAfter(req.getExpiresAt())) {
            req.markExpired();
        }
        if (req.getStatus() != AccessRequest.Status.APPROVED) {
            audit.record(caller, "REVEAL_DENIED", req.getRecordId(),
                    "request=" + requestId + " reason=status-" + req.getStatus());
            throw new IllegalStateException("Grant is " + req.getStatus() + ", not APPROVED");
        }
        Map<PiiField, String> plaintext = records.decrypt(req.getRecordId(), req.fieldSet());
        req.markUsed();
        audit.record(caller, "PII_REVEALED", req.getRecordId(),
                "request=" + requestId + " fields=" + req.getFields() + " approvedBy=" + req.getApprover());
        return plaintext;
    }

    private void checkApprover(AccessRequest req, String approver, Set<String> roles) {
        if (req.getRequester().equals(approver)) {
            throw new AccessDeniedException("Requesters cannot approve their own access requests");
        }
        int needed = ROLE_HIERARCHY.indexOf(req.getRequiredRole());
        int has = roles.stream().mapToInt(ROLE_HIERARCHY::indexOf).max().orElse(-1);
        if (has < needed) {
            throw new AccessDeniedException("Approving " + req.getFields() + " requires " + req.getRequiredRole());
        }
    }

    private AccessRequest pendingRequest(String id) {
        AccessRequest req = find(id);
        if (req.getStatus() != AccessRequest.Status.PENDING) {
            throw new IllegalStateException("Request is already " + req.getStatus());
        }
        return req;
    }

    private AccessRequest find(String id) {
        return repo.findById(id).orElseThrow(() -> new NoSuchElementException("Access request " + id + " not found"));
    }
}
