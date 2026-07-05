package org.enthusia.playtime.plan;

import com.djrapitops.plan.extension.CallEvents;
import com.djrapitops.plan.extension.DataExtension;
import com.djrapitops.plan.extension.ElementOrder;
import com.djrapitops.plan.extension.FormatType;
import com.djrapitops.plan.extension.annotation.NumberProvider;
import com.djrapitops.plan.extension.annotation.PluginInfo;
import com.djrapitops.plan.extension.annotation.Tab;
import com.djrapitops.plan.extension.annotation.TabInfo;
import com.djrapitops.plan.extension.annotation.TableProvider;
import com.djrapitops.plan.extension.icon.Color;
import com.djrapitops.plan.extension.icon.Family;
import com.djrapitops.plan.extension.icon.Icon;
import com.djrapitops.plan.extension.table.Table;
import com.djrapitops.plan.extension.table.TableColumnFormat;
import org.enthusia.playtime.activity.SessionManager;
import org.enthusia.playtime.data.PlaytimeRepository;
import org.enthusia.playtime.data.model.AdminServerStats;
import org.enthusia.playtime.data.model.PlaytimeSnapshot;
import org.enthusia.playtime.data.model.PublicLeaderboardEntry;
import org.enthusia.playtime.data.model.RangeTotals;
import org.enthusia.playtime.data.model.RecentJoinActivity;
import org.enthusia.playtime.service.PlaytimeReadService;
import org.enthusia.playtime.util.TimeFormats;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@PluginInfo(name = "EnthusiaPlaytime", iconName = "clock", color = Color.TEAL)
@TabInfo(tab = "Player Playtime", iconName = "clock", elementOrder = {ElementOrder.VALUES, ElementOrder.TABLE})
@TabInfo(tab = "Server Activity", iconName = "chart-line", elementOrder = {ElementOrder.VALUES, ElementOrder.TABLE})
public final class PlaytimePlanExtension implements DataExtension {

    private static final long MINUTE_MS = 60_000L;
    private static final String PLAYER_PLAYTIME_TAB = "Player Playtime";
    private static final String SERVER_ACTIVITY_TAB = "Server Activity";
    private static final String ICON_ACTIVE = "person-running";
    private static final String ICON_CALENDAR = "calendar";
    private static final String RANGE_7D = "7D";
    private static final String RANGE_30D = "30D";

    private final PlaytimeRepository repository;
    private final PlaytimeReadService readService;
    private final SessionManager sessionManager;

    public PlaytimePlanExtension(PlaytimeRepository repository, PlaytimeReadService readService, SessionManager sessionManager) {
        this.repository = repository;
        this.readService = readService;
        this.sessionManager = sessionManager;
    }

    @Override
    public CallEvents[] callExtensionMethodsOn() {
        return new CallEvents[]{CallEvents.PLAYER_JOIN, CallEvents.PLAYER_LEAVE};
    }

    @Tab(PLAYER_PLAYTIME_TAB)
    @NumberProvider(
            text = "Active Playtime",
            description = "All-time active playtime tracked by EnthusiaPlaytime.",
            iconName = ICON_ACTIVE,
            iconColor = Color.GREEN,
            format = FormatType.TIME_MILLISECONDS,
            priority = 100,
            showInPlayerTable = true
    )
    public long activePlaytime(UUID playerUUID) {
        return minutesToMillis(lifetime(playerUUID).activeMinutes);
    }

    @Tab(PLAYER_PLAYTIME_TAB)
    @NumberProvider(
            text = "Current Session",
            description = "Length of the player's current online session.",
            iconName = "hourglass-half",
            iconColor = Color.TEAL,
            format = FormatType.TIME_MILLISECONDS,
            priority = 95,
            showInPlayerTable = true
    )
    public long currentSession(UUID playerUUID) {
        return Math.max(0L, sessionManager.getSessionLengthMillis(playerUUID));
    }

    @Tab(PLAYER_PLAYTIME_TAB)
    @NumberProvider(
            text = "Active Last 24h",
            description = "Active playtime in the last rolling 24 hours.",
            iconName = "calendar-day",
            iconColor = Color.LIGHT_GREEN,
            format = FormatType.TIME_MILLISECONDS,
            priority = 90,
            showInPlayerTable = true
    )
    public long activeLast24Hours(UUID playerUUID) {
        return minutesToMillis(repository.getRollingTotals(playerUUID, Instant.now(), 24).activeMinutes);
    }

