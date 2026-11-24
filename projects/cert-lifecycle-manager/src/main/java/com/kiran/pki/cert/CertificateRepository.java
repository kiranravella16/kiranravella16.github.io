package com.kiran.pki.cert;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CertificateRepository extends JpaRepository<ManagedCertificate, Long> {

    Optional<ManagedCertificate> findBySerialHex(String serialHex);

    List<ManagedCertificate> findByStatusOrderByNotAfterAsc(CertificateStatus status);

    List<ManagedCertificate> findByStatus(CertificateStatus status);

    List<ManagedCertificate> findByStatusAndNotAfterBefore(CertificateStatus status, Instant cutoff);

    long countByStatus(CertificateStatus status);

    long countByStatusAndNotAfterBefore(CertificateStatus status, Instant cutoff);
}
