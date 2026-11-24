package com.kiran.pki.ca;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/** Small helpers for converting between PEM text and JCA / BouncyCastle objects. */
public final class Pem {

    private Pem() {
    }

    public static String write(Object obj) {
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(sw)) {
            writer.writeObject(obj);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sw.toString();
    }

    public static X509Certificate readCertificate(String pem) {
        Object parsed = read(pem);
        if (!(parsed instanceof X509CertificateHolder holder)) {
            throw new IllegalArgumentException("PEM does not contain an X.509 certificate");
        }
        try {
            return new JcaX509CertificateConverter().getCertificate(holder);
        } catch (CertificateException e) {
            throw new IllegalArgumentException("Invalid certificate", e);
        }
    }

    public static PKCS10CertificationRequest readCsr(String pem) {
        Object parsed = read(pem);
        if (!(parsed instanceof PKCS10CertificationRequest csr)) {
            throw new IllegalArgumentException("PEM does not contain a PKCS#10 certificate signing request");
        }
        return csr;
    }

    private static Object read(String pem) {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object obj = parser.readObject();
            if (obj == null) {
                throw new IllegalArgumentException("Empty or malformed PEM");
            }
            return obj;
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed PEM", e);
        }
    }
}
