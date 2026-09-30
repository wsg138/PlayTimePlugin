package org.enthusia.playtime.discord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Reconciles one provider identity from authoritative active-playtime snapshots. */
public final class NumeralRoleSyncService {
    @FunctionalInterface
    public interface ActiveMinutes {
        long read(UUID uuid);
    }

    private final NumeralRolePolicy policy;
    private final ActiveMinutes playtime;
    private final NumeralRoleProvider provider;
    private final Object queueLock = new Object();
    private final Map<NumeralRoleAccountRef, CompletableFuture<Void>> accountWork = new ConcurrentHashMap<>();

    public NumeralRoleSyncService(
            NumeralRolePolicy policy, ActiveMinutes playtime, NumeralRoleProvider provider) {
        this.policy = Objects.requireNonNull(policy);
        this.playtime = Objects.requireNonNull(playtime);
        this.provider = Objects.requireNonNull(provider);
    }

    public CompletableFuture<Void> reconcile(UUID uuid) {
        return CompletableFuture.completedFuture(null).thenCompose(ignored -> {
            Optional<NumeralRoleAccountRef> account = provider.accountFor(uuid);
            if (account.isEmpty()) return CompletableFuture.completedFuture(null);
            NumeralRoleAccountRef resolvedAccount = account.get();
            return serialize(resolvedAccount, () -> reconcileAccount(resolvedAccount));
        });
    }

    /**
     * Reconciles the captured identity after unlink. Remaining Minecraft links keep their effective
     * highest numeral; only an identity with no remaining links loses all managed numeral roles.
     */
    public CompletableFuture<Void> unlink(NumeralRoleAccountRef account) {
        if (account == null) return CompletableFuture.completedFuture(null);
        return serialize(account, () -> reconcileAccount(account));
    }

    private CompletableFuture<Void> reconcileAccount(NumeralRoleAccountRef account) {
        Set<UUID> linkedAccounts = linkedAccounts(account);
        long effectiveActiveMinutes = linkedAccounts.isEmpty() ? 0L : effectiveActiveMinutes(linkedAccounts);

        return provider.currentRoles(account).thenCompose(current -> {
            ensureMembershipUnchanged(account, linkedAccounts);
            Set<String> currentRoles = Objects.requireNonNull(current, "Provider roles unavailable");
            NumeralRolePolicy.Change change = linkedAccounts.isEmpty()
                    ? policy.revokeAllManaged(currentRoles)
                    : policy.reconcile(currentRoles, effectiveActiveMinutes);
            return apply(account, change);
        });
    }

    private void ensureMembershipUnchanged(NumeralRoleAccountRef account, Set<UUID> expected) {
        if (!linkedAccounts(account).equals(expected)) {
            throw new IllegalStateException("Linked account membership changed during numeral reconciliation");
        }
    }

    private Set<UUID> linkedAccounts(NumeralRoleAccountRef account) {
        return Set.copyOf(Objects.requireNonNull(
                provider.minecraftAccounts(account), "Provider linked accounts unavailable"));
    }

    private long effectiveActiveMinutes(Set<UUID> linkedAccounts) {
        long effective = 0L;
        for (UUID uuid : linkedAccounts) {
            long active = playtime.read(uuid);
            if (active < 0) {
                throw new IllegalStateException("Authoritative active playtime is unavailable");
            }
            effective = Math.max(effective, active);
        }
        return effective;
    }

    private CompletableFuture<Void> apply(NumeralRoleAccountRef account, NumeralRolePolicy.Change change) {
        List<CompletableFuture<Void>> work = new ArrayList<>();
        for (String roleId : change.revoke()) {
            work.add(provider.revoke(account, roleId));
        }
        for (String roleId : change.grant()) {
            work.add(provider.grant(account, roleId));
        }
        return CompletableFuture.allOf(work.toArray(CompletableFuture[]::new));
    }

    private CompletableFuture<Void> serialize(
            NumeralRoleAccountRef account, Supplier<CompletableFuture<Void>> operation) {
        synchronized (queueLock) {
            CompletableFuture<Void> prior = accountWork.getOrDefault(
                    account, CompletableFuture.completedFuture(null));
            CompletableFuture<Void> current = prior.handle((ignored, failure) -> null)
                    .thenComposeAsync(ignored -> operation.get());
            accountWork.put(account, current);
            current.whenComplete((ignored, failure) -> {
                synchronized (queueLock) {
                    accountWork.remove(account, current);
                }
            });
            return current;
        }
    }
}
