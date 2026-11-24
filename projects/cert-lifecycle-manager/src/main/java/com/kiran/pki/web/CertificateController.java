package com.kiran.pki.web;

import com.kiran.pki.ca.CertificateAuthority;
import com.kiran.pki.ca.Pem;
import com.kiran.pki.cert.CertificateService;
import com.kiran.pki.cert.CertificateStatus;
import com.kiran.pki.cert.ManagedCertificate;
import com.kiran.pki.cert.RevocationReason;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class CertificateController {

    private final CertificateService service;
    private final CertificateAuthority ca;

    public CertificateController(CertificateService service, CertificateAuthority ca) {
        this.service = service;
        this.ca = ca;
    }

    public record IssueRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9.*-]{1,253}$", message = "must be a DNS-style name") String commonName,
            List<@Pattern(regexp = "^[A-Za-z0-9.*-]{1,253}$") String> dnsNames,
            @Min(1) @Max(825) Integer validityDays,
            boolean autoRenew,
            String owner) {
    }

    public record CsrRequest(@NotBlank String csrPem, List<String> dnsNames,
                             @Min(1) @Max(825) Integer validityDays, boolean autoRenew, String owner) {
    }

    public record RevokeRequest(RevocationReason reason) {
    }

    public record ValidateRequest(@NotBlank String pem) {
    }

    public record CertificateView(Long id, String serial, String commonName, List<String> dnsNames,
                                  Instant notBefore, Instant notAfter, CertificateStatus status, boolean autoRenew,
                                  String owner, RevocationReason revocationReason, Long renewedFromId, String pem) {
        static CertificateView of(ManagedCertificate c) {
            return new CertificateView(c.getId(), c.getSerialHex(), c.getCommonName(),
                    c.getDnsNames() == null ? List.of() : List.of(c.getDnsNames().split(",")),
                    c.getNotBefore(), c.getNotAfter(), c.getStatus(), c.isAutoRenew(), c.getOwner(),
                    c.getRevocationReason(), c.getRenewedFromId(), c.getPem());
        }
    }

    @PostMapping("/certificates")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> issue(@Valid @RequestBody IssueRequest req) throws Exception {
        var issued = service.issueWithGeneratedKey(req.commonName(), req.dnsNames(), req.validityDays(),
                req.autoRenew(), req.owner());
        return Map.of("certificate", CertificateView.of(issued.certificate()),
                "privateKeyPem", issued.privateKeyPem(),
                "warning", "The private key is not stored by the CA. Save it now.");
    }

    @PostMapping("/certificates/csr")
    @ResponseStatus(HttpStatus.CREATED)
    public CertificateView signCsr(@Valid @RequestBody CsrRequest req) throws Exception {
        return CertificateView.of(service.issueFromCsr(req.csrPem(), req.dnsNames(), req.validityDays(),
                req.autoRenew(), req.owner()));
    }

    @GetMapping("/certificates")
    public List<CertificateView> list(@RequestParam(required = false) CertificateStatus status) {
        return service.list(status).stream().map(CertificateView::of).toList();
    }

    @GetMapping("/certificates/summary")
    public Map<String, Long> summary() {
        return service.summary();
    }

    @GetMapping("/certificates/{id}")
    public CertificateView get(@PathVariable Long id) {
        return CertificateView.of(service.get(id));
    }

    @PostMapping("/certificates/{id}/renew")
    public CertificateView renew(@PathVariable Long id) throws Exception {
        return CertificateView.of(service.renew(id));
    }

    @PostMapping("/certificates/{id}/revoke")
    public CertificateView revoke(@PathVariable Long id, @RequestBody(required = false) RevokeRequest req) {
        return CertificateView.of(service.revoke(id, req == null ? null : req.reason()));
    }

    @PostMapping("/certificates/validate")
    public CertificateService.ValidationResult validate(@Valid @RequestBody ValidateRequest req) {
        return service.validate(req.pem());
    }

    @GetMapping(value = "/ca/certificate", produces = "application/x-pem-file")
    public String caCertificate() {
        return Pem.write(ca.caCertificate());
    }

    @GetMapping(value = "/ca/crl", produces = "application/pkix-crl")
    public ResponseEntity<byte[]> crl() throws Exception {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/pkix-crl"))
                .body(service.currentCrl().getEncoded());
    }

    @GetMapping(value = "/ca/crl.pem", produces = MediaType.TEXT_PLAIN_VALUE)
    public String crlPem() throws Exception {
        return Pem.write(service.currentCrl());
    }
}
