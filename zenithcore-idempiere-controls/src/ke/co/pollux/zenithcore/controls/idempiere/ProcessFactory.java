package ke.co.pollux.zenithcore.controls.idempiere;

import org.adempiere.base.AnnotationBasedProcessFactory;

/** Registers the ZenithCore controls processes (classes annotated with @Process). */
public class ProcessFactory extends AnnotationBasedProcessFactory {

    @Override
    protected String[] getPackages() {
        return new String[] {"ke.co.pollux.zenithcore.controls.idempiere.process"};
    }
}
