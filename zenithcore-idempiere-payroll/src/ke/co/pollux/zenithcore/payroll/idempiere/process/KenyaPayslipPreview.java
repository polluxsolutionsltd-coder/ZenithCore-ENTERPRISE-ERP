package ke.co.pollux.zenithcore.payroll.idempiere.process;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;

import org.adempiere.base.annotation.Parameter;
import org.adempiere.base.annotation.Process;
import org.compiere.process.SvrProcess;

import ke.co.pollux.zenithcore.payroll.ke.EmployeePayInput;
import ke.co.pollux.zenithcore.payroll.ke.KenyaPayrollEngine;
import ke.co.pollux.zenithcore.payroll.ke.Money;
import ke.co.pollux.zenithcore.payroll.ke.PayResult;

/**
 * Calculates one Kenyan payslip from the amounts entered and shows the full working in the
 * process log. Used by payroll staff to check a single case and by testers to compare against
 * worked payslips. It does not save anything.
 *
 * <p>{@code @Process} is required: iDempiere's annotation-based factory registers only annotated classes.
 */
@Process
public class KenyaPayslipPreview extends SvrProcess {

    @Parameter(name = "ZC_PayDate") private Timestamp payDate;
    @Parameter(name = "ZC_BasicSalary") private BigDecimal basic;
    @Parameter(name = "ZC_HouseAllowance") private BigDecimal house;
    @Parameter(name = "ZC_CommuterAllowance") private BigDecimal commuter;
    @Parameter(name = "ZC_Overtime") private BigDecimal overtime;
    @Parameter(name = "ZC_NonCashBenefits") private BigDecimal nonCash;
    @Parameter(name = "ZC_PensionContribution") private BigDecimal pension;
    @Parameter(name = "ZC_InsurancePremium") private BigDecimal insurance;
    @Parameter(name = "ZC_HELB") private BigDecimal helb;
    @Parameter(name = "ZC_OtherDeductions") private BigDecimal other;

    @Override
    protected void prepare() {
        // parameters are filled from the @Parameter annotations
    }

    @Override
    protected String doIt() {
        LocalDate date = payDate == null ? LocalDate.now() : payDate.toLocalDateTime().toLocalDate();
        EmployeePayInput in = new EmployeePayInput("PREVIEW", basic, house, commuter, overtime, null, nonCash,
                pension, insurance, null, null, helb, null, other, null);
        PayResult r = KenyaPayrollEngine.withKenyaDefaults().calculate(in, date);
        for (String line : r.trace()) {
            addLog(line);
        }
        return "Gross " + Money.fmt(r.grossPay()) + " · PAYE " + Money.fmt(r.paye()) + " · NSSF " + Money.fmt(r.nssf())
                + " · SHIF " + Money.fmt(r.shif()) + " · Housing Levy " + Money.fmt(r.housingLevy())
                + " · Net pay KES " + Money.fmt(r.netPay());
    }
}
