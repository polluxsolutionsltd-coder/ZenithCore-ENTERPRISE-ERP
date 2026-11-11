package ke.co.pollux.zenithcore.payroll.idempiere;

import org.adempiere.base.AnnotationBasedProcessFactory;

/** Registers ZenithCore payroll processes with iDempiere by scanning the process package. */
public class ProcessFactory extends AnnotationBasedProcessFactory {

    @Override
    protected String[] getPackages() {
        return new String[] {"ke.co.pollux.zenithcore.payroll.idempiere.process"};
    }
}
