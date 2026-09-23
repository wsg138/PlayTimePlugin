package org.enthusia.playtime.discord;

import org.enthusia.playtime.util.NumeralTierCatalog;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NumeralRolePolicyTest {
    private static final String TIER_ONE_ROLE = "101";
    private final NumeralTierCatalog catalog = new NumeralTierCatalog(
            java.util.List.of(new NumeralTierCatalog.Tier("I", 60, "gray"),
                    new NumeralTierCatalog.Tier("II", 480, "white"),
                    new NumeralTierCatalog.Tier("III", 1200, "green")));
    private final Map<String, String> roleIds = Map.of("I", TIER_ONE_ROLE, "II", "102", "III", "103");

    @Test void highestOnlyUsesAuthoritativeActiveMinuteThreshold() {
        NumeralRolePolicy policy = new NumeralRolePolicy(catalog, roleIds);
        assertEquals(Set.of(), policy.desiredRoles(59));
        assertEquals(Set.of(TIER_ONE_ROLE), policy.desiredRoles(60));
        assertEquals(Set.of("102"), policy.desiredRoles(480));
        assertEquals(Set.of("103"), policy.desiredRoles(1200));
    }

    @Test void reconciliationChangesOnlyManagedRoles() {
        NumeralRolePolicy policy = new NumeralRolePolicy(catalog, roleIds);
        NumeralRolePolicy.Change change = policy.reconcile(Set.of(TIER_ONE_ROLE, "unrelated"), 480);
        assertEquals(Set.of("102"), change.grant());
        assertEquals(Set.of(TIER_ONE_ROLE), change.revoke());
        assertFalse(change.revoke().contains("unrelated"));
    }

    @Test void incompleteOrDuplicateRoleMappingIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new NumeralRolePolicy(catalog, Map.of("I", TIER_ONE_ROLE)));
        assertThrows(IllegalArgumentException.class, () -> new NumeralRolePolicy(catalog,
                Map.of("I", TIER_ONE_ROLE, "II", TIER_ONE_ROLE, "III", "103")));
    }

    @Test void unlinkRevokesEvenAZeroHourTier() {
        NumeralRolePolicy zeroHour = new NumeralRolePolicy(new NumeralTierCatalog(
                java.util.List.of(new NumeralTierCatalog.Tier("I", 0, "gray"))), Map.of("I", TIER_ONE_ROLE));
        assertEquals(Set.of(TIER_ONE_ROLE), zeroHour.desiredRoles(0));
        assertEquals(Set.of(TIER_ONE_ROLE), zeroHour.revokeAllManaged(Set.of(TIER_ONE_ROLE, "staff")).revoke());
        assertTrue(zeroHour.revokeAllManaged(Set.of(TIER_ONE_ROLE, "staff")).grant().isEmpty());
    }
}
