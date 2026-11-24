package com.kiran.vault.record;

import com.kiran.vault.audit.AuditService;
import com.kiran.vault.crypto.EnvelopeCrypto;
import com.kiran.vault.crypto.EnvelopeCrypto.WrappedKey;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

@Service
public class PiiRecordService {

    private final PiiRecordRepository repo;
    private final EnvelopeCrypto crypto;
    private final AuditService audit;

    public PiiRecordService(PiiRecordRepository repo, EnvelopeCrypto crypto, AuditService audit) {
        this.repo = repo;
        this.crypto = crypto;
        this.audit = audit;
    }

    public record NewRecord(String customerRef, String ssn, String dateOfBirth, String email, String phone) {
    }

    /** What callers see by default: masked hints only, no ciphertext and no plaintext. */
    public record MaskedView(String id, String customerRef, String ssn, String dateOfBirth, String email, String phone,
                             int kekVersion) {
        static MaskedView of(PiiRecord r) {
            return new MaskedView(r.getId(), r.getCustomerRef(),
                    r.getSsnLast4() == null ? null : "***-**-" + r.getSsnLast4(),
                    r.hasDob() ? "****-**-**" : null,
                    r.getEmailMasked(),
                    r.getPhoneLast4() == null ? null : "(***) ***-" + r.getPhoneLast4(),
                    r.getKekVersion());
        }
    }

    @Transactional
    public MaskedView store(NewRecord in, String actor) {
        String id = UUID.randomUUID().toString();
        SecretKey dek = crypto.newDataKey();
        PiiRecord r = new PiiRecord(id, in.customerRef());

        if (in.ssn() != null) {
            String digits = normalizeSsn(in.ssn());
            r.setSsn(crypto.encryptField(dek, id, PiiField.SSN.name(), digits),
                    crypto.blindIndex(digits), digits.substring(5));
        }
        r.setDob(crypto.encryptField(dek, id, PiiField.DATE_OF_BIRTH.name(), in.dateOfBirth()));
        if (in.email() != null) {
            r.setEmail(crypto.encryptField(dek, id, PiiField.EMAIL.name(), in.email()), maskEmail(in.email()));
        }
        if (in.phone() != null) {
            String digits = in.phone().replaceAll("\\D", "");
            r.setPhone(crypto.encryptField(dek, id, PiiField.PHONE.name(), digits),
                    digits.length() >= 4 ? digits.substring(digits.length() - 4) : digits);
        }
        WrappedKey wk = crypto.wrap(dek, id);
        r.setWrappedDek(Base64.getEncoder().encodeToString(wk.wrapped()), wk.kekVersion());
        repo.save(r);
        audit.record(actor, "RECORD_CREATED", id, "customerRef=" + in.customerRef());
        return MaskedView.of(r);
    }

    @Transactional(readOnly = true)
    public MaskedView masked(String id) {
        return MaskedView.of(load(id));
    }

    @Transactional
    public List<MaskedView> searchBySsn(String ssn, String actor) {
        List<PiiRecord> hits = repo.findBySsnIndex(crypto.blindIndex(normalizeSsn(ssn)));
        audit.record(actor, "SSN_SEARCH", null, "hits=" + hits.size());
        return hits.stream().map(MaskedView::of).toList();
    }

    /** Decrypts the requested fields. Callers must have already passed the approval checks in AccessService. */
    @Transactional(readOnly = true)
    public Map<PiiField, String> decrypt(String id, Set<PiiField> fields) {
        PiiRecord r = load(id);
        SecretKey dek = crypto.unwrap(
                new WrappedKey(r.getKekVersion(), Base64.getDecoder().decode(r.getWrappedDek())), id);
        Map<PiiField, String> out = new EnumMap<>(PiiField.class);
        for (PiiField f : fields) {
            out.put(f, crypto.decryptField(dek, id, f.name(), r.cipherFor(f)));
        }
        return out;
    }

    /** Re-wraps every DEK that isn't on the active KEK version. Returns how many records were migrated. */
    @Transactional
    public int rewrapToActiveKek(String actor) {
        List<PiiRecord> stale = repo.findByKekVersionNot(crypto.activeKekVersion());
        for (PiiRecord r : stale) {
            WrappedKey next = crypto.rewrap(
                    new WrappedKey(r.getKekVersion(), Base64.getDecoder().decode(r.getWrappedDek())), r.getId());
            r.setWrappedDek(Base64.getEncoder().encodeToString(next.wrapped()), next.kekVersion());
        }
        audit.record(actor, "KEK_REWRAP", null, "records=" + stale.size() + " toVersion=" + crypto.activeKekVersion());
        return stale.size();
    }

    private PiiRecord load(String id) {
        return repo.findById(id).orElseThrow(() -> new NoSuchElementException("Record " + id + " not found"));
    }

    static String normalizeSsn(String ssn) {
        String digits = ssn.replaceAll("\\D", "");
        if (digits.length() != 9) {
            throw new IllegalArgumentException("SSN must contain exactly 9 digits");
        }
        return digits;
    }

    static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