    @Tab(PLAYER_PLAYTIME_TAB)
    @NumberProvider(
            text = "Active Last 7d",
            description = "Active playtime over the last 7 UTC calendar days.",
            iconName = "calendar-week",
            iconColor = Color.LIGHT_GREEN,
            format = FormatType.TIME_MILLISECONDS,
            priority = 85
    )
    public long activeLast7Days(UUID playerUUID) {
        return minutesToMillis(readService.getRangeTotals(playerUUID, RANGE_7D).activeMinutes);
    }

    @Tab(PLAYER_PLAYTIME_TAB)
    @NumberProvider(
            text = "Active Last 30d",
            description = "Active playtime over the last 30 UTC calendar days.",
            iconName = ICON_CALENDAR,
            iconColor = Color.LIGHT_GREEN,
            format = FormatType.TIME_MILLISECONDS,
            priority = 80
    )
    public long activeLast30Days(UUID playerUUID) {
        return minutesToMillis(readService.getRangeTotals(playerUUID, RANGE_30D).activeMinutes);
    }

    @Tab(PLAYER_PLAYTIME_TAB)
    @NumberProvider(
            text = "First Seen",
            description = "First join timestamp recorded by EnthusiaPlaytime.",
            iconName = "right-to-bracket",
            iconColor = Color.BLUE,
            format = FormatType.DATE_SECOND,
            priority = 60
    )
    public long firstSeen(UUID playerUUID) {
        return epochMillis(repository.getFirstJoin(playerUUID));
    }

    @Tab(PLAYER_PLAYTIME_TAB)
    @NumberProvider(
            text = "Last Seen",
            description = "Last seen timestamp recorded by EnthusiaPlaytime.",
            iconName = "door-open",
            iconColor = Color.BLUE,
            format = FormatType.DATE_SECOND,
            priority = 55
    )
    public long lastSeen(UUID playerUUID) {
        return epochMillis(repository.getLastSeen(playerUUID));
    }

    @Tab(PLAYER_PLAYTIME_TAB)
    @TableProvider(tableColor = Color.TEAL)
    public Table playtimeRanges(UUID playerUUID) {
        long last24h = repository.getRollingTotals(playerUUID, Instant.now(), 24).activeMinutes;
        long last7d = readService.getRangeTotals(playerUUID, RANGE_7D).activeMinutes;
        long last30d = readService.getRangeTotals(playerUUID, RANGE_30D).activeMinutes;
        long all = lifetime(playerUUID).activeMinutes;

        return Table.builder()
                .columnOne("Range", icon(ICON_CALENDAR, Color.TEAL))
                .columnTwo("Active", icon(ICON_ACTIVE, Color.GREEN))
                .columnTwoFormat(TableColumnFormat.NONE)
                .addRow("Last 24h", TimeFormats.formatDurationMillis(minutesToMillis(last24h)))
                .addRow("Last 7d", TimeFormats.formatDurationMillis(minutesToMillis(last7d)))
                .addRow("Last 30d", TimeFormats.formatDurationMillis(minutesToMillis(last30d)))
                .addRow("All Time", TimeFormats.formatDurationMillis(minutesToMillis(all)))
                .build();
    }

    @Tab(SERVER_ACTIVITY_TAB)
    @NumberProvider(
            text = "Known Players",
            description = "Players with a playtime record in EnthusiaPlaytime storage.",
            iconName = "users",
            iconColor = Color.TEAL,
            priority = 100
    )
    public long knownPlayers() {
        return repository.countKnownPlayers();
    }

    @Tab(SERVER_ACTIVITY_TAB)
    @NumberProvider(
            text = "Server Active 24h",
            description = "Server-wide active playtime in the last rolling 24 hours.",
            iconName = "chart-line",
            iconColor = Color.GREEN,
            format = FormatType.TIME_MILLISECONDS,
            priority = 95
    )
    public long serverActiveLast24Hours() {
        return minutesToMillis(repository.getServerRollingTotals(Instant.now(), 24).activeMinutes);
    }

    @Tab(SERVER_ACTIVITY_TAB)
    @NumberProvider(
            text = "Server Active 7d",
            description = "Server-wide active playtime over the last 7 UTC calendar days.",
            iconName = "calendar-week",
            iconColor = Color.GREEN,
            format = FormatType.TIME_MILLISECONDS,
            priority = 90
    )
    public long serverActiveLast7Days() {
        return minutesToMillis(readService.getAdminServerStats(RANGE_7D).activeMinutes);
    }

