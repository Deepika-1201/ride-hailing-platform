package com.ridehailing.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ridehailing.payment.db.ChargeRepository.ChargeRow;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * I7 (LLD §17.3, §11.10), each part shown to find a broken state. Where a constraint stops the state from existing,
 * the test shows the constraint refusing it, then drops the constraint in a transaction that is rolled back.
 */
class ChargesCheckTests extends PaymentTest {

    @Autowired
    private ChargesCheck check;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void aSecondChargeForOneRideAndPurposeIsRefusedAndWouldBeReported() {
        ChargeRow charge = paidCharge();
        assertThatThrownBy(() -> duplicate(charge)).isInstanceOf(DuplicateKeyException.class);

        List<String> found = rolledBack(() -> {
            jdbc.sql("ALTER TABLE payment.charges DROP CONSTRAINT charges_ride_id_purpose_key").update();
            duplicate(charge);
            return check.violations(city.id());
        });

        assertThat(found).containsExactly("ride " + charge.rideId() + " has 2 FARE charges");
        assertThat(check.violations(city.id())).isEmpty();
    }

    @Test
    void aReservationThatDiffersFromTheRefundsIsReported() {
        ChargeRow charge = paidCharge();

        List<String> found = rolledBack(() -> {
            jdbc.sql("UPDATE payment.charges SET refunded_paise = 500 WHERE id = :id").param("id", charge.id())
                    .update();
            return check.violations(city.id());
        });

        assertThat(found).containsExactly("charge " + charge.id() + " reserves 500 paise but its refunds total 0");
    }

    @Test
    void refundsBeyondTheChargeAreReported() {
        ChargeRow charge = paidCharge();

        List<String> found = rolledBack(() -> {
            refund(charge, charge.succeededAttemptId(), 6_000, false, "SUCCEEDED");
            refund(charge, charge.succeededAttemptId(), 6_000, false, "PENDING");
            refund(charge, charge.succeededAttemptId(), 9_000, false, "FAILED");
            jdbc.sql("UPDATE payment.charges SET refunded_paise = 10000 WHERE id = :id").param("id", charge.id())
                    .update();
            return check.violations(city.id());
        });

        assertThat(found).containsExactlyInAnyOrder(
                "charge " + charge.id() + " reserves 10000 paise but its refunds total 12000",
                "attempt " + charge.succeededAttemptId() + " has 12000 paise refunded of a 10000 paise charge");
    }

    @Test
    void aDoubleChargeIsReportedUnlessAnAutomaticRefundUndidIt() {
        ChargeRow charge = paidCharge();
        String twice = "charge " + charge.id() + " was paid 2 times with 0 automatic refunds";

        List<List<String>> found = rolledBack(() -> {
            UUID second = UUID.randomUUID();
            jdbc.sql("""
                            INSERT INTO payment.charge_attempts (id, charge_id, seq, status, provider,
                                payment_method_id, method_ref, created_at)
                            VALUES (:id, :chargeId, 2, 'SUCCEEDED', 'mock', :methodId, 'tok_ok', now())
                            """)
                    .param("id", second).param("chargeId", charge.id()).param("methodId", UUID.randomUUID()).update();
            List<String> paidTwice = check.violations(city.id());
            UUID refund = refund(charge, second, charge.amountPaise(), true, "PENDING");
            List<String> undone = check.violations(city.id());
            jdbc.sql("UPDATE payment.refunds SET status = 'FAILED', failure_code = 'DECLINED' WHERE id = :id")
                    .param("id", refund).update();
            return List.of(paidTwice, undone, check.violations(city.id()));
        });

        assertThat(found.get(0)).containsExactly(twice);
        assertThat(found.get(1)).isEmpty();
        assertThat(found.get(2)).containsExactly(twice);
    }

    private void duplicate(ChargeRow charge) {
        jdbc.sql("""
                        INSERT INTO payment.charges (id, ride_id, rider_id, city_id, purpose, amount_paise,
                            commission_paise, currency, method_type, status, created_at, updated_at)
                        VALUES (:id, :rideId, :riderId, :cityId, 'FARE', 100, 0, 'INR', 'CASH', 'SUCCEEDED',
                            now(), now())
                        """)
                .param("id", UUID.randomUUID()).param("rideId", charge.rideId()).param("riderId", charge.riderId())
                .param("cityId", city.id()).update();
    }

    private UUID refund(ChargeRow charge, UUID attemptId, long amountPaise, boolean automatic, String status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO payment.refunds (id, charge_id, attempt_id, amount_paise, reason, automatic,
                            status, failure_code, requested_by, created_at)
                        VALUES (:id, :chargeId, :attemptId, :amountPaise, 'test', :automatic, :status,
                            CASE WHEN :status = 'FAILED' THEN 'DECLINED' END, 'test', now())
                        """)
                .param("id", id).param("chargeId", charge.id()).param("attemptId", attemptId)
                .param("amountPaise", amountPaise).param("automatic", automatic).param("status", status).update();
        return id;
    }

    private <T> T rolledBack(Supplier<T> work) {
        return transactionTemplate.execute(status -> {
            status.setRollbackOnly();
            return work.get();
        });
    }
}
