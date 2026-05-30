package com.betagent.persistence;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import java.util.List;
import org.hibernate.reactive.mutiny.Mutiny;

public final class ReactiveQueries {

    private ReactiveQueries() {}

    public static Uni<Long> countNative(String sql) {
        return session().flatMap(s -> s.createNativeQuery(sql, Long.class).getSingleResult());
    }

    public static Uni<Long> countNative(String sql, Object... params) {
        return session().flatMap(s -> {
            Mutiny.SelectionQuery<Long> query = s.createNativeQuery(sql, Long.class);
            bind(query, params);
            return query.getSingleResult();
        });
    }

    public static Uni<List<Object[]>> rowsNative(String sql, Object... params) {
        return session().flatMap(s -> {
            @SuppressWarnings("unchecked")
            Mutiny.Query<Object[]> query = (Mutiny.Query<Object[]>) (Mutiny.Query<?>) s.createNativeQuery(sql);
            bind(query, params);
            return query.getResultList();
        });
    }

    public static Uni<Integer> executeNative(String sql, Object... params) {
        return session().flatMap(s -> {
            Mutiny.Query<?> query = s.createNativeQuery(sql);
            bind(query, params);
            return query.executeUpdate();
        });
    }

    private static Uni<Mutiny.Session> session() {
        return Panache.getSession();
    }

    private static void bind(Mutiny.Query<?> query, Object... params) {
        for (int i = 0; i < params.length; i++) {
            query.setParameter(i + 1, params[i]);
        }
    }

    private static void bind(Mutiny.SelectionQuery<?> query, Object... params) {
        for (int i = 0; i < params.length; i++) {
            query.setParameter(i + 1, params[i]);
        }
    }
}