    @Tab(SERVER_ACTIVITY_TAB)
    @NumberProvider(
            text = "Server Active 30d",
            description = "Server-wide active playtime over the last 30 UTC calendar days.",
            iconName = ICON_CALENDAR,
            iconColor = Color.GREEN,
            format = FormatType.TIME_MILLISECONDS,
            priority = 85
    )
    public long serverActiveLast30Days() {
        return minutesToMillis(readService.getAdminServerStats(RANGE_30D).activeMinutes);
    }

    @Tab(SERVER_ACTIVITY_TAB)
    @TableProvider(tableColor = Color.TEAL)
    public Table serverPlaytimeSummary() {
        Instant now = Instant.now();
        RangeTotals last24h = repository.getServerRollingTotals(now, 24);
        AdminServerStats last7d = readService.getAdminServerStats(RANGE_7D);
        AdminServerStats last30d = readService.getAdminServerStats(RANGE_30D);
        AdminServerStats all = readService.getAdminServerStats("ALL");

        return Table.builder()
                .columnOne("Range", icon(ICON_CALENDAR, Color.TEAL))
                .columnTwo("Active", icon(ICON_ACTIVE, Color.GREEN))
                .columnThree("Players", icon("users", Color.BLUE))
                .columnTwoFormat(TableColumnFormat.NONE)
                .addRow("Last 24h", TimeFormats.formatDurationMillis(minutesToMillis(last24h.activeMinutes)), repository.getRollingUniquePlayers(now, 24))
                .addRow("Last 7d", TimeFormats.formatDurationMillis(minutesToMillis(last7d.activeMinutes)), last7d.playersWithPlaytime)
                .addRow("Last 30d", TimeFormats.formatDurationMillis(minutesToMillis(last30d.activeMinutes)), last30d.playersWithPlaytime)
                .addRow("All Time", TimeFormats.formatDurationMillis(minutesToMillis(all.activeMinutes)), all.playersWithPlaytime)
                .build();
    }

    @Tab(SERVER_ACTIVITY_TAB)
    @TableProvider(tableColor = Color.GREEN)
    public Table topActivePlaytime() {
        Table.Factory table = Table.builder()
                .columnOne("Rank", icon("ranking-star", Color.AMBER))
                .columnTwo("Player", icon("user", Color.BLUE))
                .columnThree("Active", icon(ICON_ACTIVE, Color.GREEN))
                .columnFour("Last Seen", icon("door-open", Color.BLUE))
                .columnTwoFormat(TableColumnFormat.PLAYER_NAME)
                .columnThreeFormat(TableColumnFormat.NONE)
                .columnFourFormat(TableColumnFormat.NONE);

        List<PublicLeaderboardEntry> leaders = repository.getPublicLeaderboard("ACTIVE", "ALL", Instant.now(), 10);
        for (PublicLeaderboardEntry leader : leaders) {
            table.addRow(
                    leader.rank,
                    leader.username,
                    TimeFormats.formatDurationMillis(minutesToMillis(leader.activeMinutes)),
                    leader.lastSeen == null ? "Unknown" : leader.lastSeen.toString()
            );
        }
        return table.build();
    }

    @Tab(SERVER_ACTIVITY_TAB)
    @TableProvider(tableColor = Color.BLUE)
    public Table recentJoinActivity() {
        Table.Factory table = Table.builder()
                .columnOne("Player", icon("user", Color.BLUE))
                .columnTwo("Joined", icon("right-to-bracket", Color.GREEN))
                .columnThree("Result", icon("circle-info", Color.TEAL))
                .columnOneFormat(TableColumnFormat.PLAYER_NAME)
                .columnTwoFormat(TableColumnFormat.DATE_SECOND);

        for (RecentJoinActivity join : repository.getRecentJoinActivity(15)) {
            table.addRow(join.username(), instantMillis(join.joinedAt()), join.firstKnownJoin() ? "First join" : "Returning");
        }
        return table.build();
    }

    private PlaytimeSnapshot lifetime(UUID playerUUID) {
        return readService.getLifetime(playerUUID)
                .orElseGet(() -> new PlaytimeSnapshot(0, 0, 0));
    }

    private long epochMillis(Optional<Instant> instant) {
        return instant.map(Instant::toEpochMilli).orElse(0L);
    }

    private long instantMillis(Instant instant) {
        return instant == null ? 0L : instant.toEpochMilli();
    }

    private long minutesToMillis(long minutes) {
        return Math.max(0L, minutes) * MINUTE_MS;
    }

    private Icon icon(String name, Color color) {
        return new Icon(Family.SOLID, name, color);
    }
}
