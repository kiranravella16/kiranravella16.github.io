package com.kiran.vault.web;

import com.kiran.vault.access.AccessRequest;
import com.kiran.vault.access.AccessService;
import com.kiran.vault.audit.AuditEvent;
import com.kiran.vault.audit.AuditService;
import com.kiran.vault.record.PiiField;
import com.kiran.vault.record.PiiRecordService;
import com.kiran.vault.record.PiiRecordService.MaskedView;
import com.kiran.vault.record.PiiRecordService.NewRecord;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class VaultController {

    private final PiiRecordService records;
    private final AccessService access;
    private final AuditService audit;

    public VaultController(PiiRecordService records, AccessService access, AuditService audit) {
        this.records = records;
        this.access = access;
        this.audit = audit;
    }

    public record CreateRecord(
            @NotBlank String customerRef,
            @Pattern(regexp = "^\\d{3}-?\\d{2}-?\\d{4}$", message = "must be a 9-digit SSN") String ssn,
            @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "must be YYYY-MM-DD") String dateOfBirth,
            @Email String email,
            @Pattern(regexp = "^[0-9()+\\-. ]{7,20}$") String phone) {
    }

    public record CreateAccessRequest(@NotBlank String recordId, @NotEmpty Set<PiiField> fields,
                                      @NotBlank @Size(min = 10, max = 500) String justification) {
    }

    public record Decision(String note) {
    }

    public record SearchRequest(@NotBlank String ssn) {
    }

    // ---- records -------------------------------------------------------------------------------------------

    @PostMapping("/records")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('INGEST')")
    public MaskedView create(@Valid @RequestBody CreateRecord body, Authentication auth) {
        return records.store(new NewRecord(body.customerRef(), body.ssn(), body.dateOfBirth(), body.email(),
                body.phone()), auth.getName());
    }

    @GetMapping("/records/{id}")
    public MaskedView get(@PathVariable String id) {
        return records.masked(id);
    }

    /** POST rather than GET so the SSN never ends up in URLs, access logs or proxies. */
    @PostMapping("/records/search")
    @PreAuthorize("hasRole('ANALYST')")
    public List<MaskedView> search(@Valid @RequestBody SearchRequest body, Authentication auth) {
        return records.searchBySsn(body.ssn(), auth.getName());
    }

    // ---- access workflow -----------------------------------------------------------------------------------

    @PostMapping("/access-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ANALYST')")
    public AccessRequest requestAccess(@Valid @RequestBody CreateAccessRequest body, Authentication auth) {
        return access.request(body.recordId(), body.fields(), body.justification(), auth.getName());
    }

    @GetMapping("/access-requests/pending")
    @PreAuthorize("hasAnyRole('MANAGER','DIRECTOR')")
    public List<AccessRequest> pending() {
        return access.pending();
    }

    @PostMapping("/access-requests/{id}/approve")
    @PreAuthorize("hasAnyRole('MANAGER','DIRECTOR')")
    public AccessRequest approve(@PathVariable String id, @RequestBody(required = false) Decision body,
                                 Authentication auth) {
        return access.approve(id, auth.getName(), roles(auth), body == null ? null : body.note());
    }

    @PostMapping("/access-requests/{id}/reject")
    @PreAuthorize("hasAnyRole('MANAGER','DIRECTOR')")
    public AccessRequest reject(@PathVariable String id, @RequestBody(required = false) Decision body,
                                Authentication auth) {
        return access.reject(id, auth.getName(), roles(auth), body == null ? null : body.note());
    }

    @PostMapping("/access-requests/{id}/reveal")
    @PreAuthorize("hasRole('ANALYST')")
    public Map<PiiField, String> reveal(@PathVariable String id, Authentication auth) {
        return access.reveal(id, auth.getName());
    }

    // ---- admin ---------------------------------------------------------------------------------------------

    @PostMapping("/admin/kek/rewrap")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Integer> rewrap(Authentication auth) {
        return Map.of("rewrapped", records.rewrapToActiveKek(auth.getName()));
    }

    @GetMapping("/admin/audit/verify")
    @PreAuthorize("hasRole('ADMIN')")
    public AuditService.ChainStatus verifyAudit() {
        return audit.verify();
    }

    @GetMapping("/admin/audit/records/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public List<AuditEvent> auditForRecord(@PathVariable String id) {
        return audit.forRecord(id);
    }

    private static Set<String> roles(Authentication auth) {
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_")).map(a -> a.substring(5)).collect(Collectors.toSet());
    }
}
