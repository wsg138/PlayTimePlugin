package org.enthusia.playtime.discord;

import net.enthusia.discord.platform.api.DiscordPlatformAvailability;
import net.enthusia.discord.platform.api.ManagedRoleClaim;
import net.enthusia.discord.platform.api.ManagedRoleClient;
import net.enthusia.discord.platform.api.ManagedRoleDeleteResult;
import net.enthusia.discord.platform.api.ManagedRoleKey;
import net.enthusia.discord.platform.api.ManagedRoleNamespace;
import net.enthusia.discord.platform.api.ManagedRoleReconcileResult;
import net.enthusia.discord.platform.api.ManagedRoleReconcileStatus;
import org.enthusia.playtime.util.NumeralTierCatalog;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnthusiaNumeralShadowPublisherTest {
    @Test
    void publishesEveryTierWithCompleteAuthoritativeMembership() {
        NumeralRolePolicy policy = policy();
        EnthusiaNumeralShadowPublisher publisher = new EnthusiaNumeralShadowPublisher(policy);
        CapturingClient client = new CapturingClient();
        UUID below = UUID.randomUUID();
        UUID tierOne = UUID.randomUUID();
        UUID tierTwo = UUID.randomUUID();

        EnthusiaNumeralShadowPublisher.Summary summary = publisher.publish(
                client,
                Map.of("I", "Playtime I", "II", "Playtime II"),
                Set.of(below, tierOne, tierTwo),
                playerId -> {
                    if (playerId.equals(below)) return 30L;
                    if (playerId.equals(tierOne)) return 60L;
                    return 150L;
                }
        ).join();

        assertEquals(2, summary.claimsPublished());
        assertEquals(3, summary.playersEvaluated());
        assertEquals(2, client.claims.size());

        ManagedRoleClaim first = client.claimByName("Playtime I");
        ManagedRoleClaim second = client.claimByName("Playtime II");
        assertEquals("playtime-numerals", first.key().namespace().value());
        assertEquals(Set.of(tierOne), first.desiredMinecraftAccounts());
        assertEquals(Set.of(tierTwo), second.desiredMinecraftAccounts());
    }

    @Test
    void emptyTierIsStillPublishedSoStaleHoldersCanBeDetected() {
        EnthusiaNumeralShadowPublisher publisher = new EnthusiaNumeralShadowPublisher(policy());
        CapturingClient client = new CapturingClient();
        UUID tierOne = UUID.randomUUID();

        publisher.publish(
                client,
                Map.of("I", "Playtime I", "II", "Playtime II"),
                Set.of(tierOne),
                ignored -> 60L
        ).join();

        assertEquals(Set.of(), client.claimByName("Playtime II").desiredMinecraftAccounts());
    }

    @Test
    void failedAuthoritativeReadPublishesNothing() {
        EnthusiaNumeralShadowPublisher publisher = new EnthusiaNumeralShadowPublisher(policy());
        CapturingClient client = new CapturingClient();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertThrows(RuntimeException.class, () -> publisher.publish(
                client,
                Map.of("I", "Playtime I", "II", "Playtime II"),
                Set.of(first, second),
                playerId -> {
                    if (playerId.equals(second)) throw new IllegalStateException("storage unavailable");
                    return 60L;
                }
        ));
        assertEquals(List.of(), client.claims);
    }

    private static NumeralRolePolicy policy() {
        return new NumeralRolePolicy(
                new NumeralTierCatalog(List.of(
                        new NumeralTierCatalog.Tier("I", 60L, "gray"),
                        new NumeralTierCatalog.Tier("II", 120L, "white")
                )),
                Map.of("I", "101", "II", "102")
        );
    }

    private static final class CapturingClient implements ManagedRoleClient {
        private final List<ManagedRoleClaim> claims = new ArrayList<>();

        @Override
        public ManagedRoleNamespace namespace() {
            return new ManagedRoleNamespace("playtime-numerals");
        }

        @Override
        public DiscordPlatformAvailability availability() {
            return DiscordPlatformAvailability.AVAILABLE;
        }

        @Override
        public CompletionStage<ManagedRoleReconcileResult> reconcile(ManagedRoleClaim claim) {
            claims.add(claim);
            return CompletableFuture.completedFuture(new ManagedRoleReconcileResult(
                    ManagedRoleReconcileStatus.RETRY_SCHEDULED,
                    claim.desiredMinecraftAccounts().size(),
                    0,
                    0,
                    0
            ));
        }

        @Override
        public CompletionStage<ManagedRoleDeleteResult> delete(ManagedRoleKey key) {
            return CompletableFuture.completedFuture(ManagedRoleDeleteResult.REJECTED);
        }

        ManagedRoleClaim claimByName(String displayName) {
            return claims.stream()
                    .filter(claim -> displayName.equals(claim.displayName()))
                    .findFirst()
                    .orElseThrow();
        }
    }
}
