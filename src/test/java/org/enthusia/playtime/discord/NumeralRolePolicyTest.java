package org.enthusia.playtime.discord;

import org.enthusia.playtime.util.NumeralTierCatalog;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NumeralRolePolicyTest {
    private static final String TIER_ONE_ROLE = "101";
    private static final String TIER_TWO_ROLE = "102";
    private static final String TIER_THREE_ROLE = "103";
    private static final String TIER_THREE_LABEL = "III";
    private final NumeralTierCatalog catalog = new NumeralTierCatalog(
            java.util.List.of(new NumeralTierCatalog.Tier("I", 60, "gray"),
                    new NumeralTierCatalog.Tier("II", 480, "white"),
                    new NumeralTierCatalog.Tier(TIER_THREE_LABEL, 1200, "green")));
    private final Map<String, String> roleIds = Map.of("I", TIER_ONE_ROLE, "II", TIER_TWO_ROLE, TIER_THREE_LABEL, TIER_THREE_ROLE);

    @Test void highestOnlyUsesAuthoritativeActiveMinuteThreshold() {
        NumeralRolePolicy policy = new NumeralRolePolicy(catalog, roleIds);
        assertEquals(Set.of(), policy.desiredRoles(59));
        assertEquals(Set.of(TIER_ONE_ROLE), policy.desiredRoles(60));
        assertEquals(Set.of(TIER_TWO_ROLE), policy.desiredRoles(480));
        assertEquals(Set.of("103"), policy.desiredRoles(1200));
    }

    @Test void exposesStableTierMappingForCompleteShadowSnapshots() {
        NumeralRolePolicy policy = new NumeralRolePolicy(catalog, roleIds);
        assertEquals(roleIds, policy.roleIdsByTier());
        assertEquals(java.util.Optional.empty(), policy.desiredTierLabel(59));
        assertEquals(java.util.Optional.of("II"), policy.desiredTierLabel(480));
    }

    @Test void reconciliationChangesOnlyManagedRoles() {
        NumeralRolePolicy policy = new NumeralRolePolicy(catalog, roleIds);
        NumeralRolePolicy.Change change = policy.reconcile(Set.of(TIER_ONE_ROLE, "unrelated"), 480);
        assertEquals(Set.of("102"), change.grant());
        assertEquals(Set.of(TIER_ONE_ROLE), change.revoke());
        assertFalse(change.revoke().contains("unrelated"));
    }

    @Test void tierFourReplacesTierOneAtTheConfiguredActiveTimeThreshold() {
        NumeralRolePolicy policy = new NumeralRolePolicy(new NumeralTierCatalog(
                NumeralTierCatalog.defaultTiers().subList(0, 4)),
                Map.of("I", TIER_ONE_ROLE, "II", TIER_TWO_ROLE, TIER_THREE_LABEL, TIER_THREE_ROLE, "IV", "104"));
        NumeralRolePolicy.Change change = policy.reconcile(Set.of(TIER_ONE_ROLE, "staff"), 45L * 60L);
        assertEquals(Set.of("104"), change.grant());
        assertEquals(Set.of(TIER_ONE_ROLE), change.revoke());
    }

    @Test void incompleteOrDuplicateRoleMappingIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new NumeralRolePolicy(catalog, Map.of("I", TIER_ONE_ROLE)));
        assertThrows(IllegalArgumentException.class, () -> new NumeralRolePolicy(catalog,
                Map.of("I", TIER_ONE_ROLE, "II", TIER_ONE_ROLE, TIER_THREE_LABEL, TIER_THREE_ROLE)));
    }

    @Test void unlinkRevokesEvenAZeroHourTier() {
        NumeralRolePolicy zeroHour = new NumeralRolePolicy(new NumeralTierCatalog(
                java.util.List.of(new NumeralTierCatalog.Tier("I", 0, "gray"))), Map.of("I", TIER_ONE_ROLE));
        assertEquals(Set.of(TIER_ONE_ROLE), zeroHour.desiredRoles(0));
        assertEquals(Set.of(TIER_ONE_ROLE), zeroHour.revokeAllManaged(Set.of(TIER_ONE_ROLE, "staff")).revoke());
        assertTrue(zeroHour.revokeAllManaged(Set.of(TIER_ONE_ROLE, "staff")).grant().isEmpty());
    }
}
