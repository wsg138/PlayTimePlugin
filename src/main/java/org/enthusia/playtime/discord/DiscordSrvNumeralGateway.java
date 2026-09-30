package org.enthusia.playtime.discord;

import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.dependencies.jda.api.entities.Guild;
import github.scarsz.discordsrv.dependencies.jda.api.entities.Role;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/** DiscordSRV/JDA adapter; no JDA network operation runs on the Paper server thread. */
final class DiscordSrvNumeralGateway implements NumeralRoleSyncService.RoleGateway {
    @Override
    public CompletableFuture<Set<String>> currentRoles(String discordId) {
        try {
            Guild guild = guild();
            return guild.retrieveMemberById(discordId).submit().thenApply(member -> member.getRoles().stream()
                    .map(Role::getId).collect(Collectors.toUnmodifiableSet()));
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    @Override
    public CompletableFuture<Void> grant(String discordId, String roleId) {
        try {
            Guild guild = guild();
            return guild.addRoleToMember(discordId, role(guild, roleId)).submit();
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    @Override
    public CompletableFuture<Void> revoke(String discordId, String roleId) {
        try {
            Guild guild = guild();
            return guild.removeRoleFromMember(discordId, role(guild, roleId)).submit();
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    Guild guild() {
        if (!DiscordSRV.isReady || DiscordSRV.getPlugin() == null || DiscordSRV.getPlugin().getMainGuild() == null) {
            throw new IllegalStateException("DiscordSRV main guild is unavailable");
        }
        return DiscordSRV.getPlugin().getMainGuild();
    }

    private Role role(Guild guild, String roleId) {
        Role role = guild.getRoleById(roleId);
        if (role == null) throw new IllegalStateException("Configured Discord numeral role is missing: " + roleId);
        if (!guild.getSelfMember().canInteract(role)) {
            throw new IllegalStateException("DiscordSRV bot cannot manage numeral role: " + roleId);
        }
        return role;
    }
}
