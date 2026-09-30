package org.enthusia.playtime.discord;

import github.scarsz.discordsrv.dependencies.jda.api.exceptions.ErrorResponseException;
import github.scarsz.discordsrv.dependencies.jda.api.requests.ErrorResponse;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DiscordSrvNumeralRoleProviderTest {
    @Test
    void onlyUnknownMemberIsNormalizedAsTerminalAbsence() {
        ErrorResponseException absent = mock(ErrorResponseException.class);
        when(absent.getErrorResponse()).thenReturn(ErrorResponse.UNKNOWN_MEMBER);
        assertTrue(DiscordSrvNumeralRoleProvider.isUnknownMember(new CompletionException(absent)));

        ErrorResponseException missingPermission = mock(ErrorResponseException.class);
        when(missingPermission.getErrorResponse()).thenReturn(ErrorResponse.MISSING_PERMISSIONS);
        assertFalse(DiscordSrvNumeralRoleProvider.isUnknownMember(new CompletionException(missingPermission)));
        assertFalse(DiscordSrvNumeralRoleProvider.isUnknownMember(
                new IllegalStateException("storage unavailable")));
    }
}
