package ke.co.pollux.zenithcore.controls;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Supplier pre-qualification gate: no LPO, supplier invoice or supplier payment for a supplier who is
 * not pre-qualified on the document date, whose pre-qualification has lapsed, whose KRA tax compliance
 * certificate is missing or expired, or who is blacklisted.
 */
public final class SupplierGate {

    public static final String CONTROL = "Supplier pre-qualification";

    /** Pre-qualification status. Codes are those stored in the database. */
    public enum Status {
        PENDING("P", "pending"), QUALIFIED("Q", "pre-qualified"), SUSPENDED("S", "suspended"),
        BLACKLISTED("B", "blacklisted");

        public final String code;
        public final String label;

        Status(String code, String label) {
            this.code = code;
            this.label = label;
        }

        public static Status of(String code) {
            for (Status s : values()) {
                if (s.code.equals(code)) {
                    return s;
                }
            }
            return PENDING;
        }
    }

    /**
     * A supplier's pre-qualification record.
     *
     * @param qualifiedUntil last day of the pre-qualification period, or null if not set
     * @param tccExpiry      expiry date of the KRA tax compliance certificate, or null if none on file
     */
    public record Supplier(String name, Status status, LocalDate qualifiedUntil, LocalDate tccExpiry,
                           String blacklistReason) {
    }

    private SupplierGate() {
    }

    public static ControlResult check(Supplier s, LocalDate documentDate) {
        List<String> reasons = new ArrayList<>();
        if (s.status() == Status.BLACKLISTED) {
            reasons.add(s.name() + " is blacklisted"
                    + (s.blacklistReason() == null || s.blacklistReason().isBlank() ? "" : " (" + s.blacklistReason().trim() + ")"));
            return ControlResult.block(CONTROL, reasons);
        }
        if (s.status() != Status.QUALIFIED) {
            reasons.add(s.name() + " is " + s.status().label + ", not pre-qualified");
        } else if (s.qualifiedUntil() == null) {
            reasons.add(s.name() + " has no pre-qualification end date");
        } else if (documentDate.isAfter(s.qualifiedUntil())) {
            reasons.add(s.name() + "'s pre-qualification ended on " + s.qualifiedUntil());
        }
        if (s.tccExpiry() == null) {
            reasons.add("no KRA tax compliance certificate on file for " + s.name());
        } else if (documentDate.isAfter(s.tccExpiry())) {
            reasons.add(s.name() + "'s KRA tax compliance certificate expired on " + s.tccExpiry());
        }
        return reasons.isEmpty() ? ControlResult.pass(CONTROL) : ControlResult.block(CONTROL, reasons);
    }
}
