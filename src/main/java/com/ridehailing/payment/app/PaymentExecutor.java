package com.ridehailing.payment.app;

import com.ridehailing.payment.app.Outcomes.Source;
import com.ridehailing.payment.db.AttemptRepository;
import com.ridehailing.payment.db.ProviderCallRepository;
import com.ridehailing.payment.db.ProviderCallRepository.Call;
import com.ridehailing.payment.db.ProviderCallRepository.Claim;
import com.ridehailing.payment.db.RefundRepository;
import com.ridehailing.platform.Transactions;
import com.ridehailing.shared.Money;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Sends attempts and refunds and checks unknown ones (LLD §11.2, §11.3, §11.10): claim in one transaction, call the
 * provider outside any, record in another. A row is sent once; after that only status checks and webhooks touch it.
 */
@Component
class PaymentExecutor {

    private final ProviderCallRepository calls;
    private final AttemptRepository attempts;
    private final RefundRepository refunds;
    private final Outcomes outcomes;
    private final PaymentProvider provider;
    private final ProviderCircuit circuit;
    private final Transactions transactions;
    private final PaymentProperties properties;

    PaymentExecutor(ProviderCallRepository calls, AttemptRepository attempts, RefundRepository refunds,
            Outcomes outcomes, PaymentProvider provider, ProviderCircuit circuit, Transactions transactions,
            PaymentProperties properties) {
        this.calls = calls;
        this.attempts = attempts;
        this.refunds = refunds;
        this.outcomes = outcomes;
        this.provider = provider;
        this.circuit = circuit;
        this.transactions = transactions;
        this.properties = properties;
    }

    /** Sends the oldest pending attempt, else the oldest pending refund; false if none, or the circuit is open. */
    boolean sendNext() {
        if (!circuit.allowsCalls()) {
            return false;
        }
        Optional<Claim> attempt = transactions.execute(() -> calls.claimToSend(Call.ATTEMPT, properties.lease()));
        if (attempt.isPresent()) {
            UUID id = attempt.get().id();
            AttemptRepository.ToSend send = attempts.toSend(id).orElseThrow();
            ProviderAnswer answer = circuit.call(() -> provider.charge(id,
                    new Money(send.amountPaise(), send.currency()), send.methodRef()));
            outcomes.attempt(id, Source.SEND, answer);
            return true;
        }
        Optional<Claim> refund = transactions.execute(() -> calls.claimToSend(Call.REFUND, properties.lease()));
        if (refund.isPresent()) {
            UUID id = refund.get().id();
            RefundRepository.ToSend send = refunds.toSend(id).orElseThrow();
            ProviderAnswer answer = circuit.call(() -> provider.refund(id, send.paymentKey(),
                    new Money(send.amountPaise(), send.currency())));
            outcomes.refund(id, Source.SEND, answer);
            return true;
        }
        return false;
    }

    /** Checks one attempt, else one refund, whose lease expired or whose check is due; false if none. */
    boolean checkNext() {
        if (!circuit.allowsCalls()) {
            return false;
        }
        return check(Call.ATTEMPT) || check(Call.REFUND);
    }

    private boolean check(Call call) {
        Optional<Claim> claim = transactions.execute(() -> calls.claimExpired(call, properties.lease())
                .or(() -> calls.claimDue(call, properties.lease())));
        if (claim.isEmpty()) {
            return false;
        }
        UUID id = claim.get().id();
        ProviderAnswer answer = circuit.call(() -> provider.status(id));
        if (call == Call.ATTEMPT) {
            outcomes.attempt(id, Source.CHECK, answer);
        } else {
            outcomes.refund(id, Source.CHECK, answer);
        }
        return true;
    }
}
