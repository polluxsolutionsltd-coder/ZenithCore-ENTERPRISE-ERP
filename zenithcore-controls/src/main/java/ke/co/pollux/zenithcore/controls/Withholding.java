package ke.co.pollux.zenithcore.controls;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Kenyan withholding on supplier invoices: withholding income tax by type of payment (professional,
 * management, training, consultancy, contractual fees, sales promotion, ...) and withholding VAT on
 * standard-rated supplies when the institution is an appointed withholding VAT agent.
 *
 * <p>All rates, thresholds and dates are data ({@link Rate}); nothing statutory is in this code. Each
 * deduction is rounded to the cent, half up, and the calculation returns a trace for the user.
 */
public final class Withholding {

    public enum Kind {
        INCOME_TAX("W", "Withholding tax"), VAT("V", "Withholding VAT");

        public final String code;
        public final String label;

        Kind(String code, String label) {
            this.code = code;
            this.label = label;
        }

        public static Kind of(String code) {
            return "V".equals(code) ? VAT : INCOME_TAX;
        }
    }

    /**
     * A dated rate.
     *
     * @param monthlyThreshold for residents: no withholding if the supplier's total of this type in the month,
     *                         this invoice included, is at or below it; null or zero means none
     */
    public record Rate(String type, String name, Kind kind, boolean nonResident, BigDecimal ratePct,
                       BigDecimal monthlyThreshold, LocalDate validFrom, LocalDate validTo) {

        boolean appliesOn(LocalDate d) {
            return (validFrom == null || !d.isBefore(validFrom)) && (validTo == null || !d.isAfter(validTo));
        }
    }

    /** One invoice line: amount before VAT, its VAT rate, and its withholding type (null if none). */
    public record Line(int lineNo, String description, BigDecimal netAmount, BigDecimal vatRatePct, String type) {
    }

    /** What matters about the supplier. */
    public record Supplier(String name, boolean nonResident, boolean whtExempt, boolean vatRegistered,
                           boolean whvatExempt) {
    }

    /** One deduction to make from the supplier's payable. */
    public record Deduction(String type, String name, Kind kind, BigDecimal base, BigDecimal ratePct,
                            BigDecimal amount) {
    }

    /** Deductions, the working, and problems that stop the invoice (for example a missing rate). */
    public record Result(List<Deduction> deductions, List<String> trace, List<String> problems) {
        public BigDecimal total() {
            return deductions.stream().map(Deduction::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    private Withholding() {
    }

    public static Optional<Rate> find(List<Rate> rates, String type, boolean nonResident, LocalDate date) {
        return rates.stream().filter(r -> r.type().equals(type) && r.nonResident() == nonResident && r.appliesOn(date))
                .findFirst();
    }

    /**
     * @param monthToDate  per withholding type, the supplier's earlier amounts of that type this month
     * @param whvatAgent   true if the institution is an appointed withholding VAT agent
     * @param whvatType    the rate type holding the withholding VAT rate
     */
    public static Result calculate(List<Line> lines, Supplier s, LocalDate date, List<Rate> rates,
            Map<String, BigDecimal> monthToDate, boolean whvatAgent, String whvatType) {
        List<Deduction> out = new ArrayList<>();
        List<String> trace = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        // Withholding income tax, by type
        Map<String, BigDecimal> byType = new LinkedHashMap<>();
        for (Line l : lines) {
            if (l.type() != null && !l.type().isBlank()) {
                byType.merge(l.type(), l.netAmount(), BigDecimal::add);
            }
        }
        if (!byType.isEmpty() && s.whtExempt()) {
            trace.add(s.name() + " holds a withholding tax exemption: no withholding tax deducted");
        } else {
            for (Map.Entry<String, BigDecimal> e : byType.entrySet()) {
                BigDecimal base = e.getValue().setScale(2, RoundingMode.HALF_UP);
                Optional<Rate> r = find(rates, e.getKey(), s.nonResident(), date);
                if (r.isEmpty()) {
                    problems.add("no " + (s.nonResident() ? "non-resident" : "resident") + " withholding tax rate for "
                            + e.getKey() + " on " + date);
                    continue;
                }
                Rate rate = r.get();
                BigDecimal threshold = rate.monthlyThreshold();
                if (!s.nonResident() && threshold != null && threshold.signum() > 0) {
                    BigDecimal earlier = monthToDate.getOrDefault(e.getKey(), BigDecimal.ZERO);
                    BigDecimal month = earlier.add(base);
                    if (month.compareTo(threshold) <= 0) {
                        trace.add(rate.name() + ": " + Amounts.fmt(month) + " this month is at or below "
                                + Amounts.fmt(threshold) + ", no withholding tax");
                        continue;
                    }
                    if (earlier.signum() > 0) {
                        trace.add(rate.name() + ": this month's total " + Amounts.fmt(month) + " is above "
                                + Amounts.fmt(threshold) + "; check withholding on earlier invoices this month ("
                                + Amounts.fmt(earlier) + ")");
                    }
                }
                BigDecimal amount = base.multiply(rate.ratePct()).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
                out.add(new Deduction(rate.type(), rate.name(), Kind.INCOME_TAX, base, rate.ratePct(), amount));
                trace.add(rate.name() + ": " + pct(rate.ratePct()) + " of " + Amounts.fmt(base) + " = " + Amounts.fmt(amount));
            }
        }

        // Withholding VAT on standard-rated supplies
        if (whvatAgent) {
            BigDecimal vatable = lines.stream().filter(l -> l.vatRatePct() != null && l.vatRatePct().signum() > 0)
                    .map(Line::netAmount).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
            if (vatable.signum() == 0) {
                trace.add("Withholding VAT: no standard-rated lines");
            } else if (s.nonResident()) {
                trace.add("Withholding VAT: not applied to a non-resident supplier");
            } else if (!s.vatRegistered()) {
                trace.add("Withholding VAT: " + s.name() + " is not VAT-registered");
            } else if (s.whvatExempt()) {
                trace.add("Withholding VAT: " + s.name() + " holds an exemption certificate");
            } else {
                Optional<Rate> r = find(rates, whvatType, false, date);
                if (r.isEmpty()) {
                    problems.add("no withholding VAT rate (" + whvatType + ") on " + date);
                } else {
                    BigDecimal amount = vatable.multiply(r.get().ratePct()).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
                    out.add(new Deduction(r.get().type(), r.get().name(), Kind.VAT, vatable, r.get().ratePct(), amount));
                    trace.add(r.get().name() + ": " + pct(r.get().ratePct()) + " of taxable value " + Amounts.fmt(vatable)
                            + " = " + Amounts.fmt(amount));
                }
            }
        }
        return new Result(List.copyOf(out), List.copyOf(trace), List.copyOf(problems));
    }

    private static String pct(BigDecimal p) {
        return p.stripTrailingZeros().toPlainString() + "%";
    }
}
