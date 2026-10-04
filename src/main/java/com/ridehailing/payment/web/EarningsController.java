package com.ridehailing.payment.web;

import com.ridehailing.payment.app.Earnings;
import com.ridehailing.payment.app.Earnings.EarningsView;
import com.ridehailing.platform.AllowedRoles;
import com.ridehailing.platform.ApiController;
import com.ridehailing.platform.Caller;
import com.ridehailing.shared.UserRole;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** The signed-in driver's earnings per day (FR-D4, LLD §11.8). */
@ApiController
@AllowedRoles(UserRole.DRIVER)
@RequestMapping("/v1/drivers/me/earnings")
class EarningsController {

    private final Earnings earnings;

    EarningsController(Earnings earnings) {
        this.earnings = earnings;
    }

    @GetMapping
    EarningsView earnings(Caller caller, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        return earnings.between(caller.userId(), from, to);
    }
}
