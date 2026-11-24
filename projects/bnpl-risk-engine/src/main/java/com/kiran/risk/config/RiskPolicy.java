package com.kiran.risk.config;

import com.kiran.risk.model.Models.Bureau;
import com.kiran.risk.model.Models.Product;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * Externalized underwriting policy. Changing thresholds or the second-look bureau order is a config change,
 * not a code change: the same idea as a ruleset managed outside the service.
 */
@ConfigurationProperties(prefix = "risk")
public record RiskPolicy(
        Bureau primaryBureau,
        String primaryPackage,
        int minScore,
        int minTradelines,
        BigDecimal maxDti,
        int maxDelinquencies24m,
        int maxInquiries6m,
        Set<String> unsupportedStates,
        List<TierRule> tiers,
        SecondLook secondLook) {

    /** Score floor → tier, max line and APR per product. Evaluated top-down. */
    public record TierRule(String tier, int minScore, BigDecimal maxAmount, BigDecimal payIn4Apr,
                           BigDecimal payMonthlyApr) {
    }

    /**
     * @param products  products eligible for a second look (e.g. only long-term financing)
     * @param alternates ordered list of alternate bureau pulls to try
     * @param minScore   score floor applied to alternate-bureau scores (alternate data can use its own scale)
     */
    public record SecondLook(boolean enabled, Set<Product> products, List<Alternate> alternates, int minScore) {
    }

    public record Alternate(Bureau bureau, String vendorId, String packageName) {
    }
}
