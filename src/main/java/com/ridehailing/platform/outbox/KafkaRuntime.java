package com.ridehailing.platform.outbox;

import com.ridehailing.platform.EventConsumer;
import com.ridehailing.platform.workers.WorkerProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.context.SmartLifecycle;

class KafkaRuntime implements SmartLifecycle {

    private final KafkaTopology topology;
    private final EventConsumers consumers;
    private final Function<EventConsumer, KafkaEventConsumer> factory;
    private final Supplier<KafkaDeadLetterConsumer> mirrorFactory;
    private final boolean autostart;
    private final List<Runnable> closeActions = new ArrayList<>();
    private boolean running;

    KafkaRuntime(KafkaTopology topology, EventConsumers consumers, Function<EventConsumer, KafkaEventConsumer> factory,
            Supplier<KafkaDeadLetterConsumer> mirrorFactory, WorkerProperties workers) {
        this.topology = topology;
        this.consumers = consumers;
        this.factory = factory;
        this.mirrorFactory = mirrorFactory;
        this.autostart = workers.autostart();
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        try {
            topology.prepare();
            KafkaDeadLetterConsumer mirror = mirrorFactory.get();
            closeActions.add(mirror::close);
            mirror.start();
            for (EventConsumer consumer : consumers.all()) {
                KafkaEventConsumer listener = factory.apply(consumer);
                closeActions.add(listener::close);
                listener.start();
            }
            running = true;
        } catch (RuntimeException failure) {
            try {
                stop();
            } catch (RuntimeException closing) {
                failure.addSuppressed(closing);
            }
            throw failure;
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        RuntimeException failure = null;
        for (Runnable close : closeActions.reversed()) {
            try {
            close.run();
            } catch (RuntimeException closing) {
                if (failure == null) {
                    failure = closing;
                } else {
                    failure.addSuppressed(closing);
                }
            }
        }
        closeActions.clear();
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return autostart;
    }

    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE - 100;
    }
}