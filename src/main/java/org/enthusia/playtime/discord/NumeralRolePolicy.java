package org.enthusia.playtime.discord;

import org.enthusia.playtime.util.NumeralTierCatalog;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure role entitlement policy. All inputs are snapshots; Discord state is not mutated here. */
public final class NumeralRolePolicy {
    private final NumeralTierCatalog catalog;
    private final Map<String, String> roleIds;
    private final Set<String> managedIds;

    public NumeralRolePolicy(NumeralTierCatalog catalog, Map<String, String> roleIds) {
        this.catalog = Objects.requireNonNull(catalog);
        this.roleIds = Map.copyOf(Objects.requireNonNull(roleIds));
        if (catalog.tiers().isEmpty() || this.roleIds.size() != catalog.tiers().size()) {
            throw new IllegalArgumentException("Every numeral tier must have exactly one Discord role ID");
        }
        Set<String> unique = new HashSet<>();
        for (NumeralTierCatalog.Tier tier : catalog.tiers()) {
            String id = this.roleIds.get(tier.label());
            if (id == null || !id.matches("[0-9]{1,20}") || !unique.add(id)) {
                throw new IllegalArgumentException("Missing, invalid, or duplicate Discord role ID for tier " + tier.label());
            }
        }
        this.managedIds = Set.copyOf(unique);
    }

    public Set<String> desiredRoles(long activeMinutes) {
        if (activeMinutes < 0) throw new IllegalArgumentException("Active minutes must be known and nonnegative");
        LinkedHashSet<String> desired = new LinkedHashSet<>();
        for (NumeralTierCatalog.Tier tier : catalog.tiers()) {
            if (activeMinutes < tier.thresholdMinutes()) break;
            desired.clear();
            desired.add(roleIds.get(tier.label()));
        }
        return Set.copyOf(desired);
    }

    public Change reconcile(Set<String> currentRoleIds, long activeMinutes) {
        Set<String> desired = desiredRoles(activeMinutes);
        Set<String> grant = new HashSet<>(desired);
        grant.removeAll(currentRoleIds);
        Set<String> revoke = new HashSet<>(currentRoleIds);
        revoke.retainAll(managedIds);
        revoke.removeAll(desired);
        return new Change(Set.copyOf(grant), Set.copyOf(revoke));
    }

    public Change revokeAllManaged(Set<String> currentRoleIds) {
        Set<String> revoke = new HashSet<>(currentRoleIds);
        revoke.retainAll(managedIds);
        return new Change(Set.of(), Set.copyOf(revoke));
    }

    public Set<String> managedRoleIds() { return managedIds; }

    public record Change(Set<String> grant, Set<String> revoke) { }
}
