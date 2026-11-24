package com.kiran.pki.cert;

import com.kiran.pki.ca.CertificateAuthority;
import com.kiran.pki.ca.Pem;
import com.kiran.pki.config.PkiProperties;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.KeyPair;
import java.security.PublicKey;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
public class CertificateService {

    private static final Logger log = LoggerFactory.getLogger(CertificateService.class);

    private final CertificateAuthority ca;
    private final CertificateRepository repo;
    private final PkiProperties props;

    public CertificateService(CertificateAuthority ca, CertificateRepository repo, PkiProperties props) {
        this.ca = ca;
        this.repo = repo;
        this.props = props;
    }

    /** Result of issuing with a server-generated key. The private key is returned exactly once and never stored. */
    public record IssuedWithKey(ManagedCertificate certificate, String privateKeyPem) {
    }

    public record ValidationResult(boolean valid, String serialHex, String subject, List<String> problems) {
    }

    @Transactional
    public IssuedWithKey issueWithGeneratedKey(String cn, List<String> dnsNames, Integer validityDays,
                                               boolean autoRenew, String owner) throws Exception {
        KeyPair kp = ca.newKeyPair();
        ManagedCertificate saved = issueAndStore(cn, dnsNames, kp.getPublic(), days(validityDays), autoRenew, owner);
        return new IssuedWithKey(saved, Pem.write(kp.getPrivate()));
    }

    @Transactional
    public ManagedCertificate issueFromCsr(String csrPem, List<String> dnsNames, Integer validityDays,
                                           boolean autoRenew, String owner) throws Exception {
        var csr = Pem.readCsr(csrPem);
        PublicKey key = ca.verifyCsr(csr);
        RDN[] cns = csr.getSubject().getRDNs(BCStyle.CN);
        if (cns.length == 0) {
            throw new IllegalArgumentException("CSR subject must contain a CN");
        }
        String cn = IETFUtils.valueToString(cns[0].getFirst().getValue());
        return issueAndStore(cn, dnsNames, key, days(validityDays), autoRenew, owner);
    }

    /**
     * Renews a certificate by re-signing its existing public key with a fresh validity window.
     * The old certificate is marked SUPERSEDED so dashboards and alerts track only the newest one.
     */
    @Transactional
    public ManagedCertificate renew(Long id) throws Exception {
        ManagedCertificate old = get(id);
        if (old.getStatus() == CertificateStatus.REVOKED) {
            throw new IllegalStateException("Revoked certificates cannot be renewed; issue a new one");
        }
        X509Certificate oldCert = Pem.readCertificate(old.getPem());
        ManagedCertificate renewed = issueAndStore(old.getCommonName(), splitSans(old.getDnsNames()),
                oldCert.getPublicKey(), props.defaultValidityDays(), old.isAutoRenew(), old.getOwner());
        renewed.setRenewedFromId(old.getId());
        old.supersede();
        log.info("Renewed certificate {} (CN={}) -> {}", old.getSerialHex(), old.getCommonName(), renewed.getSerialHex());
        return renewed;
    }

    @Transactional
    public ManagedCertificate revoke(Long id, RevocationReason reason) {
        ManagedCertificate cert = get(id);
        cert.revoke(reason == null ? RevocationReason.UNSPECIFIED : reason, Instant.now());
        log.warn("Revoked certificate {} (CN={}) reason={}", cert.getSerialHex(), cert.getCommonName(), reason);
        return cert;
    }

    @Transactional(readOnly = true)
    public X509CRL currentCrl() throws Exception {
        List<CertificateAuthority.RevokedEntry> entries = repo.findByStatus(CertificateStatus.REVOKED).stream()
                .map(c -> new CertificateAuthority.RevokedEntry(
                        new java.math.BigInteger(c.getSerialHex(), 16), c.getRevokedAt(), c.getRevocationReason().code()))
                .toList();
        return ca.generateCrl(entries, props.crlValidityHours());
    }

