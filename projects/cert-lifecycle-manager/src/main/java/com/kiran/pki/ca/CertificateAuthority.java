package com.kiran.pki.ca;

import com.kiran.pki.config.PkiProperties;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.CRLNumber;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CRLConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v2CRLBuilder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A minimal but real X.509 certificate authority built on BouncyCastle.
 * <p>
 * The root key pair is persisted to a PKCS#12 keystore so issued certificates stay verifiable across restarts.
 * In production the CA key would live in an HSM / cloud KMS and the keystore password would be injected from a
 * secrets manager (e.g. Akeyless or Vault); the {@link PkiProperties} abstraction keeps that swap local.
 */
@Component
public class CertificateAuthority {

    private static final Logger log = LoggerFactory.getLogger(CertificateAuthority.class);
    private static final String SIG_ALG = "SHA256withECDSA";
    private static final String ALIAS = "root-ca";

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final SecureRandom random = new SecureRandom();
    private final AtomicLong crlNumber = new AtomicLong(1);
    private final PrivateKey caKey;
    private final X509Certificate caCert;

    public CertificateAuthority(PkiProperties props) throws Exception {
        Path keystore = Path.of(props.keystorePath());
        char[] pw = props.keystorePassword().toCharArray();
        if (Files.exists(keystore)) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(keystore)) {
                ks.load(in, pw);
            }
            this.caKey = (PrivateKey) ks.getKey(ALIAS, pw);
            this.caCert = (X509Certificate) ks.getCertificate(ALIAS);
            log.info("Loaded root CA '{}' from {}", caCert.getSubjectX500Principal(), keystore);
        } else {
            KeyPair kp = newKeyPair();
            this.caKey = kp.getPrivate();
            this.caCert = selfSign(kp, props.caSubject(), props.rootValidityDays());
            if (keystore.getParent() != null) {
                Files.createDirectories(keystore.getParent());
            }
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            ks.setKeyEntry(ALIAS, caKey, pw, new Certificate[]{caCert});
            try (OutputStream out = Files.newOutputStream(keystore)) {
                ks.store(out, pw);
            }
            log.info("Generated new root CA '{}' and stored it at {}", caCert.getSubjectX500Principal(), keystore);
        }
    }

    public X509Certificate caCertificate() {
        return caCert;
    }

    public KeyPair newKeyPair() throws GeneralSecurityException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"), random);
        return gen.generateKeyPair();
    }

    /** Issues a TLS leaf certificate (serverAuth + clientAuth, suitable for mTLS between services). */
    public X509Certificate issue(String commonName, List<String> dnsNames, PublicKey subjectKey, int validityDays)
            throws Exception {
        Instant now = Instant.now();
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                new JcaX509CertificateHolder(caCert).getSubject(),
                new BigInteger(127, random),
                Date.from(now.minus(1, ChronoUnit.MINUTES)),
                Date.from(now.plus(validityDays, ChronoUnit.DAYS)),
                new X500Name("CN=" + commonName),
                subjectKey);

        JcaX509ExtensionUtils ext = new JcaX509ExtensionUtils();
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        builder.addExtension(Extension.extendedKeyUsage, false,
                new ExtendedKeyUsage(new KeyPurposeId[]{KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth}));
        builder.addExtension(Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(subjectKey));
        builder.addExtension(Extension.authorityKeyIdentifier, false, ext.createAuthorityKeyIdentifier(caCert));
        if (dnsNames != null && !dnsNames.isEmpty()) {
            GeneralName[] names = dnsNames.stream()
                    .map(d -> new GeneralName(GeneralName.dNSName, d))
                    .toArray(GeneralName[]::new);
            builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(names));
        }
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer()));
    }

    /** Verifies a PKCS#10 CSR's proof-of-possession signature and returns the subject public key. */
    public PublicKey verifyCsr(PKCS10CertificationRequest csr) throws Exception {
        JcaPKCS10CertificationRequest jcaCsr = new JcaPKCS10CertificationRequest(csr);
        boolean valid = csr.isSignatureValid(new JcaContentVerifierProviderBuilder().build(jcaCsr.getPublicKey()));
        if (!valid) {
            throw new IllegalArgumentException("CSR signature is invalid (proof of possession failed)");
        }
        return jcaCsr.getPublicKey();
    }

    public record RevokedEntry(BigInteger serial, Instant revokedAt, int reasonCode) {
    }

    public X509CRL generateCrl(List<RevokedEntry> revoked, int validityHours) throws Exception {
        Instant now = Instant.now();
        X509v2CRLBuilder builder = new JcaX509v2CRLBuilder(caCert, Date.from(now));
        builder.setNextUpdate(Date.from(now.plus(validityHours, ChronoUnit.HOURS)));
        for (RevokedEntry r : revoked) {
            builder.addCRLEntry(r.serial(), Date.from(r.revokedAt()), r.reasonCode());
        }
        builder.addExtension(Extension.cRLNumber, false,
                new CRLNumber(BigInteger.valueOf(crlNumber.getAndIncrement())));
        builder.addExtension(Extension.authorityKeyIdentifier, false,
                new JcaX509ExtensionUtils().createAuthorityKeyIdentifier(caCert));
        return new JcaX509CRLConverter().getCRL(builder.build(signer()));
    }

    private ContentSigner signer() throws Exception {
        return new JcaContentSignerBuilder(SIG_ALG).build(caKey);
    }

    private X509Certificate selfSign(KeyPair kp, String subject, int days) throws Exception {
        Instant now = Instant.now();
        X500Name name = new X500Name(subject);
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name, new BigInteger(127, random),
                Date.from(now.minus(1, ChronoUnit.MINUTES)),
                Date.from(now.plus(days, ChronoUnit.DAYS)),
                name, kp.getPublic());
        JcaX509ExtensionUtils ext = new JcaX509ExtensionUtils();
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(0));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        builder.addExtension(Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(kp.getPublic()));
        ContentSigner signer = new JcaContentSignerBuilder(SIG_ALG).build(kp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }
}
