package org.enthusia.playtime.discord;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Provider-neutral port used by numeral-role orchestration.
 *
 * <p>The application layer owns active-playtime and tier policy. Implementations own linked-identity
 * lookup, link-change events and role transport. Account references are opaque outside the provider.
 * Missing remote members must be treated as an idempotent completed operation rather than leaked as
 * provider-specific exceptions. Synchronous provider-state failures are runtime exceptions; remote
 * role-operation failures complete the returned future exceptionally.
 */
public interface NumeralRoleProvider extends AutoCloseable {
    void start(LinkListener listener);

    /** Returns whether linked-identity enumeration can be attempted without provider startup backoff. */
    boolean linksAvailable();

    Set<UUID> linkedMinecraftAccounts();

    Optional<NumeralRoleAccountRef> accountFor(UUID uuid);

    Set<UUID> minecraftAccounts(NumeralRoleAccountRef account);

    CompletableFuture<Set<String>> currentRoles(NumeralRoleAccountRef account);

    CompletableFuture<Void> grant(NumeralRoleAccountRef account, String roleId);

    CompletableFuture<Void> revoke(NumeralRoleAccountRef account, String roleId);

    @Override
    void close();

    interface LinkListener {
        void linked(UUID uuid);

        void unlinked(NumeralRoleAccountRef account);
    }
}
