package com.ridehailing.realtime.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.location.LiveIndex.LocationUpdate;
import com.ridehailing.shared.BoundingBox;
import com.ridehailing.shared.GeoPoint;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** LLD §14.7: clients' messages are read by {@code client-messages.v1.json}'s rules, unknown fields ignored. */
class InboundTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void aLocationNeedsItsFieldsInRange() {
        assertThat(Inbound.location(json("""
                {"type": "location", "seq": 7, "lat": 12.97, "lon": 77.6, "accuracy_m": 8.5, "heading_deg": 359.9,
                 "speed_mps": 0, "device_time": "2026-10-09T08:00:00.120Z", "battery": 80}""")))
                .isEqualTo(new LocationUpdate(7, new GeoPoint(12.97, 77.6), 8.5, 359.9, 0.0,
                        Instant.parse("2026-10-09T08:00:00.120Z")));
        assertThat(Inbound.location(json("""
                {"seq": 0, "lat": -90, "lon": 180, "accuracy_m": 0, "device_time": "2026-10-09T08:00:00Z",
                 "heading_deg": null}""")))
                .isEqualTo(new LocationUpdate(0, new GeoPoint(-90, 180), 0, null, null,
                        Instant.parse("2026-10-09T08:00:00Z")));

        String valid = """
                "seq": 7, "lat": 12.97, "lon": 77.6, "accuracy_m": 8.5, "device_time": "2026-10-09T08:00:00Z\"""";
        for (String broken : new String[] {
            "{" + valid.replace("\"seq\": 7", "\"seq\": -1") + "}",
            "{" + valid.replace("\"seq\": 7", "\"seq\": 7.5") + "}",
            "{" + valid.replace("\"seq\": 7", "\"seq\": \"7\"") + "}",
            "{" + valid.replace("\"lat\": 12.97", "\"lat\": 90.1") + "}",
            "{" + valid.replace("\"lon\": 77.6", "\"lon\": -180.5") + "}",
            "{" + valid.replace("\"accuracy_m\": 8.5", "\"accuracy_m\": -1") + "}",
            "{" + valid.replace("2026-10-09T08:00:00Z", "yesterday") + "}",
            "{" + valid.replace(", \"device_time\": \"2026-10-09T08:00:00Z\"", "") + "}",
            "{" + valid + ", \"heading_deg\": 360}",
            "{" + valid + ", \"speed_mps\": -0.1}",
            "{" + valid + ", \"speed_mps\": \"fast\"}"}) {
            assertThat(Inbound.location(json(broken))).as(broken).isNull();
        }
    }

    @Test
    void aViewportNeedsACityAndABoxWithItsMinimumsFirst() {
        assertThat(Inbound.viewport(json("""
                {"type": "ops_viewport", "city_id": "blr",
                 "bbox": {"min_lat": 12.9, "min_lon": 77.55, "max_lat": 13.02, "max_lon": 77.7}}""")))
                .isEqualTo(new Session.Viewport("blr", new BoundingBox(12.9, 77.55, 13.02, 77.7)));

        assertThat(Inbound.viewport(json("""
                {"city_id": "BLR", "bbox": {"min_lat": 12.9, "min_lon": 77.55, "max_lat": 13.02, "max_lon": 77.7}}""")))
                .isNull();
        assertThat(Inbound.viewport(json("""
                {"city_id": "blr", "bbox": {"min_lat": 13.1, "min_lon": 77.55, "max_lat": 13.02, "max_lon": 77.7}}""")))
                .isNull();
        assertThat(Inbound.viewport(json("""
                {"city_id": "blr", "bbox": {"min_lat": 12.9, "min_lon": 77.55, "max_lat": 13.02}}"""))).isNull();
        assertThat(Inbound.viewport(json("{\"city_id\": \"blr\", \"bbox\": [12.9, 77.55, 13.02, 77.7]}"))).isNull();
    }

    @Test
    void anOfferIdMustBeAUuid() {
        UUID offerId = UUID.randomUUID();

        assertThat(Inbound.uuid(json("{\"offer_id\": \"" + offerId + "\"}"), "offer_id")).isEqualTo(offerId);
        assertThat(Inbound.uuid(json("{\"offer_id\": \"not-a-uuid\"}"), "offer_id")).isNull();
        assertThat(Inbound.uuid(json("{\"offer_id\": 42}"), "offer_id")).isNull();
        assertThat(Inbound.uuid(json("{}"), "offer_id")).isNull();
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }
}
