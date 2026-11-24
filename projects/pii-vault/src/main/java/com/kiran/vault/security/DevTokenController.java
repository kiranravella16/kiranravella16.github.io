package com.kiran.vault.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/** Local-only token minting so the approval workflow can be demoed without an identity provider. */
@RestController
@ConditionalOnProperty(name = "pii.dev-tokens.enabled", havingValue = "true")
public class DevTokenController {

    private final JwtEncoder encoder;

    public DevTokenController(JwtEncoder encoder) {
        this.encoder = encoder;
    }

    public record TokenRequest(String subject, List<String> roles) {
    }

    @PostMapping("/dev/token")
    public Map<String, String> token(@RequestBody TokenRequest req) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("pii-vault-dev")
                .subject(req.subject())
                .claim("roles", req.roles())
                .issuedAt(now)
                .expiresAt(now.plus(1, ChronoUnit.HOURS))
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return Map.of("access_token", token, "token_type", "Bearer");
    }
}
