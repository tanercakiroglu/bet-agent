package com.betagent.persistence;

import io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.impl.ContextInternal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ReactiveContextRunner {

    private static final Logger LOG = Logger.getLogger(ReactiveContextRunner.class);

    @Inject Vertx vertx;

    public <T> Uni<T> onContext(Supplier<Uni<T>> supplier) {
        return Uni.createFrom().emitter(emitter -> {
            Context context = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
            VertxContextSafetyToggle.setContextSafe(context, true);
            context.runOnContext(ignored -> supplier.get()
                    .subscribe()
                    .with(emitter::complete, emitter::fail));
        });
    }

    public void subscribe(Supplier<Uni<?>> supplier) {
        onContext(() -> supplier.get().replaceWithVoid())
                .subscribe()
                .with(ignored -> {}, err -> LOG.error("Background reactive task failed", err));
    }

    public <T> T await(Supplier<Uni<T>> supplier) {
        return onContext(supplier).await().indefinitely();
    }
}
