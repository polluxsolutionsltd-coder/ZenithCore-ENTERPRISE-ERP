package ke.co.pollux.zenithcore.payroll.ke;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Effective-dated statutory rate sets. Exactly one set must cover a pay date.
 *
 * <p>The defaults reflect Pollux's reading of the law at November 2026 and must be validated
 * against current KRA, NSSF and SHA guidance before any production payroll. See
 * {@code docs/payroll/kenya-statutory-rules.md}.
 */
public final class StatutoryRateTable {

    private final List<StatutoryRateSet> sets;

    public StatutoryRateTable(List<StatutoryRateSet> sets) {
        List<StatutoryRateSet> sorted = new ArrayList<>(sets);
        sorted.sort(Comparator.comparing(StatutoryRateSet::effectiveFrom));
        for (int i = 0; i < sorted.size(); i++) {
            for (int j = i + 1; j < sorted.size(); j++) {
                if (sorted.get(i).overlaps(sorted.get(j))) {
                    throw new IllegalArgumentException("Rate sets overlap: from " + sorted.get(i).effectiveFrom()
                            + " and from " + sorted.get(j).effectiveFrom());
                }
            }
        }
        this.sets = List.copyOf(sorted);
    }

    public List<StatutoryRateSet> sets() {
        return sets;
    }

    /** The rate set in force on the pay date. */
    public StatutoryRateSet forDate(LocalDate payDate) {
        return sets.stream()
                .filter(s -> s.covers(payDate))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No statutory rate set covers pay date " + payDate));
    }

    /** Returns a new table with an additional rate set, for example after a change in law. */
    public StatutoryRateTable with(StatutoryRateSet added) {
        List<StatutoryRateSet> all = new ArrayList<>(sets);
        all.add(added);
        return new StatutoryRateTable(all);
    }

    /**
     * Kenyan rates from 1 February 2025.
     * <ul>
     *   <li>PAYE bands: Finance Act 2023 (from 1 July 2023).</li>
     *   <li>SHIF 2.75%, minimum KES 300: Social Health Insurance Act 2023 (from 1 October 2024).</li>
     *   <li>Affordable Housing Levy 1.5% each side: Affordable Housing Act 2024.</li>
     *   <li>SHIF and Housing Levy deductible; pension cap 30,000, post-retirement medical cap 15,000,
     *       mortgage interest cap 30,000, non-cash benefit threshold 5,000: Tax Laws (Amendment) Act 2024
     *       (from 27 December 2024).</li>
     *   <li>NSSF Year 3 limits (8,000 / 72,000) from 1 February 2025; Year 4 (9,000 / 108,000) from 1 February 2026.</li>
     *   <li>Two-thirds rule: Employment Act 2007, section 19(3).</li>
     * </ul>
     */
    public static StatutoryRateTable kenyaDefaults() {
        return new StatutoryRateTable(List.of(
                kenya(LocalDate.of(2025, 2, 1), LocalDate.of(2026, 1, 31),
                        "NSSF Year 3 limits (LEL 8,000, UEL 72,000); PAYE bands per Finance Act 2023; "
                                + "deductibility and caps per Tax Laws (Amendment) Act 2024",
                        "8000", "72000"),
                kenya(LocalDate.of(2026, 2, 1), null,
                        "NSSF Year 4 limits (LEL 9,000, UEL 108,000); PAYE bands per Finance Act 2023 "
                                + "(unchanged by Finance Act 2026 as far as known; validate); "
                                + "deductibility and caps per Tax Laws (Amendment) Act 2024",
                        "9000", "108000")));
    }

    private static StatutoryRateSet kenya(LocalDate from, LocalDate to, String description, String lel, String uel) {
        return new StatutoryRateSet(from, to, description,
                List.of(TaxBand.of("24000", "0.10"),
                        TaxBand.of("8333", "0.25"),
                        TaxBand.of("467667", "0.30"),
                        TaxBand.of("300000", "0.325"),
                        TaxBand.of(null, "0.35")),
                Money.of("2400"),
                new BigDecimal("0.15"), Money.of("5000"),
                new BigDecimal("0.06"), Money.of(lel), Money.of(uel),
                new BigDecimal("0.0275"), Money.of("300"),
                new BigDecimal("0.015"),
                Money.of("50"),
                Money.of("30000"), Money.of("15000"), Money.of("30000"),
                Money.of("5000"),
                2, 3);
    }
}
