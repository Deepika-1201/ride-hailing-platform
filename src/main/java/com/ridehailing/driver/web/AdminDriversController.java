package com.ridehailing.driver.web;

import com.ridehailing.driver.DriverApi.Vehicle;
import com.ridehailing.driver.Verification;
import com.ridehailing.driver.app.DriverAdministration;
import com.ridehailing.driver.app.DriverAdministration.AdminDriver;
import com.ridehailing.platform.AllowedRoles;
import com.ridehailing.platform.ApiController;
import com.ridehailing.platform.Caller;
import com.ridehailing.platform.Cursor;
import com.ridehailing.shared.Page;
import com.ridehailing.shared.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

/** Driver onboarding: drivers, verification and vehicles (LLD §13.3). */
@ApiController
@AllowedRoles(UserRole.ADMIN)
@RequestMapping("/v1/admin")
class AdminDriversController {

    private final DriverAdministration drivers;

    AdminDriversController(DriverAdministration drivers) {
        this.drivers = drivers;
    }

    @GetMapping("/drivers")
    Page<AdminDriver> drivers(@RequestParam(name = "city_id", required = false) String cityId,
            @RequestParam(required = false) Verification verification,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return drivers.list(cityId, verification, Cursor.decode(cursor), Cursor.limit(limit));
    }

    @PostMapping("/drivers")
    ResponseEntity<AdminDriver> createDriver(Caller caller, @Valid @RequestBody DriverCreate request) {
        AdminDriver driver = drivers.create(caller, request.phone(), request.firstName(), request.lastName(),
                request.cityId());
        return ResponseEntity.status(HttpStatus.CREATED).body(driver);
    }

    @GetMapping("/drivers/{driverId}")
    AdminDriver driver(@PathVariable UUID driverId) {
        return drivers.get(driverId);
    }

    @PostMapping("/drivers/{driverId}/verification")
    AdminDriver setVerification(Caller caller, @PathVariable UUID driverId,
            @Valid @RequestBody VerificationRequest request) {
        return drivers.setVerification(caller, driverId, Verification.valueOf(request.status()), request.reason());
    }

    @PostMapping("/vehicles")
    ResponseEntity<Vehicle> createVehicle(Caller caller, @Valid @RequestBody VehicleCreate request) {
        Vehicle vehicle = drivers.addVehicle(caller, request.driverId(), request.category(), request.plate(),
                request.make(), request.model(), request.colour());
        return ResponseEntity.status(HttpStatus.CREATED).body(vehicle);
    }

    @PatchMapping("/vehicles/{vehicleId}")
    Vehicle updateVehicle(Caller caller, @PathVariable UUID vehicleId, @Valid @RequestBody VehicleUpdate request) {
        return drivers.setVehicleActive(caller, vehicleId, request.active(), request.version());
    }

    record DriverCreate(
            @NotNull @Pattern(regexp = "\\+[1-9][0-9]{7,14}") String phone,
            @NotNull @Size(min = 1, max = 60) String firstName,
            @Size(max = 60) String lastName,
            @NotNull @Pattern(regexp = "[a-z]{3,8}") String cityId) {
    }

    /** Admins verify or reject; {@code PENDING} is only where onboarding starts. */
    record VerificationRequest(
            @NotNull @Pattern(regexp = "VERIFIED|REJECTED") String status,
            @NotNull @Size(min = 1, max = 500) String reason) {
    }

    record VehicleCreate(
            @NotNull UUID driverId,
            @NotNull @Pattern(regexp = "[A-Z][A-Z0-9_]{1,19}") String category,
            @NotNull @Pattern(regexp = "[A-Z0-9 -]{4,15}") String plate,
            @NotNull @Size(min = 1, max = 40) String make,
            @NotNull @Size(min = 1, max = 40) String model,
            @NotNull @Size(min = 1, max = 30) String colour) {
    }

    record VehicleUpdate(@NotNull Boolean active, @NotNull Integer version) {
    }
}
