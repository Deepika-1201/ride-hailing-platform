package com.ridehailing.pricing.web;

import com.ridehailing.platform.AllowedRoles;
import com.ridehailing.platform.ApiController;
import com.ridehailing.platform.Caller;
import com.ridehailing.pricing.app.RulePublishing;
import com.ridehailing.pricing.app.SurgeRuleAdministration;
import com.ridehailing.pricing.db.RuleRepository.FareRule;
import com.ridehailing.pricing.db.RuleRepository.FeeRule;
import com.ridehailing.pricing.db.SurgeRuleRepository.SurgeRule;
import com.ridehailing.shared.Page;
import com.ridehailing.shared.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Fare, fee and surge rules (LLD §10.5, §13.3). */
@ApiController
@AllowedRoles(UserRole.ADMIN)
@RequestMapping("/v1/admin")
class AdminPricingController {

    private static final String CITY = "[a-z]{3,8}";
    private static final String CATEGORY = "[A-Z][A-Z0-9_]{1,19}";
    private static final String CURRENCY = "[A-Z]{3}";
    private static final String LOCAL_TIME = "([01][0-9]|2[0-3]):[0-5][0-9]";
    private static final DateTimeFormatter HOURS_MINUTES = DateTimeFormatter.ofPattern("HH:mm");

    private final RulePublishing publishing;
    private final SurgeRuleAdministration surgeRules;

    AdminPricingController(RulePublishing publishing, SurgeRuleAdministration surgeRules) {
        this.publishing = publishing;
        this.surgeRules = surgeRules;
    }

    @GetMapping("/fare-rules")
    Page<FareRule> fareRules(@RequestParam("city_id") String cityId,
            @RequestParam(name = "category", required = false) String category) {
        return new Page<>(publishing.fareRules(cityId, category), null);
    }

    @PostMapping("/fare-rules")
    ResponseEntity<FareRule> publishFareRule(Caller caller, @Valid @RequestBody FareRuleCreate request) {
        FareRule published = publishing.publish(caller, new FareRule(null, request.cityId(), request.category(), 0,
                request.effectiveFrom(), request.basePaise(), request.perKmPaise(), request.perMinPaise(),
                request.minimumPaise(), request.bookingFeePaise(), request.taxBp(), request.commissionBp(),
                request.currency(), null));
        return ResponseEntity.status(HttpStatus.CREATED).body(published);
    }

    @GetMapping("/fee-rules")
    Page<FeeRule> feeRules(@RequestParam("city_id") String cityId,
            @RequestParam(name = "category", required = false) String category) {
        return new Page<>(publishing.feeRules(cityId, category), null);
    }

    @PostMapping("/fee-rules")
    ResponseEntity<FeeRule> publishFeeRule(Caller caller, @Valid @RequestBody FeeRuleCreate request) {
        FeeRule published = publishing.publish(caller, new FeeRule(null, request.cityId(), request.category(), 0,
                request.effectiveFrom(), request.cancellationFeePaise(), request.noShowFeePaise(),
                orDefault(request.freeCancelWindowS(), 120), orDefault(request.lateGraceS(), 300),
                orDefault(request.pickupWaitS(), 300), request.commissionBp(), request.currency(), null));
        return ResponseEntity.status(HttpStatus.CREATED).body(published);
    }

    @GetMapping("/surge-rules")
    Page<SurgeRuleView> surgeRules(@RequestParam("city_id") String cityId) {
        return new Page<>(surgeRules.list(cityId).stream().map(SurgeRuleView::of).toList(), null);
    }

    @PostMapping("/surge-rules")
    ResponseEntity<SurgeRuleView> createSurgeRule(Caller caller, @Valid @RequestBody SurgeRuleCreate request) {
        SurgeRule created = surgeRules.create(caller, new SurgeRule(null, request.cityId(), request.zoneId(),
                request.daysOfWeek(), LocalTime.parse(request.startLocal()), LocalTime.parse(request.endLocal()),
                request.multiplier(), true, 0));
        return ResponseEntity.status(HttpStatus.CREATED).body(SurgeRuleView.of(created));
    }

    @PatchMapping("/surge-rules/{ruleId}")
    SurgeRuleView updateSurgeRule(Caller caller, @PathVariable UUID ruleId,
            @Valid @RequestBody SurgeRuleUpdate request) {
        return SurgeRuleView.of(surgeRules.update(caller, ruleId, request.multiplier(), request.active(),
                request.version()));
    }

    private static int orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    record FareRuleCreate(
            @NotNull @Pattern(regexp = CITY) String cityId,
            @NotNull @Pattern(regexp = CATEGORY) String category,
            Instant effectiveFrom,
            @NotNull @Min(0) Long basePaise,
            @NotNull @Min(0) Long perKmPaise,
            @NotNull @Min(0) Long perMinPaise,
            @NotNull @Min(0) Long minimumPaise,
            @NotNull @Min(0) Long bookingFeePaise,
            @NotNull @Min(0) @Max(5000) Integer taxBp,
            @NotNull @Min(0) @Max(5000) Integer commissionBp,
            @NotNull @Pattern(regexp = CURRENCY) String currency) {
    }

    record FeeRuleCreate(
            @NotNull @Pattern(regexp = CITY) String cityId,
            @NotNull @Pattern(regexp = CATEGORY) String category,
            Instant effectiveFrom,
            @NotNull @Min(0) Long cancellationFeePaise,
            @NotNull @Min(0) Long noShowFeePaise,
            @Min(0) Integer freeCancelWindowS,
            @Min(0) Integer lateGraceS,
            @Min(0) Integer pickupWaitS,
            @NotNull @Min(0) @Max(5000) Integer commissionBp,
            @NotNull @Pattern(regexp = CURRENCY) String currency) {
    }

    record SurgeRuleCreate(
            @NotNull @Pattern(regexp = CITY) String cityId,
            @NotNull @Size(min = 1, max = 60) String zoneId,
            @NotEmpty List<@NotNull @Min(1) @Max(7) Integer> daysOfWeek,
            @NotNull @Pattern(regexp = LOCAL_TIME) String startLocal,
            @NotNull @Pattern(regexp = LOCAL_TIME) String endLocal,
            @NotNull @DecimalMin("1.00") @DecimalMax("2.00") @Digits(integer = 1, fraction = 2) BigDecimal multiplier) {
    }

    record SurgeRuleUpdate(
            @DecimalMin("1.00") @DecimalMax("2.00") @Digits(integer = 1, fraction = 2) BigDecimal multiplier,
            Boolean active,
            @NotNull Integer version) {
    }

    record SurgeRuleView(UUID id, String cityId, String zoneId, List<Integer> daysOfWeek, String startLocal,
            String endLocal, BigDecimal multiplier, boolean active, int version) {

        static SurgeRuleView of(SurgeRule rule) {
            return new SurgeRuleView(rule.id(), rule.cityId(), rule.zoneId(), rule.daysOfWeek(),
                    rule.startLocal().format(HOURS_MINUTES), rule.endLocal().format(HOURS_MINUTES), rule.multiplier(),
                    rule.active(), rule.version());
        }
    }
}
