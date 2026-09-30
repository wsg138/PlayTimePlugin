package org.enthusia.playtime.discord;

import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.api.Subscribe;
import github.scarsz.discordsrv.api.events.AccountLinkedEvent;
import github.scarsz.discordsrv.api.events.AccountUnlinkedEvent;
import github.scarsz.discordsrv.dependencies.jda.api.entities.Guild;
import github.scarsz.discordsrv.dependencies.jda.api.entities.Role;
import github.scarsz.discordsrv.dependencies.jda.api.exceptions.ErrorResponseException;
import github.scarsz.discordsrv.dependencies.jda.api.requests.ErrorResponse;
import github.scarsz.discordsrv.objects.managers.AccountLinkManager;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Legacy DiscordSRV compatibility adapter. No DiscordSRV/JDA type escapes this class. */
public final class DiscordSrvNumeralRoleProvider implements NumeralRoleProvider {
    private static final Pattern DISCORD_ID = Pattern.compile("[0-9]{1,20}");

    private final AtomicBoolean started = new AtomicBoolean();
    private volatile LinkListener listener;

    @Override
    public synchronized void start(LinkListener listener) {
        Objects.requireNonNull(listener, "listener");
        if (started.get()) {
            throw new IllegalStateException("DiscordSRV numeral role provider is already started");
        }
        DiscordSRV.api.subscribe(this);
        this.listener = listener;
        started.set(true);
    }

    @Override
    public boolean linksAvailable() {
        return DiscordSRV.isReady && DiscordSRV.getPlugin() != null
                && DiscordSRV.getPlugin().getAccountLinkManager() != null;
    }

    @Override
    public Set<UUID> linkedMinecraftAccounts() {
        return Set.copyOf(linkManager().getLinkedAccounts().values());
    }

    @Override
    public Optional<NumeralRoleAccountRef> accountFor(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        String discordId = linkManager().getDiscordId(uuid);
        if (discordId == null) return Optional.empty();
        return Optional.of(discordAccount(discordId));
    }

    @Override
    public Set<UUID> minecraftAccounts(NumeralRoleAccountRef account) {
        Objects.requireNonNull(account, "account");
        return linkManager().getLinkedAccounts().entrySet().stream()
                .filter(entry -> account.value().equals(entry.getKey()))
                .map(java.util.Map.Entry::getValue)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public CompletableFuture<Set<String>> currentRoles(NumeralRoleAccountRef account) {
        return captureFailure(() -> {
            Guild guild = guild();
            CompletableFuture<Set<String>> future = guild.retrieveMemberById(account.value()).submit()
                    .thenApply(member -> member.getRoles().stream()
                            .map(Role::getId)
                            .collect(Collectors.toUnmodifiableSet()));
            return normalizeUnknownMember(future, Set.of());
        });
    }

    @Override
    public CompletableFuture<Void> grant(NumeralRoleAccountRef account, String roleId) {
        return captureFailure(() -> {
            Guild guild = guild();
            return normalizeUnknownMember(
                    guild.addRoleToMember(account.value(), role(guild, roleId)).submit(), null);
        });
    }

    @Override
    public CompletableFuture<Void> revoke(NumeralRoleAccountRef account, String roleId) {
        return captureFailure(() -> {
            Guild guild = guild();
            return normalizeUnknownMember(
                    guild.removeRoleFromMember(account.value(), role(guild, roleId)).submit(), null);
        });
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
    public synchronized void close() {
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

    private static <T> CompletableFuture<T> captureFailure(
            Supplier<CompletableFuture<T>> operation) {
        return CompletableFuture.completedFuture(null).thenCompose(ignored -> operation.get());
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

    private static AccountLinkManager linkManager() {
        if (!DiscordSRV.isReady || DiscordSRV.getPlugin() == null
                || DiscordSRV.getPlugin().getAccountLinkManager() == null) {
            throw new IllegalStateException("DiscordSRV account links unavailable");
        }
        return DiscordSRV.getPlugin().getAccountLinkManager();
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
