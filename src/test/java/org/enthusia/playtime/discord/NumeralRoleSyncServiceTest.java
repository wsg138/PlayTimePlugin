package org.enthusia.playtime.discord;

import org.enthusia.playtime.util.NumeralTierCatalog;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class NumeralRoleSyncServiceTest {
    private static final String TIER_ONE_ROLE = "101";
    private static final String STAFF_ROLE = "staff";
    private static final String DISCORD_ID = "discord-1";
    private final UUID player = UUID.randomUUID();
    private final NumeralRolePolicy policy = new NumeralRolePolicy(
            new NumeralTierCatalog(java.util.List.of(new NumeralTierCatalog.Tier("I", 60, "gray"),
                    new NumeralTierCatalog.Tier("II", 480, "white"))),
            Map.of("I", TIER_ONE_ROLE, "II", "102"));

    @Test void linkedPlayerGetsCurrentTierWithoutTouchingUnrelatedRoles() {
        FakeRoles roles = new FakeRoles(Set.of(TIER_ONE_ROLE, STAFF_ROLE));
        NumeralRoleSyncService service = new NumeralRoleSyncService(policy, uuid -> DISCORD_ID, uuid -> 480L, roles);
        service.reconcile(player).join();
        assertEquals(Set.of("102", STAFF_ROLE), roles.roles);
    }

    @Test void failedAuthoritativeReadDoesNotRemoveRoles() {
        FakeRoles roles = new FakeRoles(Set.of(TIER_ONE_ROLE));
        NumeralRoleSyncService service = new NumeralRoleSyncService(policy, uuid -> DISCORD_ID, uuid -> { throw new IllegalStateException("DB down"); }, roles);
        assertThrows(Exception.class, () -> service.reconcile(player).join());
        assertEquals(Set.of(TIER_ONE_ROLE), roles.roles);
    }

    @Test void unlinkRevokesCapturedDiscordIdentityAfterMappingIsGone() {
        FakeRoles roles = new FakeRoles(Set.of(TIER_ONE_ROLE, STAFF_ROLE));
        NumeralRoleSyncService service = new NumeralRoleSyncService(policy, uuid -> null, uuid -> 999L, roles);
        service.unlink(DISCORD_ID).join();
        assertEquals(Set.of(STAFF_ROLE), roles.roles);
    }

    @Test void unlinkWaitsForInflightReconciliationAndRemovesItsGrant() throws Exception {
        AtomicReference<String> link = new AtomicReference<>(DISCORD_ID);
        CountDownLatch readStarted = new CountDownLatch(1);
        CompletableFuture<Set<String>> heldRoles = new CompletableFuture<>();
        AtomicInteger reads = new AtomicInteger();
        FakeRoles roles = new FakeRoles(Set.of(TIER_ONE_ROLE, STAFF_ROLE)) {
            @Override public CompletableFuture<Set<String>> currentRoles(String discordId) {
                if (reads.getAndIncrement() == 0) {
                    readStarted.countDown();
                    return heldRoles;
                }
                return super.currentRoles(discordId);
            }
        };
        NumeralRoleSyncService service = new NumeralRoleSyncService(policy, uuid -> link.get(), uuid -> 480L, roles);
        CompletableFuture<Void> reconcile = service.reconcile(player);
        assertTrue(readStarted.await(5, TimeUnit.SECONDS));
        link.set(null);
        CompletableFuture<Void> unlink = service.unlink(DISCORD_ID);
        assertFalse(unlink.isDone());
        heldRoles.complete(Set.of(TIER_ONE_ROLE, STAFF_ROLE));
        CompletableFuture.allOf(reconcile, unlink).join();
        assertEquals(Set.of(STAFF_ROLE), roles.roles);
    }

    @Test void unlinkRemovesRoleEvenWhenItsTierStartsAtZeroMinutes() {
        NumeralRolePolicy zeroHour = new NumeralRolePolicy(new NumeralTierCatalog(
                java.util.List.of(new NumeralTierCatalog.Tier("I", 0, "gray"))), Map.of("I", TIER_ONE_ROLE));
        FakeRoles roles = new FakeRoles(Set.of(TIER_ONE_ROLE));
        new NumeralRoleSyncService(zeroHour, uuid -> null, uuid -> 0L, roles).unlink(DISCORD_ID).join();
        assertTrue(roles.roles.isEmpty());
    }

    private static class FakeRoles implements NumeralRoleSyncService.RoleGateway {
        final Set<String> roles;
        FakeRoles(Set<String> initial) { roles = new HashSet<>(initial); }
        public CompletableFuture<Set<String>> currentRoles(String discordId) { return CompletableFuture.completedFuture(Set.copyOf(roles)); }
        public CompletableFuture<Void> grant(String discordId, String roleId) { roles.add(roleId); return CompletableFuture.completedFuture(null); }
        public CompletableFuture<Void> revoke(String discordId, String roleId) { roles.remove(roleId); return CompletableFuture.completedFuture(null); }
    }
}