    /** Validates signature chain, validity window and revocation status of a presented certificate. */
    @Transactional(readOnly = true)
    public ValidationResult validate(String pem) {
        List<String> problems = new ArrayList<>();
        X509Certificate cert;
        try {
            cert = Pem.readCertificate(pem);
        } catch (IllegalArgumentException e) {
            return new ValidationResult(false, null, null, List.of(e.getMessage()));
        }
        String serial = cert.getSerialNumber().toString(16);
        try {
            cert.verify(ca.caCertificate().getPublicKey());
        } catch (Exception e) {
            problems.add("Signature does not chain to this CA");
        }
        try {
            cert.checkValidity();
        } catch (CertificateExpiredException e) {
            problems.add("Certificate has expired");
        } catch (CertificateNotYetValidException e) {
            problems.add("Certificate is not yet valid");
        }
        repo.findBySerialHex(serial).ifPresentOrElse(
                c -> {
                    if (c.getStatus() == CertificateStatus.REVOKED) {
                        problems.add("Certificate is revoked (" + c.getRevocationReason() + ")");
                    }
                },
                () -> problems.add("Serial is unknown to this CA's inventory"));
        return new ValidationResult(problems.isEmpty(), serial, cert.getSubjectX500Principal().getName(), problems);
    }

    /** Marks lapsed ACTIVE certificates as EXPIRED and returns how many were changed. */
    @Transactional
    public int expireLapsed() {
        List<ManagedCertificate> lapsed = repo.findByStatusAndNotAfterBefore(CertificateStatus.ACTIVE, Instant.now());
        lapsed.forEach(c -> {
            c.expire();
            log.warn("Certificate {} (CN={}, owner={}) expired", c.getSerialHex(), c.getCommonName(), c.getOwner());
        });
        return lapsed.size();
    }

    /** ACTIVE, auto-renew certificates whose notAfter falls inside the renewal window. */
    @Transactional(readOnly = true)
    public List<Long> renewalCandidates() {
        Instant window = Instant.now().plus(props.renewBeforeDays(), ChronoUnit.DAYS);
        return repo.findByStatusAndNotAfterBefore(CertificateStatus.ACTIVE, window).stream()
                .filter(ManagedCertificate::isAutoRenew)
                .map(ManagedCertificate::getId)
                .toList();
    }

    @Transactional(readOnly = true)
    public ManagedCertificate get(Long id) {
        return repo.findById(id).orElseThrow(() -> new NoSuchElementException("Certificate " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public List<ManagedCertificate> list(CertificateStatus status) {
        return status == null ? repo.findAll() : repo.findByStatusOrderByNotAfterAsc(status);
    }

    @Transactional(readOnly = true)
    public Map<String, Long> summary() {
        Instant soon = Instant.now().plus(props.renewBeforeDays(), ChronoUnit.DAYS);
        return Map.of(
                "active", repo.countByStatus(CertificateStatus.ACTIVE),
                "expiringSoon", repo.countByStatusAndNotAfterBefore(CertificateStatus.ACTIVE, soon),
                "revoked", repo.countByStatus(CertificateStatus.REVOKED),
                "expired", repo.countByStatus(CertificateStatus.EXPIRED),
                "superseded", repo.countByStatus(CertificateStatus.SUPERSEDED));
    }

    private ManagedCertificate issueAndStore(String cn, List<String> dnsNames, PublicKey key, int days,
                                             boolean autoRenew, String owner) throws Exception {
        List<String> sans = dnsNames == null || dnsNames.isEmpty() ? List.of(cn) : dnsNames;
        X509Certificate cert = ca.issue(cn, sans, key, days);
        ManagedCertificate entity = new ManagedCertificate(
                cert.getSerialNumber().toString(16), cn, String.join(",", sans),
                cert.getNotBefore().toInstant(), cert.getNotAfter().toInstant(),
                autoRenew, owner, Pem.write(cert));
        return repo.save(entity);
    }

    private int days(Integer requested) {
        return requested == null ? props.defaultValidityDays() : requested;
    }

    private static List<String> splitSans(String sans) {
        return sans == null || sans.isBlank() ? List.of() : Arrays.asList(sans.split(","));
    }
}
