package com.kiran.risk.bureau;

import com.kiran.risk.model.Models.Bureau;
import com.kiran.risk.model.Models.BureauReport;
import com.kiran.risk.model.Models.LoanApplication;

/** Abstraction over a credit bureau pull. Real implementations would be vendor HTTP clients behind a gateway. */
@FunctionalInterface
public interface BureauGateway {

    BureauReport pull(LoanApplication application, Bureau bureau, String vendorId, String packageName);
}
