package ke.co.pollux.zenithcore.payroll.ke;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import ke.co.pollux.zenithcore.payroll.ke.EstablishmentChecks.Position;

/**
 * Establishment position as at a date: for each approved position, how many hold it, how many posts
 * are vacant or over-filled, and the monthly basic and fixed pay of the holders. Employees whose
 * position is missing or not in force are listed separately.
 */
public final class EstablishmentSummary {

    /** An employee in post on the date, with their position code (null if none). */
    public record Holder(String employeeId, String name, String positionCode, BigDecimal basicSalary,
                         BigDecimal fixedPay) {
    }

    /** One position's line. */
    public record Row(Position position, int filled, int vacant, int over, BigDecimal basicCost, BigDecimal fixedCost,
                      List<String> holders) {
    }

    /** The whole report. */
    public record Summary(List<Row> rows, int approved, int filled, int vacant, int over, BigDecimal basicCost,
                          BigDecimal fixedCost, List<String> withoutPosition) {
    }

    private EstablishmentSummary() {
    }

    public static Summary of(List<Position> positions, List<Holder> holders, LocalDate asAt) {
        List<Row> rows = new ArrayList<>();
        int approved = 0, filled = 0, vacant = 0, over = 0;
        BigDecimal basic = Money.ZERO, fixed = Money.ZERO;
        List<Holder> placed = new ArrayList<>();
        for (Position p : positions) {
            if (!p.inForceOn(asAt)) {
                continue;
            }
            List<Holder> in = holders.stream().filter(h -> Objects.equals(h.positionCode(), p.code())).toList();
            placed.addAll(in);
            BigDecimal b = Money.round(in.stream().map(Holder::basicSalary).reduce(BigDecimal.ZERO, BigDecimal::add));
            BigDecimal f = Money.round(in.stream().map(Holder::fixedPay).reduce(BigDecimal.ZERO, BigDecimal::add));
            int n = in.size();
            int v = Math.max(0, p.approvedHeadcount() - n);
            int o = Math.max(0, n - p.approvedHeadcount());
            rows.add(new Row(p, n, v, o, b, f, in.stream().map(h -> h.employeeId() + " " + h.name()).toList()));
            approved += p.approvedHeadcount();
            filled += n;
            vacant += v;
            over += o;
            basic = basic.add(b);
            fixed = fixed.add(f);
        }
        List<String> without = holders.stream().filter(h -> !placed.contains(h))
                .map(h -> h.employeeId() + " " + h.name()).toList();
        return new Summary(List.copyOf(rows), approved, filled, vacant, over, Money.round(basic), Money.round(fixed),
                without);
    }
}
