package org.enthusia.playtime.discord;

import org.enthusia.playtime.util.NumeralTierCatalog;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NumeralRoleSyncServiceTest {
    private static final String TIER_ONE_ROLE = "101";
    private static final String TIER_TWO_ROLE = "102";
    private static final String STAFF_ROLE = "staff";
    private static final NumeralRoleAccountRef ACCOUNT = new NumeralRoleAccountRef("account-1");

    private final UUID player = UUID.randomUUID();
    private final NumeralRolePolicy policy = new NumeralRolePolicy(
            new NumeralTierCatalog(java.util.List.of(
                    new NumeralTierCatalog.Tier("I", 60, "gray"),
                    new NumeralTierCatalog.Tier("II", 480, "white"))),
            Map.of("I", TIER_ONE_ROLE, "II", TIER_TWO_ROLE));

    @Test
    void linkedPlayerGetsCurrentTierWithoutTouchingUnrelatedRoles() {
        FakeProvider provider = new FakeProvider(Set.of(TIER_ONE_ROLE, STAFF_ROLE));
        provider.link(player, ACCOUNT);
        NumeralRoleSyncService service = service(provider, uuid -> 480L);

        service.reconcile(player).join();

        assertEquals(Set.of(TIER_TWO_ROLE, STAFF_ROLE), provider.roles);
    }

    @Test
    void failedAuthoritativeReadDoesNotRemoveRoles() {
        FakeProvider provider = new FakeProvider(Set.of(TIER_ONE_ROLE));
        provider.link(player, ACCOUNT);
        NumeralRoleSyncService service = service(provider, uuid -> {
            throw new IllegalStateException("DB down");
        });

        assertThrows(CompletionException.class, () -> service.reconcile(player).join());
        assertEquals(Set.of(TIER_ONE_ROLE), provider.roles);
    }

    @Test
    void pendingAuthoritativeSnapshotUsesTypedRetrySignalWithoutRoleMutation() {
        FakeProvider provider = new FakeProvider(Set.of(TIER_ONE_ROLE, STAFF_ROLE));
        provider.link(player, ACCOUNT);
        NumeralRoleSyncService service = service(provider, uuid -> -1L);

        CompletionException failure = assertThrows(
                CompletionException.class, () -> service.reconcile(player).join());

        assertInstanceOf(NumeralRoleSyncService.SnapshotPendingException.class, failure.getCause());
        assertEquals(Set.of(TIER_ONE_ROLE, STAFF_ROLE), provider.roles);
        assertEquals(0, provider.grants.get());
        assertEquals(0, provider.revokes.get());
    }

    @Test
    void unavailableProviderDoesNotMutateRoles() {
        FakeProvider provider = new FakeProvider(Set.of(TIER_ONE_ROLE)) {
            @Override
            public Optional<NumeralRoleAccountRef> accountFor(UUID uuid) {
                throw new IllegalStateException("provider down");
            }
        };

        assertThrows(CompletionException.class, () -> service(provider, uuid -> 480L).reconcile(player).join());
        assertEquals(Set.of(TIER_ONE_ROLE), provider.roles);
    }

    @Test
    void unlinkRevokesCapturedIdentityAfterMappingIsGone() {
        FakeProvider provider = new FakeProvider(Set.of(TIER_ONE_ROLE, STAFF_ROLE));
        service(provider, uuid -> 999L).unlink(ACCOUNT).join();
        assertEquals(Set.of(STAFF_ROLE), provider.roles);
    }

    @Test
    void unlinkWaitsForInflightReconciliationAndRemovesItsGrant() throws Exception {
        CountDownLatch readStarted = new CountDownLatch(1);
        CompletableFuture<Set<String>> heldRoles = new CompletableFuture<>();
        AtomicInteger reads = new AtomicInteger();
        FakeProvider provider = new FakeProvider(Set.of(TIER_ONE_ROLE, STAFF_ROLE)) {
            @Override
            public CompletableFuture<Set<String>> currentRoles(NumeralRoleAccountRef account) {
                if (reads.getAndIncrement() == 0) {
                    readStarted.countDown();
                    return heldRoles;
                }
                return super.currentRoles(account);
            }
        };
        provider.link(player, ACCOUNT);
        NumeralRoleSyncService service = service(provider, uuid -> 480L);

        CompletableFuture<Void> reconcile = service.reconcile(player);
        assertTrue(readStarted.await(5, TimeUnit.SECONDS));
        provider.unlink(player);
        CompletableFuture<Void> unlink = service.unlink(ACCOUNT);
        assertFalse(unlink.isDone());
        heldRoles.complete(Set.of(TIER_ONE_ROLE, STAFF_ROLE));

        CompletableFuture.allOf(reconcile, unlink).join();
        assertEquals(Set.of(STAFF_ROLE), provider.roles);
    }

    @Test
    void unlinkRemovesRoleEvenWhenItsTierStartsAtZeroMinutes() {
        NumeralRolePolicy zeroHour = new NumeralRolePolicy(
                new NumeralTierCatalog(java.util.List.of(
                        new NumeralTierCatalog.Tier("I", 0, "gray"))),
                Map.of("I", TIER_ONE_ROLE));
        FakeProvider provider = new FakeProvider(Set.of(TIER_ONE_ROLE));

        new NumeralRoleSyncService(zeroHour, uuid -> 0L, provider).unlink(ACCOUNT).join();

        assertTrue(provider.roles.isEmpty());
    }

    @Test
    void multipleMinecraftAccountsUseHighestEffectiveTier() {
        UUID second = UUID.randomUUID();
        FakeProvider provider = new FakeProvider(Set.of(TIER_ONE_ROLE, STAFF_ROLE));
        provider.link(player, ACCOUNT);
        provider.link(second, ACCOUNT);
        NumeralRoleSyncService service = service(provider,
                uuid -> uuid.equals(second) ? 480L : 60L);

        service.reconcile(player).join();

        assertEquals(Set.of(TIER_TWO_ROLE, STAFF_ROLE), provider.roles);
    }

    @Test
    void unlinkKeepsTierEstablishedByAnotherMinecraftAccount() {
        UUID second = UUID.randomUUID();
        FakeProvider provider = new FakeProvider(Set.of(TIER_TWO_ROLE, STAFF_ROLE));
        provider.link(player, ACCOUNT);
        provider.link(second, ACCOUNT);
        NumeralRoleSyncService service = service(provider,
                uuid -> uuid.equals(second) ? 480L : 60L);

        provider.unlink(player);
        service.unlink(ACCOUNT).join();

        assertEquals(Set.of(TIER_TWO_ROLE, STAFF_ROLE), provider.roles);
    }

    @Test
    void staleLinkSnapshotFailsBeforeRoleMutation() {
        AtomicInteger membershipReads = new AtomicInteger();
        FakeProvider provider = new FakeProvider(Set.of(TIER_ONE_ROLE)) {
            @Override
            public Set<UUID> minecraftAccounts(NumeralRoleAccountRef account) {
                if (membershipReads.getAndIncrement() == 0) return Set.of(player);
                return Set.of();
            }
        };
        provider.link(player, ACCOUNT);

        CompletionException failure = assertThrows(
                CompletionException.class, () -> service(provider, uuid -> 480L).reconcile(player).join());
        assertInstanceOf(NumeralRoleSyncService.SnapshotPendingException.class, failure.getCause());
        assertEquals(Set.of(TIER_ONE_ROLE), provider.roles);
        assertEquals(0, provider.grants.get());
        assertEquals(0, provider.revokes.get());
    }

    @Test
    void duplicateReconciliationIsIdempotent() {
        FakeProvider provider = new FakeProvider(Set.of(TIER_ONE_ROLE, STAFF_ROLE));
        provider.link(player, ACCOUNT);
        NumeralRoleSyncService service = service(provider, uuid -> 480L);

        service.reconcile(player).join();
        service.reconcile(player).join();

        assertEquals(Set.of(TIER_TWO_ROLE, STAFF_ROLE), provider.roles);
        assertEquals(1, provider.grants.get());
        assertEquals(1, provider.revokes.get());
    }

    private NumeralRoleSyncService service(
            FakeProvider provider, NumeralRoleSyncService.ActiveMinutes activeMinutes) {
        return new NumeralRoleSyncService(policy, activeMinutes, provider);
    }

    private static class FakeProvider implements NumeralRoleProvider {
        private final Map<UUID, NumeralRoleAccountRef> links = new ConcurrentHashMap<>();
        final Set<String> roles = ConcurrentHashMap.newKeySet();
        final AtomicInteger grants = new AtomicInteger();
        final AtomicInteger revokes = new AtomicInteger();

        FakeProvider(Set<String> initial) {
            roles.addAll(initial);
        }

        void link(UUID uuid, NumeralRoleAccountRef account) {
            links.put(uuid, account);
        }

        void unlink(UUID uuid) {
            links.remove(uuid);
        }

        @Override
        public void start(LinkListener listener) { }

        @Override
        public boolean linksAvailable() {
            return true;
        }

        @Override
        public Set<UUID> linkedMinecraftAccounts() {
            return Set.copyOf(links.keySet());
        }

        @Override
        public Optional<NumeralRoleAccountRef> accountFor(UUID uuid) {
            return Optional.ofNullable(links.get(uuid));
        }

        @Override
        public Set<UUID> minecraftAccounts(NumeralRoleAccountRef account) {
            return links.entrySet().stream()
                    .filter(entry -> account.equals(entry.getValue()))
                    .map(Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        @Override
        public CompletableFuture<Set<String>> currentRoles(NumeralRoleAccountRef account) {
            return CompletableFuture.completedFuture(Set.copyOf(roles));
        }

        @Override
        public CompletableFuture<Void> grant(NumeralRoleAccountRef account, String roleId) {
            grants.incrementAndGet();
            roles.add(roleId);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> revoke(NumeralRoleAccountRef account, String roleId) {
            revokes.incrementAndGet();
            roles.remove(roleId);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() { }
    }
}
