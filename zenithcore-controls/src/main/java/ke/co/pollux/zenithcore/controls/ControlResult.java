package ke.co.pollux.zenithcore.controls;

import java.util.List;

/**
 * Outcome of a control: allowed, or blocked with the reasons in plain words, ready to show the user.
 */
public record ControlResult(String control, List<String> reasons) {

    public ControlResult {
        reasons = List.copyOf(reasons);
    }

    public static ControlResult pass(String control) {
        return new ControlResult(control, List.of());
    }

    public static ControlResult block(String control, List<String> reasons) {
        return new ControlResult(control, reasons);
    }

    public boolean allowed() {
        return reasons.isEmpty();
    }

    /** One message for the user, for example "Supplier pre-qualification: ...; ...". */
    public String message() {
        return allowed() ? control + ": passed" : control + ": " + String.join("; ", reasons);
    }
}
