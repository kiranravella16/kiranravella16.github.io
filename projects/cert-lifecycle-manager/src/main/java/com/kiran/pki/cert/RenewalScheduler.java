package com.kiran.pki.cert;

import com.kiran.pki.config.PkiProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Cert-manager style reconciliation loop: expires lapsed certificates and auto-renews ones inside the
 * renewal window. Each renewal runs in its own transaction so one failure never blocks the rest.
 * Prometheus metrics let Grafana alert before anything lapses in production.
 */
@Component
public class RenewalScheduler {

    private static final Logger log = LoggerFactory.getLogger(RenewalScheduler.class);

    private final CertificateService service;
    private final Counter renewed;
    private final Counter renewalFailures;

    public RenewalScheduler(CertificateRepository repo, CertificateService service, PkiProperties props,
                            MeterRegistry registry) {
        this.service = service;
        this.renewed = Counter.builder("pki_certificates_renewed_total").register(registry);
        this.renewalFailures = Counter.builder("pki_certificate_renewal_failures_total").register(registry);
        Gauge.builder("pki_certificates_expiring_soon", repo, r -> r.countByStatusAndNotAfterBefore(
                        CertificateStatus.ACTIVE, Instant.now().plus(props.renewBeforeDays(), ChronoUnit.DAYS)))
                .description("Active certificates inside the renewal window")
                .register(registry);
        Gauge.builder("pki_certificates_active", repo, r -> r.countByStatus(CertificateStatus.ACTIVE))
                .register(registry);
    }

    @Scheduled(cron = "${pki.reconcile-cron:0 */15 * * * *}")
    public void reconcile() {
        int expired = service.expireLapsed();
        int ok = 0;
        for (Long id : service.renewalCandidates()) {
            try {
                service.renew(id);
                renewed.increment();
                ok++;
            } catch (Exception e) {
                renewalFailures.increment();
                log.error("Auto-renewal failed for certificate id={}", id, e);
            }
        }
        log.info("Reconcile finished: expired={}, renewed={}", expired, ok);
    }
}
