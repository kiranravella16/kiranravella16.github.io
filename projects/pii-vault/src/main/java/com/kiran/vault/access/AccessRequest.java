package com.kiran.vault.access;

import com.kiran.vault.record.PiiField;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

@Entity
@Table(name = "access_request")
public class AccessRequest {

    public enum Status { PENDING, APPROVED, REJECTED, USED, EXPIRED }

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 36)
    private String recordId;

    /** Comma-separated {@link PiiField} names. */
    @Column(nullable = false)
    private String fields;

    @Column(nullable = false)
    private String requester;

    @Column(nullable = false, length = 500)
    private String justification;

    /** Role required to approve, derived from the most sensitive requested field. */
    @Column(nullable = false, length = 32)
    private String requiredRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    private String approver;
    private String decisionNote;
    private Instant createdAt;
    private Instant decidedAt;
    private Instant expiresAt;

    protected AccessRequest() {
    }

    public AccessRequest(String id, String recordId, Set<PiiField> fields, String requester, String justification,
                         String requiredRole) {
        this.id = id;
        this.recordId = recordId;
        this.fields = fields.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
        this.requester = requester;
        this.justification = justification;
        this.requiredRole = requiredRole;
        this.status = Status.PENDING;
        this.createdAt = Instant.now();
    }

    public Set<PiiField> fieldSet() {
        EnumSet<PiiField> set = EnumSet.noneOf(PiiField.class);
        Arrays.stream(fields.split(",")).map(PiiField::valueOf).forEach(set::add);
        return set;
    }

    void approve(String approver, String note, Instant expiresAt) {
        this.status = Status.APPROVED;
        this.approver = approver;
        this.decisionNote = note;
        this.decidedAt = Instant.now();
        this.expiresAt = expiresAt;
    }

    void reject(String approver, String note) {
        this.status = Status.REJECTED;
        this.approver = approver;
        this.decisionNote = note;
        this.decidedAt = Instant.now();
    }

    void markUsed() {
        this.status = Status.USED;
    }

    void markExpired() {
        this.status = Status.EXPIRED;
    }

    public String getId() { return id; }
    public String getRecordId() { return recordId; }
    public String getFields() { return fields; }
    public String getRequester() { return requester; }
    public String getJustification() { return justification; }
    public String getRequiredRole() { return requiredRole; }
    public Status getStatus() { return status; }
    public String getApprover() { return approver; }
    public String getDecisionNote() { return decisionNote; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
