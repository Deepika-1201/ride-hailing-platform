package com.ridehailing.operations.web;

import com.ridehailing.operations.app.InvariantReports;
import com.ridehailing.operations.app.InvariantReports.InvariantReport;
import com.ridehailing.platform.AllowedRoles;
import com.ridehailing.platform.ApiController;
import com.ridehailing.shared.UserRole;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** The simulator's check after every run (LLD §17.3, ADR-021). */
@ApiController
@AllowedRoles({UserRole.OPS, UserRole.ADMIN})
@RequestMapping("/v1/ops/invariants")
class OpsInvariantController {

    private final InvariantReports reports;

    OpsInvariantController(InvariantReports reports) {
        this.reports = reports;
    }

    @GetMapping
    InvariantReport check() {
        return reports.check();
    }
}
