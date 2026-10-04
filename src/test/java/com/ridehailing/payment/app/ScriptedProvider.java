package com.ridehailing.payment.app;

import com.ridehailing.shared.Money;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The mock with hooks a test may set: something to run while a call is at the provider (after it decided, before the
 * answer returns), a crash of the process after the provider acted, a provider that doesn't answer, and refund answers.
 */
class ScriptedProvider implements PaymentProvider {

    final MockPaymentProvider mock;
    volatile Consumer<UUID> duringCall;
    volatile boolean crashBeforeCall;
    volatile boolean crashAfterCall;
    volatile boolean down;
    volatile ProviderAnswer refundAnswer;

    ScriptedProvider(MockPaymentProvider mock) {
        this.mock = mock;
    }

    @Override
    public String name() {
        return mock.name();
    }

    @Override
    public ProviderAnswer charge(UUID key, Money amount, String methodRef) {
        failIfDown();
        return after(key, () -> mock.charge(key, amount, methodRef));
    }

    @Override
    public ProviderAnswer refund(UUID key, UUID paymentKey, Money amount) {
        failIfDown();
        ProviderAnswer answer = after(key, () -> mock.refund(key, paymentKey, amount));
        return refundAnswer == null ? answer : refundAnswer;
    }

    @Override
    public ProviderAnswer status(UUID key) {
        failIfDown();
        return after(key, () -> mock.status(key));
    }

    @Override
    public Optional<Instant> verify(String signature, byte[] body) {
        return mock.verify(signature, body);
    }

    /** The provider has acted, answered or not; the hook and the crash come before the caller learns anything. */
    private ProviderAnswer after(UUID key, Supplier<ProviderAnswer> call) {
        ProviderAnswer answer;
        try {
            answer = call.get();
        } catch (ProviderException e) {
            runHookAndCrash(key);
            throw e;
        }
        runHookAndCrash(key);
        return answer;
    }

    private void runHookAndCrash(UUID key) {
        Consumer<UUID> hook = duringCall;
        if (hook != null) {
            hook.accept(key);
        }
        if (crashAfterCall) {
            throw new SimulatedCrash();
        }
    }

    private void failIfDown() {
        if (crashBeforeCall) {
            throw new SimulatedCrash();
        }
        if (down) {
            throw new ProviderException("Scripted outage");
        }
    }

    /** The process dying with a call in flight: an Error, which nothing on the way catches. */
    static final class SimulatedCrash extends Error {

        SimulatedCrash() {
            super("Simulated crash during a provider call");
        }
    }
}
