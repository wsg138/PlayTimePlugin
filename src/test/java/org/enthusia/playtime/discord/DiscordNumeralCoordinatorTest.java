package org.enthusia.playtime.discord;

import github.scarsz.discordsrv.dependencies.jda.api.exceptions.ErrorResponseException;
import github.scarsz.discordsrv.dependencies.jda.api.requests.ErrorResponse;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiscordNumeralCoordinatorTest {
    @Test void onlyUnknownMemberIsACompletedDiscordOperation() {
        ErrorResponseException absent = mock(ErrorResponseException.class);
        when(absent.getErrorResponse()).thenReturn(ErrorResponse.UNKNOWN_MEMBER);
        assertTrue(DiscordNumeralCoordinator.isMemberAbsent(new CompletionException(absent)));
        ErrorResponseException missingPermission = mock(ErrorResponseException.class);
        when(missingPermission.getErrorResponse()).thenReturn(ErrorResponse.MISSING_PERMISSIONS);
        assertFalse(DiscordNumeralCoordinator.isMemberAbsent(new CompletionException(missingPermission)));
        assertFalse(DiscordNumeralCoordinator.isMemberAbsent(new IllegalStateException("storage unavailable")));
    }
}
