package ke.co.pollux.zenithcore.payroll.idempiere;

import java.time.LocalDate;
import java.util.logging.Level;

import org.compiere.util.CLogger;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

import ke.co.pollux.zenithcore.payroll.ke.EmployeePayInput;
import ke.co.pollux.zenithcore.payroll.ke.KenyaPayrollEngine;
import ke.co.pollux.zenithcore.payroll.ke.Money;
import ke.co.pollux.zenithcore.payroll.ke.PayResult;

/**
 * Starts the ZenithCore payroll plug-in and runs a smoke calculation, so a broken engine or
 * missing rate set shows up in the server log at start-up rather than during a payroll run.
 */
public class Activator implements BundleActivator {

    private static final CLogger LOG = CLogger.getCLogger(Activator.class);

    @Override
    public void start(BundleContext context) {
        try {
            PayResult r = KenyaPayrollEngine.withKenyaDefaults().calculate(
                    EmployeePayInput.builder("SMOKE").basic("62000").house("12000").commuter("4000").build(),
                    LocalDate.now());
            LOG.info("ZenithCore Kenyan payroll ready. Smoke test: gross " + Money.fmt(r.grossPay())
                    + ", PAYE " + Money.fmt(r.paye()) + ", net " + Money.fmt(r.netPay()));
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "ZenithCore Kenyan payroll smoke test failed", e);
        }
    }

    @Override
    public void stop(BundleContext context) {
        // nothing to release
    }
}
