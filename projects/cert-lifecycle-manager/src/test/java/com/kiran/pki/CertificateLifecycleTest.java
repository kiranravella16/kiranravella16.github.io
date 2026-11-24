package com.kiran.pki;

import com.kiran.pki.ca.CertificateAuthority;
import com.kiran.pki.ca.Pem;
import com.kiran.pki.cert.CertificateService;
import com.kiran.pki.cert.CertificateStatus;
import com.kiran.pki.cert.ManagedCertificate;
import com.kiran.pki.cert.RevocationReason;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigInteger;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class CertificateLifecycleTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        r.add("pki.keystore-path", () -> {
            try {
                return Files.createTempDirectory("ca").resolve("ca.p12").toString();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        r.add("spring.datasource.url", () -> "jdbc:h2:mem:lifecycle;DB_CLOSE_DELAY=-1");
    }

    @Autowired
    CertificateService service;
    @Autowired
    CertificateAuthority ca;

    @Test
    void issuedCertificateChainsToRootAndCarriesSans() throws Exception {
        var issued = service.issueWithGeneratedKey("payments.internal",
                List.of("payments.internal", "payments.svc.cluster.local"), 30, true, "payments-team");

        X509Certificate cert = Pem.readCertificate(issued.certificate().getPem());
        cert.verify(ca.caCertificate().getPublicKey());
        assertThat(cert.getSubjectX500Principal().getName()).isEqualTo("CN=payments.internal");
        assertThat(cert.getSubjectAlternativeNames()).hasSize(2);
        assertThat(cert.getBasicConstraints()).isEqualTo(-1); // not a CA
        assertThat(issued.privateKeyPem()).contains("PRIVATE KEY");
        assertThat(service.validate(issued.certificate().getPem()).valid()).isTrue();
    }

    @Test
    void signsCsrAfterVerifyingProofOfPossession() throws Exception {
        KeyPair kp = ca.newKeyPair();
        var csr = new JcaPKCS10CertificationRequestBuilder(new X500Name("CN=orders.internal"), kp.getPublic())
                .build(new JcaContentSignerBuilder("SHA256withECDSA").build(kp.getPrivate()));

        ManagedCertificate cert = service.issueFromCsr(Pem.write(csr), List.of("orders.internal"), 10, false, "orders");

        assertThat(cert.getCommonName()).isEqualTo("orders.internal");
        assertThat(Pem.readCertificate(cert.getPem()).getPublicKey()).isEqualTo(kp.getPublic());
    }

    @Test
    void renewalKeepsKeySupersedesOldAndLinksLineage() throws Exception {
        var original = service.issueWithGeneratedKey("ledger.internal", List.of(), 5, true, "ledger").certificate();

        ManagedCertificate renewed = service.renew(original.getId());

        assertThat(renewed.getRenewedFromId()).isEqualTo(original.getId());
        assertThat(service.get(original.getId()).getStatus()).isEqualTo(CertificateStatus.SUPERSEDED);
        assertThat(renewed.getNotAfter()).isAfter(original.getNotAfter());
        assertThat(Pem.readCertificate(renewed.getPem()).getPublicKey())
                .isEqualTo(Pem.readCertificate(original.getPem()).getPublicKey());
    }

    @Test
    void revokedCertificateAppearsOnCrlAndFailsValidation() throws Exception {
        var cert = service.issueWithGeneratedKey("legacy.internal", List.of(), 30, false, "legacy").certificate();

        service.revoke(cert.getId(), RevocationReason.KEY_COMPROMISE);
        X509CRL crl = service.currentCrl();

        crl.verify(ca.caCertificate().getPublicKey());
        assertThat(crl.getRevokedCertificate(new BigInteger(cert.getSerialHex(), 16))).isNotNull();
        var result = service.validate(cert.getPem());
        assertThat(result.valid()).isFalse();
        assertThat(result.problems()).anyMatch(p -> p.contains("revoked"));
        assertThatThrownBy(() -> service.renew(cert.getId())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.revoke(cert.getId(), null)).isInstanceOf(IllegalStateException.class);
    }

    @Autowired
    com.kiran.pki.cert.RenewalScheduler scheduler;

    @Test
    void reconcileAutoRenewsOnlyOptedInCertificatesInsideWindow() throws Exception {
        var auto = service.issueWithGeneratedKey("auto.internal", List.of(), 3, true, "a").certificate();
        var manual = service.issueWithGeneratedKey("manual.internal", List.of(), 3, false, "b").certificate();
        var longLived = service.issueWithGeneratedKey("long.internal", List.of(), 365, true, "c").certificate();

        scheduler.reconcile();

        assertThat(service.get(auto.getId()).getStatus()).isEqualTo(CertificateStatus.SUPERSEDED);
        assertThat(service.get(manual.getId()).getStatus()).isEqualTo(CertificateStatus.ACTIVE);
        assertThat(service.get(longLived.getId()).getStatus()).isEqualTo(CertificateStatus.ACTIVE);
    }

    @Test
    void certificateFromForeignCaIsRejected() throws Exception {
        KeyPair other = ca.newKeyPair();
        var foreignCa = new com.kiran.pki.config.PkiProperties("CN=Rogue CA",
                Files.createTempDirectory("rogue").resolve("ca.p12").toString(), "x", 365, 30, 30, 24);
        X509Certificate foreign = new CertificateAuthority(foreignCa).issue("evil.internal", List.of(), other.getPublic(), 30);

        var result = service.validate(Pem.write(foreign));

        assertThat(result.valid()).isFalse();
        assertThat(result.problems()).contains("Signature does not chain to this CA");
    }
}
