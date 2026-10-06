package org.enthusia.playtime.discord;

import net.enthusia.discord.platform.api.DiscordPlatformAvailability;
import net.enthusia.discord.platform.api.ManagedRoleClaim;
import net.enthusia.discord.platform.api.ManagedRoleClient;
import net.enthusia.discord.platform.api.ManagedRoleKey;
import net.enthusia.discord.platform.api.ManagedRoleNamespace;
import net.enthusia.discord.platform.api.ManagedRoleReconcileStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Builds complete provider-neutral numeral-role snapshots before publishing any claim.
 *
 * A failed authoritative playtime read aborts the pass before partial desired state can escape.
 */
public final class EnthusiaNumeralShadowPublisher {
    private static final ManagedRoleNamespace NAMESPACE = new ManagedRoleNamespace("playtime-numerals");

    @FunctionalInterface
    public interface ActiveMinutes {
        long read(UUID playerId);
    }

    public record Summary(int claimsPublished, int playersEvaluated) {
        public Summary {
            if (claimsPublished < 0 || playersEvaluated < 0) {
                throw new IllegalArgumentException("numeral shadow summary counts must be nonnegative");
            }
        }
    }

    private final NumeralRolePolicy policy;

    public EnthusiaNumeralShadowPublisher(NumeralRolePolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public CompletableFuture<Summary> publish(
            ManagedRoleClient client,
            Map<String, String> roleIdsByTier,
            Set<UUID> knownPlayers,
            ActiveMinutes activeMinutes
    ) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(roleIdsByTier, "roleIdsByTier");
        Objects.requireNonNull(knownPlayers, "knownPlayers");
        Objects.requireNonNull(activeMinutes, "activeMinutes");
        if (!NAMESPACE.equals(client.namespace())
                || client.availability() == DiscordPlatformAvailability.UNAVAILABLE) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Enthusia numeral managed-role client is unavailable"));
        }

        Map<String, Set<UUID>> desired = buildDesired(roleIdsByTier, knownPlayers, activeMinutes);
        List<CompletableFuture<Void>> publications = new ArrayList<>();
        for (String tier : sortedTiers()) {
            ManagedRoleClaim claim = new ManagedRoleClaim(
                    key(tier),
                    displayName(tier),
                    Optional.of(roleIdsByTier.get(tier)),
                    desired.get(tier)
            );
            publications.add(client.reconcile(claim).toCompletableFuture().thenApply(result -> {
                ManagedRoleReconcileStatus status = result.status();
                if (status == ManagedRoleReconcileStatus.REJECTED
                        || status == ManagedRoleReconcileStatus.UNAVAILABLE) {
                    throw new IllegalStateException("Enthusia rejected numeral managed-role claim");
                }
                return null;
            }));
        }

        return CompletableFuture.allOf(publications.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> new Summary(publications.size(), knownPlayers.size()));
    }

    private Map<String, Set<UUID>> buildDesired(
            Map<String, String> roleIdsByTier,
            Set<UUID> knownPlayers,
            ActiveMinutes activeMinutes
    ) {
        List<String> tiers = sortedTiers();
        if (!roleIdsByTier.keySet().containsAll(tiers)) {
            throw new IllegalArgumentException("Every numeral tier requires a configured Discord role ID");
        }

        Map<String, Set<UUID>> mutable = new LinkedHashMap<>();
        tiers.forEach(tier -> mutable.put(tier, new LinkedHashSet<>()));
        knownPlayers.stream()
                .sorted(Comparator.comparing(UUID::toString))
                .forEach(playerId -> assignPlayer(mutable, playerId, activeMinutes.read(playerId)));

        Map<String, Set<UUID>> immutable = new LinkedHashMap<>();
        mutable.forEach((tier, players) -> immutable.put(tier, Set.copyOf(players)));
        return Map.copyOf(immutable);
    }

    private void assignPlayer(Map<String, Set<UUID>> desired, UUID playerId, long activeMinutes) {
        policy.desiredTierLabel(activeMinutes).ifPresent(tier -> {
            Set<UUID> players = desired.get(tier);
            if (players == null) {
                throw new IllegalStateException("Numeral tier policy returned an unmapped tier");
            }
            players.add(playerId);
        });
    }

    private List<String> sortedTiers() {
        return policy.roleIdsByTier().keySet().stream().sorted().toList();
    }

    private static String displayName(String tierLabel) {
        return "Playtime " + tierLabel;
    }

    static ManagedRoleNamespace namespace() {
        return NAMESPACE;
    }

    static ManagedRoleKey key(String tierLabel) {
        return new ManagedRoleKey(NAMESPACE, "tier:" + digest(tierLabel.toLowerCase(java.util.Locale.ROOT)));
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
