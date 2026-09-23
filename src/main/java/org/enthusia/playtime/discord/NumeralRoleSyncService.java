package org.enthusia.playtime.discord;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Reconciles a Discord member from an authoritative playtime snapshot. */
public final class NumeralRoleSyncService {
    @FunctionalInterface public interface LinkProvider { String discordId(UUID uuid) throws Exception; }
    @FunctionalInterface public interface ActiveMinutes { long read(UUID uuid) throws Exception; }
    public interface RoleGateway {
        CompletableFuture<Set<String>> currentRoles(String discordId);
        CompletableFuture<Void> grant(String discordId, String roleId);
        CompletableFuture<Void> revoke(String discordId, String roleId);
    }

    private final NumeralRolePolicy policy;
    private final LinkProvider links;
    private final ActiveMinutes playtime;
    private final RoleGateway roles;
    private final Object queueLock = new Object();
    private final Map<String, CompletableFuture<Void>> memberWork = new ConcurrentHashMap<>();

    public NumeralRoleSyncService(NumeralRolePolicy policy, LinkProvider links,
                                  ActiveMinutes playtime, RoleGateway roles) {
        this.policy = Objects.requireNonNull(policy);
        this.links = Objects.requireNonNull(links);
        this.playtime = Objects.requireNonNull(playtime);
        this.roles = Objects.requireNonNull(roles);
    }

    public CompletableFuture<Void> reconcile(UUID uuid) {
        try {
            String discordId = links.discordId(uuid);
            if (discordId == null) return CompletableFuture.completedFuture(null);
            return serialize(discordId, () -> reconcileLinked(uuid, discordId));
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private CompletableFuture<Void> reconcileLinked(UUID uuid, String discordId) {
        try {
            if (!discordId.equals(links.discordId(uuid))) return CompletableFuture.completedFuture(null);
            long active = playtime.read(uuid);
            if (active < 0) throw new IllegalStateException("Authoritative active playtime is unavailable");
            return roles.currentRoles(discordId).thenCompose(current -> apply(discordId, policy.reconcile(
                    Objects.requireNonNull(current, "Discord member roles unavailable"), active)));
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    /** The captured Discord ID survives removal of the UUID-to-Discord link. */
    public CompletableFuture<Void> unlink(String discordId) {
        if (discordId == null || discordId.isBlank()) return CompletableFuture.completedFuture(null);
        return serialize(discordId, () -> roles.currentRoles(discordId).thenCompose(current -> apply(discordId,
                policy.revokeAllManaged(Objects.requireNonNull(current, "Discord member roles unavailable")))));
    }

    private CompletableFuture<Void> apply(String discordId, NumeralRolePolicy.Change change) {
        List<CompletableFuture<Void>> work = new ArrayList<>();
        for (String roleId : change.revoke()) {
            work.add(roles.revoke(discordId, roleId));
        }
        for (String roleId : change.grant()) {
            work.add(roles.grant(discordId, roleId));
        }
        return CompletableFuture.allOf(work.toArray(CompletableFuture[]::new));
    }

    private CompletableFuture<Void> serialize(String discordId, Supplier<CompletableFuture<Void>> operation) {
        synchronized (queueLock) {
            CompletableFuture<Void> prior = memberWork.getOrDefault(discordId, CompletableFuture.completedFuture(null));
            CompletableFuture<Void> current = prior.handle((ignored, failure) -> null).thenComposeAsync(ignored -> {
                try { return operation.get(); }
                catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
            });
            memberWork.put(discordId, current);
            current.whenComplete((ignored, failure) -> {
                synchronized (queueLock) { memberWork.remove(discordId, current); }
            });
            return current;
        }
    }
}
