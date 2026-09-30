package org.enthusia.playtime.discord;

import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.api.Subscribe;
import github.scarsz.discordsrv.api.events.AccountLinkedEvent;
import github.scarsz.discordsrv.api.events.AccountUnlinkedEvent;
import github.scarsz.discordsrv.dependencies.jda.api.entities.Guild;
import github.scarsz.discordsrv.dependencies.jda.api.entities.Role;
import github.scarsz.discordsrv.dependencies.jda.api.exceptions.ErrorResponseException;
import github.scarsz.discordsrv.dependencies.jda.api.requests.ErrorResponse;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Legacy DiscordSRV compatibility adapter. No DiscordSRV/JDA type escapes this class. */
public final class DiscordSrvNumeralRoleProvider implements NumeralRoleProvider {
    private static final Pattern DISCORD_ID = Pattern.compile("[0-9]{1,20}");

    private final AtomicBoolean started = new AtomicBoolean();
    private volatile LinkListener listener;

    @Override
    public void start(LinkListener listener) {
        Objects.requireNonNull(listener, "listener");
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("DiscordSRV numeral role provider is already started");
        }
        this.listener = listener;
        try {
            DiscordSRV.api.subscribe(this);
        } catch (RuntimeException | Error failure) {
            this.listener = null;
            started.set(false);
            throw failure;
        }
    }

    @Override
    public boolean linksAvailable() {
        return DiscordSRV.isReady && DiscordSRV.getPlugin() != null
                && DiscordSRV.getPlugin().getAccountLinkManager() != null;
    }

    @Override
    public Set<UUID> linkedMinecraftAccounts() {
        requireLinksAvailable();
        return Set.copyOf(DiscordSRV.getPlugin().getAccountLinkManager().getLinkedAccounts().values());
    }

    @Override
    public Optional<NumeralRoleAccountRef> accountFor(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        requireLinksAvailable();
        String discordId = DiscordSRV.getPlugin().getAccountLinkManager().getDiscordId(uuid);
        if (discordId == null) return Optional.empty();
        return Optional.of(discordAccount(discordId));
    }

    @Override
    public Set<UUID> minecraftAccounts(NumeralRoleAccountRef account) {
        Objects.requireNonNull(account, "account");
        requireLinksAvailable();
        return DiscordSRV.getPlugin().getAccountLinkManager().getLinkedAccounts().entrySet().stream()
                .filter(entry -> account.value().equals(entry.getKey()))
                .map(java.util.Map.Entry::getValue)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public CompletableFuture<Set<String>> currentRoles(NumeralRoleAccountRef account) {
        try {
            Guild guild = guild();
            CompletableFuture<Set<String>> future = guild.retrieveMemberById(account.value()).submit()
                    .thenApply(member -> member.getRoles().stream()
                            .map(Role::getId)
                            .collect(Collectors.toUnmodifiableSet()));
            return normalizeUnknownMember(future, Set.of());
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    @Override
    public CompletableFuture<Void> grant(NumeralRoleAccountRef account, String roleId) {
        try {
            Guild guild = guild();
            return normalizeUnknownMember(
                    guild.addRoleToMember(account.value(), role(guild, roleId)).submit(), null);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    @Override
    public CompletableFuture<Void> revoke(NumeralRoleAccountRef account, String roleId) {
        try {
            Guild guild = guild();
            return normalizeUnknownMember(
                    guild.removeRoleFromMember(account.value(), role(guild, roleId)).submit(), null);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    @Subscribe
    public void linked(AccountLinkedEvent event) {
        LinkListener current = listener;
        if (current != null && event.getPlayer() != null) {
            current.linked(event.getPlayer().getUniqueId());
        }
    }

    @Subscribe
    public void unlinked(AccountUnlinkedEvent event) {
        LinkListener current = listener;
        String discordId = event.getDiscordId();
        if (current == null || discordId == null || !DISCORD_ID.matcher(discordId).matches()) return;
        current.unlinked(new NumeralRoleAccountRef(discordId));
    }

    @Override
    public void close() {
        listener = null;
        if (started.compareAndSet(true, false)) {
            DiscordSRV.api.unsubscribe(this);
        }
    }

    static boolean isUnknownMember(Throwable error) {
        Throwable cause = unwrap(error);
        return cause instanceof ErrorResponseException response
                && response.getErrorResponse() == ErrorResponse.UNKNOWN_MEMBER;
    }

    private static <T> CompletableFuture<T> normalizeUnknownMember(
            CompletableFuture<T> future, T absentValue) {
        CompletableFuture<T> normalized = new CompletableFuture<>();
        future.whenComplete((value, error) -> {
            if (error == null) {
                normalized.complete(value);
            } else if (isUnknownMember(error)) {
                normalized.complete(absentValue);
            } else {
                normalized.completeExceptionally(unwrap(error));
            }
        });
        return normalized;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static NumeralRoleAccountRef discordAccount(String discordId) {
        if (!DISCORD_ID.matcher(discordId).matches()) {
            throw new IllegalStateException("DiscordSRV returned an invalid linked Discord account ID");
        }
        return new NumeralRoleAccountRef(discordId);
    }

    private void requireLinksAvailable() {
        if (!linksAvailable()) {
            throw new IllegalStateException("DiscordSRV account links unavailable");
        }
    }

    private static Guild guild() {
        if (!DiscordSRV.isReady || DiscordSRV.getPlugin() == null || DiscordSRV.getPlugin().getMainGuild() == null) {
            throw new IllegalStateException("DiscordSRV main guild is unavailable");
        }
        return DiscordSRV.getPlugin().getMainGuild();
    }

    private static Role role(Guild guild, String roleId) {
        Role role = guild.getRoleById(roleId);
        if (role == null) throw new IllegalStateException("Configured Discord numeral role is missing: " + roleId);
        if (!guild.getSelfMember().canInteract(role)) {
            throw new IllegalStateException("DiscordSRV bot cannot manage numeral role: " + roleId);
        }
        return role;
    }
}
