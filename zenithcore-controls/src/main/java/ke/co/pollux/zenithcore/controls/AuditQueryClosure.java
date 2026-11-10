package ke.co.pollux.zenithcore.controls;

import java.util.ArrayList;
import java.util.List;

/**
 * Closing an audit query: it needs a management response, at least one evidence file, and a closer
 * who is not the person responsible for resolving it.
 */
public final class AuditQueryClosure {

    public static final String CONTROL = "Audit query closure";

    private AuditQueryClosure() {
    }

    public static ControlResult check(String queryRef, String managementResponse, int evidenceFiles, int responsibleUser,
            int closingUser) {
        List<String> reasons = new ArrayList<>();
        if (managementResponse == null || managementResponse.isBlank()) {
            reasons.add("record the management response to " + queryRef + " first");
        }
        if (evidenceFiles <= 0) {
            reasons.add("attach the evidence that resolves " + queryRef + " first");
        }
        if (responsibleUser > 0 && responsibleUser == closingUser) {
            reasons.add("you are responsible for resolving " + queryRef + ", so a different person must close it");
        }
        return reasons.isEmpty() ? ControlResult.pass(CONTROL) : ControlResult.block(CONTROL, reasons);
    }
}
