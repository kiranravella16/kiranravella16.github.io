package com.kiran.vault.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant at;

    @Column(nullable = false)
    private String actor;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(length = 64)
    private String recordId;

    @Column(length = 1000)
    private String detail;

    @Column(nullable = false, length = 64)
    private String prevHash;

    @Column(nullable = false, length = 64)
    private String hash;

    protected AuditEvent() {
    }

    public AuditEvent(Instant at, String actor, String action, String recordId, String detail, String prevHash,
                      String hash) {
        this.at = at;
        this.actor = actor;
        this.action = action;
        this.recordId = recordId;
        this.detail = detail;
        this.prevHash = prevHash;
        this.hash = hash;
    }

    public Long getId() { return id; }
    public Instant getAt() { return at; }
    public String getActor() { return actor; }
    public String getAction() { return action; }
    public String getRecordId() { return recordId; }
    public String getDetail() { return detail; }
    public String getPrevHash() { return prevHash; }
    public String getHash() { return hash; }
}
