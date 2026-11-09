package com.polluxsolutions.zenithcore.payroll.ke;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One employee's pay data for a month. All amounts are monthly KES; null means zero.
 *
 * <p>Pension contributions, insurance premiums, HELB, staff loans, SACCO and union dues are
 * collected through payroll and reduce net pay. Post-retirement medical fund contributions and
 * mortgage interest are tax allowances only: they reduce taxable pay but are paid outside payroll.
 * If a post-retirement medical contribution is collected through payroll, also record it as a
 * deduction in the payroll plug-in.
 *
 * @param employeeId               payroll number
 * @param basicSalary              basic salary
 * @param houseAllowance           house allowance
 * @param commuterAllowance        commuter / transport allowance
 * @param overtime                 overtime pay for the month
 * @param otherAllowances          any other cash allowances
 * @param nonCashBenefits          total value of non-cash benefits (car, meals and similar)
 * @param pensionContribution      employee contribution to a registered pension scheme (beyond NSSF)
 * @param insurancePremium         qualifying life, education or health insurance premium paid through payroll
 * @param postRetirementMedical    contribution to a registered post-retirement medical fund
 * @param mortgageInterest         interest on a qualifying owner-occupier mortgage
 * @param helb                     HELB repayment per the HELB deduction notice
 * @param staffLoan                staff loan recovery
 * @param sacco                    SACCO deduction
 * @param unionDues                union dues
 */
public record EmployeePayInput(
        String employeeId,
        BigDecimal basicSalary,
        BigDecimal houseAllowance,
        BigDecimal commuterAllowance,
        BigDecimal overtime,
        BigDecimal otherAllowances,
        BigDecimal nonCashBenefits,
        BigDecimal pensionContribution,
        BigDecimal insurancePremium,
        BigDecimal postRetirementMedical,
        BigDecimal mortgageInterest,
        BigDecimal helb,
        BigDecimal staffLoan,
        BigDecimal sacco,
        BigDecimal unionDues) {

    public EmployeePayInput {
        Objects.requireNonNull(employeeId, "employeeId");
        basicSalary = check(Money.nz(basicSalary), "basicSalary");
        houseAllowance = check(Money.nz(houseAllowance), "houseAllowance");
        commuterAllowance = check(Money.nz(commuterAllowance), "commuterAllowance");
        overtime = check(Money.nz(overtime), "overtime");
        otherAllowances = check(Money.nz(otherAllowances), "otherAllowances");
        nonCashBenefits = check(Money.nz(nonCashBenefits), "nonCashBenefits");
        pensionContribution = check(Money.nz(pensionContribution), "pensionContribution");
        insurancePremium = check(Money.nz(insurancePremium), "insurancePremium");
        postRetirementMedical = check(Money.nz(postRetirementMedical), "postRetirementMedical");
        mortgageInterest = check(Money.nz(mortgageInterest), "mortgageInterest");
        helb = check(Money.nz(helb), "helb");
        staffLoan = check(Money.nz(staffLoan), "staffLoan");
        sacco = check(Money.nz(sacco), "sacco");
        unionDues = check(Money.nz(unionDues), "unionDues");
    }

    private static BigDecimal check(BigDecimal value, String name) {
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " cannot be negative");
        }
        return value;
    }

    /** Gross cash pay: all cash earnings for the month. */
    public BigDecimal grossCashPay() {
        return Money.round(basicSalary.add(houseAllowance).add(commuterAllowance).add(overtime).add(otherAllowances));
    }

    public static Builder builder(String employeeId) {
        return new Builder(employeeId);
    }

    /** Builder with every amount defaulting to zero. */
    public static final class Builder {
        private final String employeeId;
        private BigDecimal basic, house, commuter, overtime, other, nonCash, pension, insurance, prmf, mortgage,
                helb, loan, sacco, union;

        private Builder(String employeeId) {
            this.employeeId = employeeId;
        }

        public Builder basic(String v) { basic = new BigDecimal(v); return this; }
        public Builder house(String v) { house = new BigDecimal(v); return this; }
        public Builder commuter(String v) { commuter = new BigDecimal(v); return this; }
        public Builder overtime(String v) { overtime = new BigDecimal(v); return this; }
        public Builder otherAllowances(String v) { other = new BigDecimal(v); return this; }
        public Builder nonCashBenefits(String v) { nonCash = new BigDecimal(v); return this; }
        public Builder pension(String v) { pension = new BigDecimal(v); return this; }
        public Builder insurancePremium(String v) { insurance = new BigDecimal(v); return this; }
        public Builder postRetirementMedical(String v) { prmf = new BigDecimal(v); return this; }
        public Builder mortgageInterest(String v) { mortgage = new BigDecimal(v); return this; }
        public Builder helb(String v) { helb = new BigDecimal(v); return this; }
        public Builder staffLoan(String v) { loan = new BigDecimal(v); return this; }
        public Builder sacco(String v) { sacco = new BigDecimal(v); return this; }
        public Builder unionDues(String v) { union = new BigDecimal(v); return this; }

        public EmployeePayInput build() {
            return new EmployeePayInput(employeeId, basic, house, commuter, overtime, other, nonCash, pension,
                    insurance, prmf, mortgage, helb, loan, sacco, union);
        }
    }
}
