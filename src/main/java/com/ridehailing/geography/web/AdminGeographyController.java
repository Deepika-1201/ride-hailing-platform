package com.ridehailing.geography.web;

import com.ridehailing.geography.app.CategoryAdministration;
import com.ridehailing.geography.app.CityAdministration;
import com.ridehailing.geography.app.CityAdministration.CityChange;
import com.ridehailing.geography.app.CityAdministration.NewCity;
import com.ridehailing.geography.app.SpecialAreaAdministration;
import com.ridehailing.geography.app.SpecialAreaAdministration.NewSpecialArea;
import com.ridehailing.geography.db.CategoryRepository.Category;
import com.ridehailing.geography.db.CategoryRepository.Settings;
import com.ridehailing.geography.db.CityRepository.CityRow;
import com.ridehailing.geography.db.SpecialAreaRepository.SpecialAreaRow;
import com.ridehailing.platform.AllowedRoles;
import com.ridehailing.platform.ApiController;
import com.ridehailing.platform.Caller;
import com.ridehailing.shared.Page;
import com.ridehailing.shared.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import tools.jackson.databind.JsonNode;

/** Cities, service and special areas, categories and their per-city settings (LLD §13.3). */
@ApiController
@AllowedRoles(UserRole.ADMIN)
@RequestMapping("/v1/admin")
class AdminGeographyController {

    private final CityAdministration cities;
    private final SpecialAreaAdministration specialAreas;
    private final CategoryAdministration categories;

    AdminGeographyController(CityAdministration cities, SpecialAreaAdministration specialAreas,
            CategoryAdministration categories) {
        this.cities = cities;
        this.specialAreas = specialAreas;
        this.categories = categories;
    }

    @GetMapping("/cities")
    Page<CityRow> cities() {
        return new Page<>(cities.list(), null);
    }

    @PostMapping("/cities")
    ResponseEntity<CityRow> createCity(Caller caller, @Valid @RequestBody CityCreate request) {
        CityRow city = cities.create(caller, new NewCity(request.id(), request.name(), request.timeZone(),
                request.currency(), request.bounds()));
        return ResponseEntity.status(HttpStatus.CREATED).body(city);
    }

    @GetMapping("/cities/{cityId}")
    CityRow city(@PathVariable String cityId) {
        return cities.get(cityId);
    }

    @PatchMapping("/cities/{cityId}")
    CityRow updateCity(Caller caller, @PathVariable String cityId, @Valid @RequestBody CityUpdate request) {
        return cities.update(caller, cityId, new CityChange(request.name(), request.active(), request.version()));
    }

    @PutMapping("/cities/{cityId}/service-area")
    ResponseEntity<Void> replaceServiceArea(Caller caller, @PathVariable String cityId,
            @Valid @RequestBody ServiceAreaUpdate request) {
        cities.replaceServiceArea(caller, cityId, request.area());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/cities/{cityId}/special-areas")
    Page<SpecialAreaRow> specialAreas(@PathVariable String cityId) {
        return new Page<>(specialAreas.list(cityId), null);
    }

    @PostMapping("/cities/{cityId}/special-areas")
    ResponseEntity<SpecialAreaRow> createSpecialArea(Caller caller, @PathVariable String cityId,
            @Valid @RequestBody SpecialAreaCreate request) {
        SpecialAreaRow area = specialAreas.create(caller, cityId, new NewSpecialArea(request.code(), request.name(),
                request.kind(), request.area(), request.priority() == null ? 0 : request.priority()));
        return ResponseEntity.status(HttpStatus.CREATED).body(area);
    }

    @DeleteMapping("/cities/{cityId}/special-areas/{areaId}")
    ResponseEntity<Void> deactivateSpecialArea(Caller caller, @PathVariable String cityId,
            @PathVariable UUID areaId) {
        specialAreas.deactivate(caller, cityId, areaId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/categories")
    Page<Category> categories() {
        return new Page<>(categories.categories(), null);
    }

    @GetMapping("/cities/{cityId}/categories/{category}")
    Settings cityCategory(@PathVariable String cityId, @PathVariable String category) {
        return categories.get(cityId, category);
    }

    @PutMapping("/cities/{cityId}/categories/{category}")
    Settings putCityCategory(Caller caller, @PathVariable String cityId, @PathVariable String category,
            @Valid @RequestBody CityCategoryRequest request) {
        Settings wanted = new Settings(cityId, category, request.active(), request.offerTtlS(),
                request.searchTimeoutS(), request.radiusStartM(), request.radiusStepM(), request.radiusMaxM(),
                request.ranker(), 0);
        return categories.put(caller, wanted, request.version());
    }

    record CityCreate(
            @NotNull @Pattern(regexp = "[a-z]{3,8}") String id,
            @NotNull @Size(min = 1, max = 80) String name,
            @NotNull String timeZone,
            @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotNull JsonNode bounds) {
    }

    record CityUpdate(@Size(min = 1, max = 80) String name, Boolean active, @NotNull Integer version) {
    }

    record ServiceAreaUpdate(@NotNull JsonNode area) {
    }

    record SpecialAreaCreate(
            @NotNull @Pattern(regexp = "[A-Z0-9-]{2,40}") String code,
            @NotNull @Size(min = 1, max = 80) String name,
            @NotNull @Pattern(regexp = "AIRPORT|STATION|STADIUM|OTHER") String kind,
            @NotNull JsonNode area,
            Integer priority) {
    }

    record CityCategoryRequest(
            @NotNull Boolean active,
            @NotNull @Min(5) @Max(60) Integer offerTtlS,
            @NotNull @Min(30) @Max(900) Integer searchTimeoutS,
            @NotNull @Min(100) Integer radiusStartM,
            @NotNull @Min(0) Integer radiusStepM,
            @NotNull @Min(100) Integer radiusMaxM,
            @NotNull @Pattern(regexp = "nearest|eta|weighted") String ranker,
            Integer version) {
    }
}
