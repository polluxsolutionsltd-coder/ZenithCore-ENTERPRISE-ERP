package ke.co.pollux.zenithcore.controls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import ke.co.pollux.zenithcore.controls.AssetRegister.Condition;
import ke.co.pollux.zenithcore.controls.AssetRegister.Line;
import ke.co.pollux.zenithcore.controls.AssetRegister.Result;
import ke.co.pollux.zenithcore.controls.AssetRegister.Summary;

class AssetRegisterTest {

    private static final String P = "JKIP-";

    @Test
    void tags_carry_a_luhn_check_digit() {
        assertEquals("JKIP-000123-0", AssetRegister.tag(P, 123, 6));
        assertEquals("JKIP-000001-8", AssetRegister.tag(P, 1, 6));
        assertEquals("JKIP-999999-6", AssetRegister.tag(P, 999999, 6));
        assertThrows(IllegalArgumentException.class, () -> AssetRegister.tag(P, 1000000, 6));
        assertThrows(IllegalArgumentException.class, () -> AssetRegister.tag(P, 0, 6));
    }

    @Test
    void mistyped_tags_are_caught() {
        assertEquals(123, AssetRegister.number(P, "jkip-000123-0 "));
        assertEquals(-1, AssetRegister.number(P, "JKIP-000123-1"));   // wrong check digit
        assertEquals(-1, AssetRegister.number(P, "JKIP-000132-0"));   // swapped digits
        assertEquals(-1, AssetRegister.number(P, "JKIP-000124-0"));   // one digit wrong
        assertEquals(-1, AssetRegister.number(P, "KEB-000123-0"));    // another prefix
        assertEquals(-1, AssetRegister.number(P, "JKIP-0001230"));    // no separator
        assertEquals(-1, AssetRegister.number(P, "JKIP--0"));
        assertEquals(-1, AssetRegister.number(P, null));
        assertTrue(AssetRegister.valid(P, AssetRegister.tag(P, 77, 6)));
    }

    @Test
    void next_number_follows_the_highest_valid_tag() {
        assertEquals(1, AssetRegister.next(P, List.of()));
        assertEquals(124, AssetRegister.next(P, List.of("JKIP-000005-9", "JKIP-000123-0", "bad", "KEB-000900-0")));
    }

    private static Line line(int no, String asset, Result r, String expected, String found, Condition c, String remarks, int custodian) {
        return new Line(no, asset, asset == null ? null : "JKIP-000001-8", r, expected, found, c, remarks, custodian);
    }

    @Test
    void a_complete_verification_passes() {
        List<Line> l = List.of(line(10, "Laptop", Result.FOUND, "Block A", null, Condition.GOOD, null, 7),
                line(20, "Projector", Result.FOUND_ELSEWHERE, "Block A", "Boardroom", Condition.FAIR, null, 7),
                line(30, "Printer", Result.NOT_FOUND, "Block A", null, null, "Searched Block A and stores; custodian on leave", 7),
                new Line(40, null, "JKIP-000123-0", Result.NOT_IN_REGISTER, null, "Block A", Condition.GOOD, "HP desktop SN 5CG1", 0));
        assertTrue(AssetRegister.complete(l, 9, P).allowed());
    }

    @Test
    void each_line_needs_a_full_result() {
        List<String> r = AssetRegister.complete(List.of(
                line(10, "Laptop", null, "Block A", null, null, null, 7),
                line(20, "Desk", Result.FOUND, "Block A", null, null, null, 7),
                line(30, "Projector", Result.FOUND_ELSEWHERE, "Block A", " ", Condition.GOOD, null, 7),
                line(40, "Chair", Result.FOUND_ELSEWHERE, "Block A", "Block A", Condition.GOOD, null, 7),
                line(50, "Printer", Result.NOT_FOUND, "Block A", null, null, "", 7),
                line(60, "Scanner", Result.NOT_IN_REGISTER, "Block A", null, Condition.GOOD, "x", 7),
                new Line(70, null, "JKIP-000123-9", Result.NOT_IN_REGISTER, null, null, Condition.GOOD, null, 0),
                new Line(80, null, null, Result.FOUND, null, null, Condition.GOOD, null, 0)), 9, P).reasons();
        assertEquals(List.of("line 10 (Laptop): record whether the asset was found",
                "line 20 (Desk): record the condition",
                "line 30 (Projector): record where the asset was found",
                "line 40 (Chair): found at its own location Block A; record it as found",
                "line 50 (Printer): explain what was done to find it",
                "line 60 (Scanner): the asset is in the register; record it as found or found elsewhere",
                "line 70 (JKIP-000123-9): describe the asset (make, model, serial number)",
                "line 70 (JKIP-000123-9): tag JKIP-000123-9 is not a valid tag (mistyped, or another institution's)",
                "line 80 (): choose the asset, or record the line as not in the register"), r);
    }

    @Test
    void custodians_do_not_verify_their_own_assets() {
        assertEquals(List.of("line 10 (Laptop): the person verifying is the asset's custodian, so someone else must verify it"),
                AssetRegister.complete(List.of(line(10, "Laptop", Result.FOUND, "A", null, Condition.GOOD, null, 9)), 9, P).reasons());
        assertFalse(AssetRegister.complete(List.of(), 9, P).allowed());
    }

    @Test
    void summary_lists_the_exceptions() {
        Summary s = AssetRegister.summary(List.of(line(10, "Laptop", Result.FOUND, "A", null, Condition.GOOD, null, 7),
                line(20, "Projector", Result.FOUND_ELSEWHERE, "A", "B", Condition.UNSERVICEABLE, null, 7),
                line(30, "Printer", Result.NOT_FOUND, "A", null, Condition.POOR, "x", 7),
                line(40, "Desk", Result.FOUND, "A", null, Condition.POOR, null, 7),
                new Line(50, null, "T", Result.NOT_IN_REGISTER, null, "A", Condition.FAIR, "x", 0)));
        assertEquals(2, s.byResult().get(Result.FOUND));
        assertEquals(List.of(30), s.notFound().stream().map(Line::lineNo).toList());
        assertEquals(List.of(20), s.elsewhere().stream().map(Line::lineNo).toList());
        assertEquals(List.of(20, 40), s.forSurvey().stream().map(Line::lineNo).toList()); // not-found printer's condition ignored
        assertEquals(List.of(50), s.unrecorded().stream().map(Line::lineNo).toList());
        assertEquals(5, s.exceptions());
    }
}
