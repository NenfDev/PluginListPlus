package dev.lsdmc.pluginlistplus;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.StringUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

public final class PluginListPlus extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final String USE_PERMISSION = "pluginlist.use";
    private static final String ADMIN_PERMISSION = "pluginlist.admin";

    private static final List<String> STYLES = List.of(
            "compact", "grouped", "boxed", "categorized", "detailed"
    );
    private static final List<String> SORTS = List.of(
            "alpha", "alpha-desc", "enabled-first", "disabled-first", "paper-first"
    );
    private static final List<String> THEMES = List.of(
            "gold", "crimson", "emerald", "sunset", "rainbow",
            "ocean", "galaxy", "cherry", "arctic", "neon"
    );
    private static final List<String> TOGGLES = List.of(
            "gradients", "versions", "legend", "paper-badge", "override-builtins", "hide-plugins", "small-caps", "small-caps-names"
    );

    private static final String SC_TABLE = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";

    private String sc(String text, boolean enabled) {
        if (!enabled) return text;
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            int idx = Character.toLowerCase(c) - 'a';
            sb.append(idx >= 0 && idx < 26 ? SC_TABLE.charAt(idx) : c);
        }
        return sb.toString();
    }

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, ViewSettings> guiSessions = new HashMap<>();
    private final Map<UUID, Inventory> openGuis = new HashMap<>();
    private final Map<UUID, BrowserSession> browserSessions = new HashMap<>();
    private final Map<UUID, Inventory> openBrowsers = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        PluginCommand command = getCommand("pluginlist");
        if (command == null) {
            getLogger().severe("pluginlist command is missing from plugin.yml");
            return;
        }

        command.setExecutor(this);
        command.setTabCompleter(this);

        if (getConfig().getBoolean("defaults.override-builtins", true)) {
            getServer().getPluginManager().registerEvents(this, this);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        if (!getConfig().getBoolean("defaults.override-builtins", true)) {
            return;
        }

        String message = event.getMessage();
        if (!shouldOverride(message)) {
            return;
        }

        String remainder = extractRemainder(message);
        event.setMessage("/pluginlist" + (remainder.isBlank() ? "" : " " + remainder));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onServerCommand(ServerCommandEvent event) {
        if (!getConfig().getBoolean("defaults.override-builtins", true)) {
            return;
        }

        String command = event.getCommand();
        if (!shouldOverride(command)) {
            return;
        }

        String remainder = extractRemainder(command);
        event.setCommand("pluginlist" + (remainder.isBlank() ? "" : " " + remainder));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(USE_PERMISSION)) {
            sender.sendRichMessage("<red>no permission.</red>");
            return true;
        }

        if (args.length == 0) {
            render(sender, readSettingsFromConfig());
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "show", "preview" -> {
                ViewSettings base = readSettingsFromConfig();
                ViewStyle style = args.length >= 2 ? ViewStyle.from(args[1]) : base.style();
                SortMode sort = args.length >= 3 ? SortMode.from(args[2]) : base.sortMode();
                String theme = args.length >= 4 ? normalizeTheme(args[3]) : base.themeKey();

                render(sender, new ViewSettings(
                        style,
                        sort,
                        theme,
                        base.gradients(),
                        base.showVersions(),
                        base.showLegend(),
                        base.showPaperBadge(),
                        base.smallCaps(),
                        base.smallCapsNames()
                ));
                return true;
            }

            case "config" -> {
                if (!sender.hasPermission(ADMIN_PERMISSION)) {
                    sender.sendRichMessage("<red>no permission.</red>");
                    return true;
                }
                sendConfigPanel(sender, readSettingsFromConfig());
                return true;
            }

            case "_cfg" -> {
                if (!sender.hasPermission(ADMIN_PERMISSION)) return true;
                ViewSettings temp = args.length >= 2 ? parseConfigState(args[1]) : readSettingsFromConfig();
                sendConfigPanel(sender, temp);
                return true;
            }

            case "book" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendRichMessage("<red>book preview is player-only.</red>");
                    return true;
                }
                ViewSettings bookBase = readSettingsFromConfig();
                ViewStyle bookStyle = args.length >= 2 ? ViewStyle.from(args[1]) : bookBase.style();
                SortMode bookSort   = args.length >= 3 ? SortMode.from(args[2])  : bookBase.sortMode();
                String bookTheme    = args.length >= 4 ? normalizeTheme(args[3]) : bookBase.themeKey();
                openBookPreview(player, new ViewSettings(bookStyle, bookSort, bookTheme,
                        bookBase.gradients(), bookBase.showVersions(), bookBase.showLegend(), bookBase.showPaperBadge(), bookBase.smallCaps(), bookBase.smallCapsNames()));
                return true;
            }

            case "browse" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendRichMessage("<red>browse is player-only.</red>");
                    return true;
                }
                ViewSettings browseBase = readSettingsFromConfig();
                ViewStyle browseStyle = args.length >= 2 ? ViewStyle.from(args[1]) : browseBase.style();
                SortMode browseSort   = args.length >= 3 ? SortMode.from(args[2])  : browseBase.sortMode();
                String browseTheme    = args.length >= 4 ? normalizeTheme(args[3]) : browseBase.themeKey();
                openBrowserGui(player, new ViewSettings(browseStyle, browseSort, browseTheme,
                        browseBase.gradients(), browseBase.showVersions(), browseBase.showLegend(), browseBase.showPaperBadge(), browseBase.smallCaps(), browseBase.smallCapsNames()), 0);
                return true;
            }

            case "gui" -> {
                if (!sender.hasPermission(ADMIN_PERMISSION)) {
                    sender.sendRichMessage("<red>no permission.</red>");
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendRichMessage("<red>GUI is player-only. Use /pluginlist config for the chat panel.</red>");
                    return true;
                }
                openConfigGui(player);
                return true;
            }

            case "_setall" -> {
                if (!sender.hasPermission(ADMIN_PERMISSION)) return true;
                if (args.length < 2) return true;
                ViewSettings s = parseConfigState(args[1]);
                getConfig().set("defaults.style", s.style().key);
                getConfig().set("defaults.sort", s.sortMode().key);
                getConfig().set("defaults.theme", s.themeKey());
                getConfig().set("defaults.gradients", s.gradients());
                getConfig().set("defaults.show-versions", s.showVersions());
                getConfig().set("defaults.show-legend", s.showLegend());
                getConfig().set("defaults.show-paper-badge", s.showPaperBadge());
                getConfig().set("defaults.small-caps", s.smallCaps());
                getConfig().set("defaults.small-caps-names", s.smallCapsNames());
                saveConfig();
                ThemeProfile savedTheme = loadTheme(s.themeKey());
                sender.sendMessage(Component.text()
                        .append(Component.text("✔ ", color(savedTheme.enabled())))
                        .append(Component.text("settings saved", color(savedTheme.accent())))
                        .build());
                render(sender, s);
                return true;
            }

            case "set" -> {
                if (!sender.hasPermission(ADMIN_PERMISSION)) {
                    sender.sendRichMessage("<red>no permission.</red>");
                    return true;
                }

                if (args.length < 3) {
                    sendHelp(sender);
                    return true;
                }

                String target = args[1].toLowerCase(Locale.ROOT);
                String value = args[2].toLowerCase(Locale.ROOT);

                switch (target) {
                    case "style" -> {
                        ViewStyle style = ViewStyle.from(value);
                        getConfig().set("defaults.style", style.key);
                        saveConfig();
                        sender.sendRichMessage("<green>default style set to <white>" + style.key + "</white>.</green>");
                    }
                    case "sort" -> {
                        SortMode sort = SortMode.from(value);
                        getConfig().set("defaults.sort", sort.key);
                        saveConfig();
                        sender.sendRichMessage("<green>default sort set to <white>" + sort.key + "</white>.</green>");
                    }
                    case "theme" -> {
                        String theme = normalizeTheme(value);
                        getConfig().set("defaults.theme", theme);
                        saveConfig();
                        sender.sendRichMessage("<green>default theme set to <white>" + theme + "</white>.</green>");
                    }
                    case "hide-message" -> {
                        String msg = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                        getConfig().set("hide-plugins.message", msg);
                        saveConfig();
                        sender.sendRichMessage("<green>hide message updated.</green>");
                    }
                    default -> sendHelp(sender);
                }
                return true;
            }

            case "toggle" -> {
                if (!sender.hasPermission(ADMIN_PERMISSION)) {
                    sender.sendRichMessage("<red>no permission.</red>");
                    return true;
                }

                if (args.length < 2) {
                    sendHelp(sender);
                    return true;
                }

                String toggle = args[1].toLowerCase(Locale.ROOT);
                String path = switch (toggle) {
                    case "gradients" -> "defaults.gradients";
                    case "versions" -> "defaults.show-versions";
                    case "legend" -> "defaults.show-legend";
                    case "paper-badge" -> "defaults.show-paper-badge";
                    case "override-builtins" -> "defaults.override-builtins";
                    case "hide-plugins" -> "hide-plugins.enabled";
                    case "small-caps" -> "defaults.small-caps";
                    case "small-caps-names" -> "defaults.small-caps-names";
                    default -> null;
                };

                if (path == null) {
                    sendHelp(sender);
                    return true;
                }

                boolean newValue = !getConfig().getBoolean(path);
                getConfig().set(path, newValue);
                saveConfig();

                sender.sendRichMessage("<green>" + toggle + " is now <white>" + newValue + "</white>.</green>");
                return true;
            }

            case "reload" -> {
                if (!sender.hasPermission(ADMIN_PERMISSION)) {
                    sender.sendRichMessage("<red>no permission.</red>");
                    return true;
                }

                reloadConfig();
                sender.sendRichMessage("<green>pluginlist config reloaded.</green>");
                return true;
            }

            case "error" -> {
                if (args.length < 2) {
                    sender.sendRichMessage("<red>usage: /pluginlist error <plugin></red>");
                    return true;
                }
                String pluginName = args[1];
                Plugin target = Bukkit.getPluginManager().getPlugin(pluginName);
                if (target == null) {
                    sender.sendRichMessage("<red>unknown plugin: <white>" + pluginName + "</white></red>");
                    return true;
                }
                if (target.isEnabled()) {
                    sender.sendRichMessage("<yellow>" + target.getName() + " is currently enabled. No recent disable error to show.</yellow>");
                    return true;
                }
                sendLastError(sender, target);
                return true;
            }

            case "help" -> {
                sendHelp(sender);
                return true;
            }

            default -> {
                sendHelp(sender);
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            return partial(args[0], List.of("show", "book", "browse", "config", "gui", "set", "toggle", "reload", "error", "help"));
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("error")) {
                List<String> disabled = Arrays.stream(Bukkit.getPluginManager().getPlugins())
                        .filter(p -> !p.isEnabled())
                        .map(Plugin::getName)
                        .collect(Collectors.toList());
                return partial(args[1], disabled);
            }
            return switch (sub) {
                case "show", "preview", "book", "browse" -> partial(args[1], STYLES);
                case "set" -> partial(args[1], List.of("style", "sort", "theme", "hide-message"));
                case "toggle" -> partial(args[1], TOGGLES);
                default -> completions;
            };
        }

        if (args.length == 3) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("show") || sub.equals("preview") || sub.equals("book") || sub.equals("browse")) {
                return partial(args[2], SORTS);
            }
            if (sub.equals("set")) {
                return switch (args[1].toLowerCase(Locale.ROOT)) {
                    case "style" -> partial(args[2], STYLES);
                    case "sort" -> partial(args[2], SORTS);
                    case "theme" -> partial(args[2], THEMES);
                    default -> completions;
                };
            }
        }

        if (args.length == 4) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("show") || sub.equals("preview") || sub.equals("book") || sub.equals("browse")) {
                return partial(args[3], THEMES);
            }
        }

        return completions;
    }

    private List<String> partial(String token, List<String> options) {
        List<String> out = new ArrayList<>();
        StringUtil.copyPartialMatches(token, options, out);
        return out;
    }

    private void sendHelp(CommandSender sender) {
        ThemeProfile theme = loadTheme(getConfig().getString("defaults.theme", "gold"));
        boolean gradients = getConfig().getBoolean("defaults.gradients", true);

        sender.sendMessage(buildTitle("plugin list", theme, gradients, getConfig().getBoolean("defaults.small-caps", true)));
        sender.sendMessage(helpLine("/pluginlist",
                "show using saved defaults",
                ClickEvent.runCommand("/pluginlist"), theme));
        sender.sendMessage(helpLine("/pluginlist config",
                "interactive settings panel with live preview",
                ClickEvent.runCommand("/pluginlist config"), theme));
        sender.sendMessage(helpLine("/pluginlist gui",
                "inventory GUI (click to configure, no typing required)",
                ClickEvent.runCommand("/pluginlist gui"), theme));
        sender.sendMessage(helpLine("/pluginlist show <style> <sort> <theme>",
                "one-shot preview without saving",
                ClickEvent.suggestCommand("/pluginlist show "), theme));
        sender.sendMessage(helpLine("/pluginlist book [style] [sort] [theme]",
                "formatted written-book preview (paginated, clickable plugin names)",
                ClickEvent.suggestCommand("/pluginlist book "), theme));
        sender.sendMessage(helpLine("/pluginlist browse [style] [sort] [theme]",
                "paginated inventory browser (one item per plugin, click to inspect)",
                ClickEvent.suggestCommand("/pluginlist browse "), theme));
        sender.sendMessage(helpLine("/pluginlist set style <compact|grouped|boxed|categorized|detailed>",
                "set default style",
                ClickEvent.suggestCommand("/pluginlist set style "), theme));
        sender.sendMessage(helpLine("/pluginlist set sort <alpha|alpha-desc|enabled-first|disabled-first|paper-first>",
                "set default sort",
                ClickEvent.suggestCommand("/pluginlist set sort "), theme));
        sender.sendMessage(helpLine("/pluginlist set theme <gold|crimson|emerald|sunset|rainbow|ocean|galaxy|cherry|arctic|neon>",
                "set default theme",
                ClickEvent.suggestCommand("/pluginlist set theme "), theme));
        sender.sendMessage(helpLine("/pluginlist set hide-message <message>",
                "set the message shown when plugins are hidden",
                ClickEvent.suggestCommand("/pluginlist set hide-message "), theme));
        sender.sendMessage(helpLine("/pluginlist toggle <gradients|versions|legend|paper-badge|override-builtins|hide-plugins>",
                "toggle a boolean setting",
                ClickEvent.suggestCommand("/pluginlist toggle "), theme));
        sender.sendMessage(helpLine("/pluginlist error <plugin>",
                "show last error from a disabled plugin",
                ClickEvent.suggestCommand("/pluginlist error "), theme));
        sender.sendMessage(helpLine("/pluginlist reload",
                "reload config from disk",
                ClickEvent.runCommand("/pluginlist reload"), theme));
    }

    private Component helpLine(String cmd, String description, ClickEvent click, ThemeProfile theme) {
        return Component.text()
                .append(Component.text(cmd, color(theme.muted()))
                        .clickEvent(click)
                        .hoverEvent(HoverEvent.showText(
                                Component.text(description, color(theme.accent()))
                                        .append(Component.newline())
                                        .append(Component.text("click to fill in command", color(theme.muted()))))))
                .append(Component.text("  -  ", NamedTextColor.DARK_GRAY))
                .append(Component.text(description, NamedTextColor.WHITE))
                .build();
    }

    private void render(CommandSender sender, ViewSettings settings) {
        if (getConfig().getBoolean("hide-plugins.enabled", false)) {
            String raw = getConfig().getString(
                    "hide-plugins.message",
                    "<gray>Plugin information is private.</gray>"
            );
            for (String line : raw.split("\\\\n")) {
                sender.sendMessage(miniMessage.deserialize(line));
            }
            return;
        }

        renderPluginList(sender, settings);
    }

    private void renderPluginList(CommandSender sender, ViewSettings settings) {
        ThemeProfile theme = loadTheme(settings.themeKey());

        List<Plugin> plugins = new ArrayList<>(Arrays.asList(Bukkit.getPluginManager().getPlugins()));
        plugins.sort(comparator(settings.sortMode()));

        long enabledCount = plugins.stream().filter(Plugin::isEnabled).count();
        long disabledCount = plugins.size() - enabledCount;
        long paperCount = plugins.stream().filter(this::isPaperPlugin).count();

        sender.sendMessage(buildTitle("plugin list", theme, settings.gradients(), settings.smallCaps()));
        sender.sendMessage(buildSummary(settings, theme, plugins.size(), enabledCount, disabledCount, paperCount));

        switch (settings.style()) {
            case COMPACT -> sendCompact(sender, plugins, settings, theme);
            case GROUPED -> sendGrouped(sender, plugins, settings, theme);
            case BOXED -> sendBoxed(sender, plugins, settings, theme);
            case CATEGORIZED -> sendCategorized(sender, plugins, settings, theme);
            case DETAILED -> sendDetailed(sender, plugins, settings, theme);
        }

        if (settings.showLegend()) {
            sender.sendMessage(buildLegend(theme, settings.showPaperBadge(), settings.smallCaps()));
        }
    }

    private void sendCompact(CommandSender sender, List<Plugin> plugins, ViewSettings settings, ThemeProfile theme) {
        int chunkSize = 10;

        for (int i = 0; i < plugins.size(); i += chunkSize) {
            List<Plugin> chunk = plugins.subList(i, Math.min(i + chunkSize, plugins.size()));

            TextComponent.Builder line = Component.text();
            line.append(Component.text("• ", NamedTextColor.DARK_GRAY));

            for (int j = 0; j < chunk.size(); j++) {
                if (j > 0) {
                    line.append(Component.text(", ", NamedTextColor.DARK_GRAY));
                }
                line.append(pluginChip(chunk.get(j), settings, theme));
            }

            sender.sendMessage(line.build());
        }
    }

    private void sendGrouped(CommandSender sender, List<Plugin> plugins, ViewSettings settings, ThemeProfile theme) {
        List<Plugin> enabled = plugins.stream().filter(Plugin::isEnabled).collect(Collectors.toList());
        List<Plugin> disabled = plugins.stream().filter(plugin -> !plugin.isEnabled()).collect(Collectors.toList());

        sender.sendMessage(sectionHeader("enabled", enabled.size(), theme, settings.gradients(), true, settings.smallCaps()));
        sendCompact(sender, enabled, settings, theme);

        if (!disabled.isEmpty()) {
            sender.sendMessage(sectionHeader("disabled", disabled.size(), theme, false, false, settings.smallCaps()));
            sendCompact(sender, disabled, settings, theme);
        }
    }

    private void sendBoxed(CommandSender sender, List<Plugin> plugins, ViewSettings settings, ThemeProfile theme) {
        for (Plugin plugin : plugins) {
            TextComponent.Builder line = Component.text();

            line.append(Component.text("▸ ", NamedTextColor.DARK_GRAY));
            line.append(pluginChip(plugin, settings, theme));

            if (settings.showVersions()) {
                line.append(Component.text(" v" + plugin.getDescription().getVersion(), color(theme.muted())));
            }

            sender.sendMessage(line.build());
        }
    }

    private void sendCategorized(CommandSender sender, List<Plugin> plugins, ViewSettings settings, ThemeProfile theme) {
        List<Plugin> paperEnabled = plugins.stream()
                .filter(p -> isPaperPlugin(p) && p.isEnabled()).collect(Collectors.toList());
        List<Plugin> paperDisabled = plugins.stream()
                .filter(p -> isPaperPlugin(p) && !p.isEnabled()).collect(Collectors.toList());
        List<Plugin> bukkitEnabled = plugins.stream()
                .filter(p -> !isPaperPlugin(p) && p.isEnabled()).collect(Collectors.toList());
        List<Plugin> bukkitDisabled = plugins.stream()
                .filter(p -> !isPaperPlugin(p) && !p.isEnabled()).collect(Collectors.toList());

        if (!paperEnabled.isEmpty() || !paperDisabled.isEmpty()) {
            sender.sendMessage(sectionHeaderPaper("paper plugins", paperEnabled.size() + paperDisabled.size(), theme, settings.gradients(), settings.smallCaps()));
            if (!paperEnabled.isEmpty()) {
                sender.sendMessage(sectionHeader("enabled", paperEnabled.size(), theme, false, true, settings.smallCaps()));
                sendCompact(sender, paperEnabled, settings, theme);
            }
            if (!paperDisabled.isEmpty()) {
                sender.sendMessage(sectionHeader("disabled", paperDisabled.size(), theme, false, false, settings.smallCaps()));
                sendCompact(sender, paperDisabled, settings, theme);
            }
        }

        if (!bukkitEnabled.isEmpty() || !bukkitDisabled.isEmpty()) {
            sender.sendMessage(sectionHeaderBukkit("bukkit plugins", bukkitEnabled.size() + bukkitDisabled.size(), theme, settings.gradients(), settings.smallCaps()));
            if (!bukkitEnabled.isEmpty()) {
                sender.sendMessage(sectionHeader("enabled", bukkitEnabled.size(), theme, false, true, settings.smallCaps()));
                sendCompact(sender, bukkitEnabled, settings, theme);
            }
            if (!bukkitDisabled.isEmpty()) {
                sender.sendMessage(sectionHeader("disabled", bukkitDisabled.size(), theme, false, false, settings.smallCaps()));
                sendCompact(sender, bukkitDisabled, settings, theme);
            }
        }
    }

    private void sendDetailed(CommandSender sender, List<Plugin> plugins, ViewSettings settings, ThemeProfile theme) {
        for (Plugin plugin : plugins) {
            PluginDescriptionFile desc = plugin.getDescription();
            boolean isPaper = isPaperPlugin(plugin);

            TextComponent.Builder line = Component.text();
            line.append(Component.text("▸ ", NamedTextColor.DARK_GRAY));
            line.append(pluginChip(plugin, settings, theme));

            line.append(Component.text(" v" + desc.getVersion(), color(theme.muted())));

            if (isPaper) {
                line.append(Component.text(" ", NamedTextColor.WHITE));
                line.append(paperBadge(theme));
            }

            if (!desc.getAuthors().isEmpty()) {
                line.append(Component.text("  by ", color(theme.muted())));
                line.append(Component.text(String.join(", ", desc.getAuthors()), color(theme.accent())));
            }

            if (isLegacy(desc)) {
                line.append(Component.text(" ★", color(theme.warning())));
            }

            sender.sendMessage(line.build());
        }
    }

    private Component buildTitle(String title, ThemeProfile theme, boolean gradients, boolean smallCaps) {
        TextComponent.Builder builder = Component.text();
        builder.append(Component.text("━━━━━━━━ ", NamedTextColor.DARK_GRAY));
        builder.append(themed(sc(title, smallCaps), theme, gradients, true));
        builder.append(Component.text(" ━━━━━━━━", NamedTextColor.DARK_GRAY));
        return builder.build();
    }

    private Component buildSummary(ViewSettings settings, ThemeProfile theme, int total, long enabled, long disabled, long paper) {
        TextComponent.Builder builder = Component.text();
        boolean s = settings.smallCaps();

        builder.append(label(sc("style", s), theme)).append(value(settings.style().key, theme));
        builder.append(Component.text("  •  ", NamedTextColor.DARK_GRAY));
        builder.append(label(sc("sort", s), theme)).append(value(settings.sortMode().key, theme));
        builder.append(Component.text("  •  ", NamedTextColor.DARK_GRAY));
        builder.append(label(sc("theme", s), theme)).append(value(settings.themeKey(), theme));
        builder.append(Component.newline());

        builder.append(label(sc("total", s), theme)).append(value(Integer.toString(total), theme));
        builder.append(Component.text("  •  ", NamedTextColor.DARK_GRAY));
        builder.append(label(sc("enabled", s), theme)).append(Component.text(Long.toString(enabled), color(theme.enabled())));
        builder.append(Component.text("  •  ", NamedTextColor.DARK_GRAY));
        builder.append(label(sc("disabled", s), theme)).append(Component.text(Long.toString(disabled), color(theme.disabled())));
        builder.append(Component.text("  •  ", NamedTextColor.DARK_GRAY));
        builder.append(label(sc("paper", s), theme)).append(Component.text(Long.toString(paper), color(theme.paperBadge())));

        return builder.build();
    }

    private Component buildLegend(ThemeProfile theme, boolean showPaperBadge, boolean smallCaps) {
        TextComponent.Builder builder = Component.text();
        builder.append(Component.text("↳ ", NamedTextColor.DARK_GRAY));
        builder.append(Component.text(sc("enabled", smallCaps), color(theme.enabled())));
        builder.append(Component.text("  •  ", NamedTextColor.DARK_GRAY));
        builder.append(Component.text(sc("disabled", smallCaps), color(theme.disabled())));
        builder.append(Component.text("  •  ", NamedTextColor.DARK_GRAY));
        builder.append(Component.text(sc("★ legacy api-version missing", smallCaps), color(theme.warning())));
        if (showPaperBadge) {
            builder.append(Component.text("  •  ", NamedTextColor.DARK_GRAY));
            builder.append(paperBadge(theme));
            builder.append(Component.text(sc(" paper plugin", smallCaps), color(theme.paperBadge())));
        }
        return builder.build();
    }

    private Component sectionHeader(String name, int count, ThemeProfile theme, boolean gradients, boolean useThemeColor, boolean smallCaps) {
        TextComponent.Builder builder = Component.text();
        builder.append(Component.text("┌ ", NamedTextColor.DARK_GRAY));
        String display = sc(name, smallCaps) + " (" + count + ")";
        if (useThemeColor) {
            builder.append(themed(display, theme, gradients, false));
        } else {
            builder.append(Component.text(display, color(theme.disabled())));
        }
        return builder.build();
    }

    private Component sectionHeaderPaper(String name, int count, ThemeProfile theme, boolean gradients, boolean smallCaps) {
        TextComponent.Builder builder = Component.text();
        builder.append(Component.text("┌ ", NamedTextColor.DARK_GRAY));
        builder.append(paperBadge(theme));
        builder.append(Component.text(" ", NamedTextColor.WHITE));
        builder.append(themed(sc(name, smallCaps) + " (" + count + ")", theme, gradients, false));
        return builder.build();
    }

    private Component sectionHeaderBukkit(String name, int count, ThemeProfile theme, boolean gradients, boolean smallCaps) {
        TextComponent.Builder builder = Component.text();
        builder.append(Component.text("┌ ", NamedTextColor.DARK_GRAY));
        builder.append(Component.text("[B] ", color(theme.muted())));
        builder.append(themed(sc(name, smallCaps) + " (" + count + ")", theme, gradients, false));
        return builder.build();
    }

    private Component paperBadge(ThemeProfile theme) {
        return Component.text("[P]", color(theme.paperBadge()))
                .decorate(TextDecoration.BOLD)
                .hoverEvent(HoverEvent.showText(
                        Component.text("Paper plugin", color(theme.paperBadge()))
                                .append(Component.newline())
                                .append(Component.text("Uses the Paper plugin loader API", color(theme.muted())))
                ));
    }

    private Component pluginChip(Plugin plugin, ViewSettings settings, ThemeProfile theme) {
        PluginDescriptionFile description = plugin.getDescription();
        boolean isPaper = isPaperPlugin(plugin);
        String displayName = sc(plugin.getName(), settings.smallCapsNames());

        Component nameComponent;
        if (plugin.isEnabled() && settings.gradients()) {
            nameComponent = miniMessage.deserialize(
                    "<gradient:" + escape(theme.headerStart()) + ":" + escape(theme.headerEnd()) + ">" +
                            escape(displayName) +
                            "</gradient>"
            );
        } else {
            nameComponent = Component.text(
                    displayName,
                    color(plugin.isEnabled() ? theme.enabled() : theme.disabled())
            );
        }

        if (isLegacy(description)) {
            nameComponent = Component.text("★ ", color(theme.warning())).append(nameComponent);
        }

        if (isPaper && settings.showPaperBadge()) {
            nameComponent = paperBadge(theme)
                    .append(Component.text(" ", NamedTextColor.WHITE))
                    .append(nameComponent);
        }

        ClickEvent clickEvent = plugin.isEnabled()
                ? ClickEvent.runCommand("/version " + plugin.getName())
                : ClickEvent.runCommand("/pluginlist error " + plugin.getName());

        return nameComponent
                .clickEvent(clickEvent)
                .hoverEvent(HoverEvent.showText(buildHover(plugin, theme, isPaper)));
    }

    private Component buildHover(Plugin plugin, ThemeProfile theme, boolean isPaper) {
        PluginDescriptionFile description = plugin.getDescription();
        TextComponent.Builder hover = Component.text();

        hover.append(Component.text(plugin.getName(), color(plugin.isEnabled() ? theme.enabled() : theme.disabled())));

        if (isPaper) {
            hover.append(Component.text("  "));
            hover.append(Component.text("[Paper]", color(theme.paperBadge())).decorate(TextDecoration.BOLD));
        }

        hover.append(Component.newline());

        hover.append(label("version", theme)).append(value(description.getVersion(), theme)).append(Component.newline());

        if (!description.getAuthors().isEmpty()) {
            hover.append(label("author", theme))
                    .append(value(String.join(", ", description.getAuthors()), theme))
                    .append(Component.newline());
        }

        String pluginDescription = description.getDescription();
        if (pluginDescription != null && !pluginDescription.isBlank()) {
            hover.append(label("about", theme))
                    .append(Component.text(pluginDescription, color(theme.muted())))
                    .append(Component.newline());
        }

        String website = description.getWebsite();
        if (website != null && !website.isBlank()) {
            hover.append(label("site", theme))
                    .append(Component.text(website, color(theme.muted())))
                    .append(Component.newline());
        }

        String apiVersion = description.getAPIVersion();
        if (apiVersion != null && !apiVersion.isBlank()) {
            hover.append(label("api", theme)).append(value(apiVersion, theme)).append(Component.newline());
        }

        if (plugin.isEnabled()) {
            hover.append(Component.text("click to run /version " + plugin.getName(), color(theme.warning())));
        } else {
            hover.append(Component.text("click to show last error for " + plugin.getName(), color(theme.disabled())));
        }
        return hover.build();
    }

    private Component themed(String text, ThemeProfile theme, boolean gradients, boolean bold) {
        if (gradients) {
            String wrapped = switch (theme.headerMode().toLowerCase(Locale.ROOT)) {
                case "rainbow" -> "<rainbow>" + (bold ? "<bold>" + escape(text) + "</bold>" : escape(text)) + "</rainbow>";
                default -> "<gradient:" + escape(theme.headerStart()) + ":" + escape(theme.headerEnd()) + ">" +
                        (bold ? "<bold>" + escape(text) + "</bold>" : escape(text)) +
                        "</gradient>";
            };
            return miniMessage.deserialize(wrapped);
        }

        Component out = Component.text(text, color(theme.accent()));
        return bold ? out.decorate(TextDecoration.BOLD) : out;
    }

    private Component label(String text, ThemeProfile theme) {
        return Component.text(text + ": ", color(theme.muted()));
    }

    private Component value(String text, ThemeProfile theme) {
        return Component.text(text, color(theme.accent()));
    }

    private Comparator<Plugin> comparator(SortMode mode) {
        Comparator<Plugin> alpha = Comparator.comparing(plugin -> plugin.getName().toLowerCase(Locale.ROOT));
        Comparator<Plugin> paperFirst = Comparator.comparing((Plugin p) -> isPaperPlugin(p) ? 0 : 1);

        return switch (mode) {
            case ALPHA -> alpha;
            case ALPHA_DESC -> alpha.reversed();
            case ENABLED_FIRST -> Comparator.comparing(Plugin::isEnabled).reversed().thenComparing(alpha);
            case DISABLED_FIRST -> Comparator.comparing(Plugin::isEnabled).thenComparing(alpha);
            case PAPER_FIRST -> paperFirst.thenComparing(Comparator.comparing(Plugin::isEnabled).reversed()).thenComparing(alpha);
        };
    }

    // ── Interactive config panel ──────────────────────────────────────────────

    private void sendConfigPanel(CommandSender sender, ViewSettings temp) {
        ThemeProfile theme = loadTheme(temp.themeKey());
        String savedState = encodeConfigState(readSettingsFromConfig());
        String tempState = encodeConfigState(temp);
        boolean smallCaps = temp.smallCaps();

        // Live preview first — controls render below and stay visible without scrolling
        renderPluginList(sender, temp);

        sender.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.DARK_GRAY));
        sender.sendMessage(buildTitle("configure", theme, temp.gradients(), smallCaps));

        // Style row
        String prevStyle = cycleList(STYLES, temp.style().key, false);
        String nextStyle = cycleList(STYLES, temp.style().key, true);
        sender.sendMessage(buildCycleRow(sc("style", smallCaps), temp.style().key, color(theme.accent()),
                "/pluginlist _cfg " + encodeConfigState(withStyle(temp, prevStyle)),
                "/pluginlist _cfg " + encodeConfigState(withStyle(temp, nextStyle)),
                theme));

        // Sort row
        String prevSort = cycleList(SORTS, temp.sortMode().key, false);
        String nextSort = cycleList(SORTS, temp.sortMode().key, true);
        sender.sendMessage(buildCycleRow(sc("sort", smallCaps), temp.sortMode().key, color(theme.accent()),
                "/pluginlist _cfg " + encodeConfigState(withSort(temp, prevSort)),
                "/pluginlist _cfg " + encodeConfigState(withSort(temp, nextSort)),
                theme));

        // Theme row
        String prevTheme = cycleList(THEMES, temp.themeKey(), false);
        String nextTheme = cycleList(THEMES, temp.themeKey(), true);
        ThemeProfile prevThemeProfile = loadTheme(prevTheme);
        ThemeProfile nextThemeProfile = loadTheme(nextTheme);
        sender.sendMessage(buildCycleRowTheme(sc("theme", smallCaps), temp.themeKey(), color(theme.accent()),
                "/pluginlist _cfg " + encodeConfigState(withTheme(temp, prevTheme)),
                "/pluginlist _cfg " + encodeConfigState(withTheme(temp, nextTheme)),
                prevTheme, color(prevThemeProfile.accent()),
                nextTheme, color(nextThemeProfile.accent()),
                theme));

        // Toggle rows
        sender.sendMessage(buildToggleRow(sc("gradients", smallCaps), temp.gradients(),
                "/pluginlist _cfg " + encodeConfigState(withGradients(temp, !temp.gradients())), theme));
        sender.sendMessage(buildToggleRow(sc("versions", smallCaps), temp.showVersions(),
                "/pluginlist _cfg " + encodeConfigState(withVersions(temp, !temp.showVersions())), theme));
        sender.sendMessage(buildToggleRow(sc("legend", smallCaps), temp.showLegend(),
                "/pluginlist _cfg " + encodeConfigState(withLegend(temp, !temp.showLegend())), theme));
        sender.sendMessage(buildToggleRow(sc("paper-badge", smallCaps), temp.showPaperBadge(),
                "/pluginlist _cfg " + encodeConfigState(withBadge(temp, !temp.showPaperBadge())), theme));
        sender.sendMessage(buildToggleRow(sc("small-caps", smallCaps), temp.smallCaps(),
                "/pluginlist _cfg " + encodeConfigState(withSmallCaps(temp, !temp.smallCaps())), theme));
        sender.sendMessage(buildSubToggleRow(sc("plugin names", smallCaps), temp.smallCapsNames(),
                "/pluginlist _cfg " + encodeConfigState(withSmallCapsNames(temp, !temp.smallCapsNames())), theme));

        sender.sendMessage(Component.text("  ─────────────────────────────────────", NamedTextColor.DARK_GRAY));

        // Apply & Reset buttons
        boolean isDirty = !tempState.equals(savedState);
        Component applyBtn = Component.text("  [" + sc("◈ Apply & Save", smallCaps) + "]",
                        isDirty ? color(theme.enabled()) : color(theme.muted()))
                .clickEvent(ClickEvent.runCommand("/pluginlist _setall " + tempState))
                .hoverEvent(HoverEvent.showText(isDirty
                        ? Component.text("Save these settings as the new defaults", color(theme.accent()))
                        : Component.text("No unsaved changes", color(theme.muted()))));

        Component resetBtn = Component.text("  [" + sc("↺ Reset", smallCaps) + "]", color(theme.muted()))
                .clickEvent(ClickEvent.runCommand("/pluginlist _cfg " + savedState))
                .hoverEvent(HoverEvent.showText(
                        Component.text("Revert to currently saved config", color(theme.muted()))));

        sender.sendMessage(Component.text().append(applyBtn).append(resetBtn).build());
    }

    private Component buildCycleRow(String label, String value, TextColor valColor,
                                     String cmdPrev, String cmdNext, ThemeProfile theme) {
        return Component.text()
                .append(Component.text("  " + label + ":  ", color(theme.muted())))
                .append(Component.text("[◄]", color(theme.accent()))
                        .clickEvent(ClickEvent.runCommand(cmdPrev))
                        .hoverEvent(HoverEvent.showText(Component.text("previous", color(theme.muted())))))
                .append(Component.text("  "))
                .append(Component.text(value, valColor))
                .append(Component.text("  "))
                .append(Component.text("[►]", color(theme.accent()))
                        .clickEvent(ClickEvent.runCommand(cmdNext))
                        .hoverEvent(HoverEvent.showText(Component.text("next", color(theme.muted())))))
                .build();
    }

    private Component buildCycleRowTheme(String label, String value, TextColor valColor,
                                          String cmdPrev, String cmdNext,
                                          String prevLabel, TextColor prevColor,
                                          String nextLabel, TextColor nextColor,
                                          ThemeProfile theme) {
        return Component.text()
                .append(Component.text("  " + label + ":  ", color(theme.muted())))
                .append(Component.text("[◄]", color(theme.accent()))
                        .clickEvent(ClickEvent.runCommand(cmdPrev))
                        .hoverEvent(HoverEvent.showText(Component.text("◀ " + prevLabel, prevColor))))
                .append(Component.text("  "))
                .append(Component.text(value, valColor))
                .append(Component.text("  "))
                .append(Component.text("[►]", color(theme.accent()))
                        .clickEvent(ClickEvent.runCommand(cmdNext))
                        .hoverEvent(HoverEvent.showText(Component.text(nextLabel + " ▶", nextColor))))
                .build();
    }

    private Component buildToggleRow(String label, boolean value, String cmdToggle, ThemeProfile theme) {
        String display = value ? "on" : "off";
        TextColor btnColor = value ? color(theme.enabled()) : color(theme.disabled());
        return Component.text()
                .append(Component.text("  " + label + ":  ", color(theme.muted())))
                .append(Component.text("[" + display + "]", btnColor)
                        .clickEvent(ClickEvent.runCommand(cmdToggle))
                        .hoverEvent(HoverEvent.showText(Component.text("click to toggle", color(theme.muted())))))
                .build();
    }

    private Component buildSubToggleRow(String label, boolean value, String cmdToggle, ThemeProfile theme) {
        String display = value ? "on" : "off";
        TextColor btnColor = value ? color(theme.enabled()) : color(theme.disabled());
        return Component.text()
                .append(Component.text("  ╰ " + label + ":  ", color(theme.muted())))
                .append(Component.text("[" + display + "]", btnColor)
                        .clickEvent(ClickEvent.runCommand(cmdToggle))
                        .hoverEvent(HoverEvent.showText(Component.text("click to toggle", color(theme.muted())))))
                .build();
    }

    // ── Config state encoding / decoding ─────────────────────────────────────

    private String encodeConfigState(ViewSettings s) {
        return s.style().key + "/" + s.sortMode().key + "/" + s.themeKey() + "/"
                + s.gradients() + "/" + s.showVersions() + "/" + s.showLegend() + "/" + s.showPaperBadge()
                + "/" + s.smallCaps() + "/" + s.smallCapsNames();
    }

    private ViewSettings parseConfigState(String encoded) {
        String[] parts = encoded.split("/", -1);
        if (parts.length < 7) return readSettingsFromConfig();
        return new ViewSettings(
                ViewStyle.from(parts[0]),
                SortMode.from(parts[1]),
                normalizeTheme(parts[2]),
                Boolean.parseBoolean(parts[3]),
                Boolean.parseBoolean(parts[4]),
                Boolean.parseBoolean(parts[5]),
                Boolean.parseBoolean(parts[6]),
                parts.length >= 8 ? Boolean.parseBoolean(parts[7]) : true,
                parts.length >= 9 ? Boolean.parseBoolean(parts[8]) : false
        );
    }

    private String cycleList(List<String> list, String current, boolean forward) {
        int idx = list.indexOf(current);
        if (idx == -1) idx = 0;
        int next = forward ? (idx + 1) % list.size() : (idx - 1 + list.size()) % list.size();
        return list.get(next);
    }

    // ── ViewSettings "with" builders ─────────────────────────────────────────

    private ViewSettings withStyle(ViewSettings s, String v)           { return new ViewSettings(ViewStyle.from(v), s.sortMode(), s.themeKey(), s.gradients(), s.showVersions(), s.showLegend(), s.showPaperBadge(), s.smallCaps(), s.smallCapsNames()); }
    private ViewSettings withSort(ViewSettings s, String v)            { return new ViewSettings(s.style(), SortMode.from(v), s.themeKey(), s.gradients(), s.showVersions(), s.showLegend(), s.showPaperBadge(), s.smallCaps(), s.smallCapsNames()); }
    private ViewSettings withTheme(ViewSettings s, String v)           { return new ViewSettings(s.style(), s.sortMode(), normalizeTheme(v), s.gradients(), s.showVersions(), s.showLegend(), s.showPaperBadge(), s.smallCaps(), s.smallCapsNames()); }
    private ViewSettings withGradients(ViewSettings s, boolean v)      { return new ViewSettings(s.style(), s.sortMode(), s.themeKey(), v, s.showVersions(), s.showLegend(), s.showPaperBadge(), s.smallCaps(), s.smallCapsNames()); }
    private ViewSettings withVersions(ViewSettings s, boolean v)       { return new ViewSettings(s.style(), s.sortMode(), s.themeKey(), s.gradients(), v, s.showLegend(), s.showPaperBadge(), s.smallCaps(), s.smallCapsNames()); }
    private ViewSettings withLegend(ViewSettings s, boolean v)         { return new ViewSettings(s.style(), s.sortMode(), s.themeKey(), s.gradients(), s.showVersions(), v, s.showPaperBadge(), s.smallCaps(), s.smallCapsNames()); }
    private ViewSettings withBadge(ViewSettings s, boolean v)          { return new ViewSettings(s.style(), s.sortMode(), s.themeKey(), s.gradients(), s.showVersions(), s.showLegend(), v, s.smallCaps(), s.smallCapsNames()); }
    private ViewSettings withSmallCaps(ViewSettings s, boolean v)      { return new ViewSettings(s.style(), s.sortMode(), s.themeKey(), s.gradients(), s.showVersions(), s.showLegend(), s.showPaperBadge(), v, s.smallCapsNames()); }
    private ViewSettings withSmallCapsNames(ViewSettings s, boolean v) { return new ViewSettings(s.style(), s.sortMode(), s.themeKey(), s.gradients(), s.showVersions(), s.showLegend(), s.showPaperBadge(), s.smallCaps(), v); }

    // ── Inventory GUI ─────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        UUID uuid = player.getUniqueId();

        // Config GUI
        Inventory configInv = openGuis.get(uuid);
        if (configInv != null && event.getInventory().equals(configInv)) {
            event.setCancelled(true);
            if (!configInv.equals(event.getClickedInventory())) return;
            ViewSettings current = guiSessions.get(uuid);
            if (current == null) return;
            int slot = event.getRawSlot();
            if (slot == GUI_SLOT_APPLY) { applyGuiSettings(player, current); return; }
            if (slot == GUI_SLOT_CLOSE) { player.closeInventory(); return; }
            ViewSettings updated = applyGuiClick(slot, current);
            if (updated != null) { guiSessions.put(uuid, updated); refreshGui(player, updated); }
            return;
        }

        // Plugin Browser
        Inventory browserInv = openBrowsers.get(uuid);
        if (browserInv != null && event.getInventory().equals(browserInv)) {
            event.setCancelled(true);
            if (!browserInv.equals(event.getClickedInventory())) return;
            handleBrowserClick(player, event.getRawSlot());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        UUID uuid = player.getUniqueId();
        Inventory configInv = openGuis.get(uuid);
        if (configInv != null && event.getInventory().equals(configInv)) {
            openGuis.remove(uuid);
            guiSessions.remove(uuid);
            return;
        }
        Inventory browserInv = openBrowsers.get(uuid);
        if (browserInv != null && event.getInventory().equals(browserInv)) {
            openBrowsers.remove(uuid);
            browserSessions.remove(uuid);
        }
    }

    private static final int GUI_SLOT_APPLY = 49;
    private static final int GUI_SLOT_CLOSE = 53;

    private void openConfigGui(Player player) {
        ViewSettings temp = guiSessions.getOrDefault(player.getUniqueId(), readSettingsFromConfig());
        guiSessions.put(player.getUniqueId(), temp);
        Inventory inv = buildConfigGui(temp);
        openGuis.put(player.getUniqueId(), inv);
        player.openInventory(inv);
    }

    private Inventory buildConfigGui(ViewSettings temp) {
        ThemeProfile theme = loadTheme(temp.themeKey());
        Inventory inv = Bukkit.createInventory(null, 54,
                Component.text("⚙ PluginListPlus Config", color(theme.accent())));

        ItemStack filler = fillerPane();
        for (int i = 0; i < 54; i++) inv.setItem(i, filler);

        // Row 0 - Style
        inv.setItem(0, labelPane("Style", theme));
        inv.setItem(1, styleItem(ViewStyle.COMPACT, temp, theme));
        inv.setItem(2, styleItem(ViewStyle.GROUPED, temp, theme));
        inv.setItem(3, styleItem(ViewStyle.BOXED, temp, theme));
        inv.setItem(4, styleItem(ViewStyle.CATEGORIZED, temp, theme));
        inv.setItem(5, styleItem(ViewStyle.DETAILED, temp, theme));

        // Row 1 - Sort
        inv.setItem(9, labelPane("Sort", theme));
        inv.setItem(10, sortItem(SortMode.ALPHA, temp, theme));
        inv.setItem(11, sortItem(SortMode.ALPHA_DESC, temp, theme));
        inv.setItem(12, sortItem(SortMode.ENABLED_FIRST, temp, theme));
        inv.setItem(13, sortItem(SortMode.DISABLED_FIRST, temp, theme));
        inv.setItem(14, sortItem(SortMode.PAPER_FIRST, temp, theme));

        // Row 2 - Themes (first 8)
        inv.setItem(18, labelPane("Theme", theme));
        inv.setItem(19, themeItem("gold", temp, theme));
        inv.setItem(20, themeItem("crimson", temp, theme));
        inv.setItem(21, themeItem("emerald", temp, theme));
        inv.setItem(22, themeItem("sunset", temp, theme));
        inv.setItem(23, themeItem("rainbow", temp, theme));
        inv.setItem(24, themeItem("ocean", temp, theme));
        inv.setItem(25, themeItem("galaxy", temp, theme));
        inv.setItem(26, themeItem("cherry", temp, theme));

        // Row 3 - Themes (last 2)
        inv.setItem(27, themeItem("arctic", temp, theme));
        inv.setItem(28, themeItem("neon", temp, theme));

        // Row 4 - Toggles
        inv.setItem(36, labelPane("Options", theme));
        inv.setItem(37, toggleItem("Gradients", temp.gradients(), theme));
        inv.setItem(38, toggleItem("Versions", temp.showVersions(), theme));
        inv.setItem(39, toggleItem("Legend", temp.showLegend(), theme));
        inv.setItem(40, toggleItem("Paper Badge", temp.showPaperBadge(), theme));
        inv.setItem(41, toggleItem("Small Caps", temp.smallCaps(), theme));
        inv.setItem(42, toggleItem("SC Plugin Names", temp.smallCapsNames(), theme));

        // Row 5 - Actions
        inv.setItem(GUI_SLOT_APPLY, applyButton(theme));
        inv.setItem(GUI_SLOT_CLOSE, closeButton(theme));

        return inv;
    }

    private void refreshGui(Player player, ViewSettings updated) {
        Inventory existing = openGuis.get(player.getUniqueId());
        if (existing == null) return;
        Inventory fresh = buildConfigGui(updated);
        for (int i = 0; i < 54; i++) existing.setItem(i, fresh.getItem(i));
    }

    private void applyGuiSettings(Player player, ViewSettings s) {
        getConfig().set("defaults.style", s.style().key);
        getConfig().set("defaults.sort", s.sortMode().key);
        getConfig().set("defaults.theme", s.themeKey());
        getConfig().set("defaults.gradients", s.gradients());
        getConfig().set("defaults.show-versions", s.showVersions());
        getConfig().set("defaults.show-legend", s.showLegend());
        getConfig().set("defaults.show-paper-badge", s.showPaperBadge());
        getConfig().set("defaults.small-caps", s.smallCaps());
        getConfig().set("defaults.small-caps-names", s.smallCapsNames());
        saveConfig();
        ThemeProfile theme = loadTheme(s.themeKey());
        player.sendMessage(Component.text()
                .append(Component.text("✔ ", color(theme.enabled())))
                .append(Component.text("settings saved", color(theme.accent())))
                .build());
        player.closeInventory();
        render(player, s);
    }

    private ViewSettings applyGuiClick(int slot, ViewSettings s) {
        return switch (slot) {
            case 1  -> withStyle(s, "compact");
            case 2  -> withStyle(s, "grouped");
            case 3  -> withStyle(s, "boxed");
            case 4  -> withStyle(s, "categorized");
            case 5  -> withStyle(s, "detailed");
            case 10 -> withSort(s, "alpha");
            case 11 -> withSort(s, "alpha-desc");
            case 12 -> withSort(s, "enabled-first");
            case 13 -> withSort(s, "disabled-first");
            case 14 -> withSort(s, "paper-first");
            case 19 -> withTheme(s, "gold");
            case 20 -> withTheme(s, "crimson");
            case 21 -> withTheme(s, "emerald");
            case 22 -> withTheme(s, "sunset");
            case 23 -> withTheme(s, "rainbow");
            case 24 -> withTheme(s, "ocean");
            case 25 -> withTheme(s, "galaxy");
            case 26 -> withTheme(s, "cherry");
            case 27 -> withTheme(s, "arctic");
            case 28 -> withTheme(s, "neon");
            case 37 -> withGradients(s, !s.gradients());
            case 38 -> withVersions(s, !s.showVersions());
            case 39 -> withLegend(s, !s.showLegend());
            case 40 -> withBadge(s, !s.showPaperBadge());
            case 41 -> withSmallCaps(s, !s.smallCaps());
            case 42 -> withSmallCapsNames(s, !s.smallCapsNames());
            default -> null;
        };
    }

    // ── GUI item builders ─────────────────────────────────────────────────────

    private ItemStack fillerPane() {
        return makeItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
    }

    private ItemStack labelPane(String label, ThemeProfile theme) {
        return makeItem(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                Component.text(label, color(theme.muted())).decorate(TextDecoration.BOLD), List.of());
    }

    private ItemStack styleItem(ViewStyle style, ViewSettings temp, ThemeProfile theme) {
        boolean selected = temp.style() == style;
        List<Component> lore = new ArrayList<>();
        lore.add(styleDescription(style, theme));
        if (selected) lore.add(Component.text("✔ current selection", color(theme.enabled())));
        ItemStack item = makeItem(Material.PAPER,
                Component.text(style.key, selected ? color(theme.accent()) : color(theme.muted())), lore);
        if (selected) setGlint(item);
        return item;
    }

    private ItemStack sortItem(SortMode mode, ViewSettings temp, ThemeProfile theme) {
        boolean selected = temp.sortMode() == mode;
        List<Component> lore = new ArrayList<>();
        lore.add(sortDescription(mode, theme));
        if (selected) lore.add(Component.text("✔ current selection", color(theme.enabled())));
        ItemStack item = makeItem(Material.COMPASS,
                Component.text(mode.key, selected ? color(theme.accent()) : color(theme.muted())), lore);
        if (selected) setGlint(item);
        return item;
    }

    private ItemStack themeItem(String themeKey, ViewSettings temp, ThemeProfile currentTheme) {
        ThemeProfile tp = loadTheme(themeKey);
        boolean selected = temp.themeKey().equals(themeKey);
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("█ enabled  ", color(tp.enabled()))
                .append(Component.text("█ disabled", color(tp.disabled()))));
        lore.add(Component.text("█ accent   ", color(tp.accent()))
                .append(Component.text("█ header", color(tp.headerStart()))));
        if (selected) lore.add(Component.text("✔ current selection", color(currentTheme.enabled())));
        ItemStack item = makeItem(themeGlassMaterial(themeKey),
                Component.text(themeKey, color(tp.accent())), lore);
        if (selected) setGlint(item);
        return item;
    }

    private ItemStack toggleItem(String displayName, boolean value, ThemeProfile theme) {
        Material mat = value ? Material.LIME_CONCRETE : Material.RED_CONCRETE;
        String state = value ? "on" : "off";
        List<Component> lore = List.of(
                Component.text("currently: " + state, value ? color(theme.enabled()) : color(theme.disabled())),
                Component.text("click to toggle", color(theme.muted())));
        return makeItem(mat,
                Component.text(displayName, value ? color(theme.enabled()) : color(theme.disabled())), lore);
    }

    private ItemStack applyButton(ThemeProfile theme) {
        return makeItem(Material.NETHER_STAR,
                Component.text("◈ Apply & Save", color(theme.enabled())),
                List.of(Component.text("save all settings as defaults", color(theme.muted()))));
    }

    private ItemStack closeButton(ThemeProfile theme) {
        return makeItem(Material.BARRIER,
                Component.text("✕ Close", color(theme.disabled())),
                List.of(Component.text("discard unsaved changes", color(theme.muted()))));
    }

    private ItemStack makeItem(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        if (!lore.isEmpty()) meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        item.setItemMeta(meta);
        return item;
    }

    private void setGlint(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
    }

    private Component styleDescription(ViewStyle style, ThemeProfile theme) {
        String desc = switch (style) {
            case COMPACT      -> "10 plugins per line, comma-separated";
            case GROUPED      -> "enabled / disabled sections";
            case BOXED        -> "one plugin per line with ▸ prefix";
            case CATEGORIZED  -> "paper vs bukkit categories";
            case DETAILED     -> "full info: version, authors, badges";
        };
        return Component.text(desc, color(theme.muted()));
    }

    private Component sortDescription(SortMode mode, ThemeProfile theme) {
        String desc = switch (mode) {
            case ALPHA          -> "a → z alphabetical";
            case ALPHA_DESC     -> "z → a alphabetical";
            case ENABLED_FIRST  -> "enabled plugins first";
            case DISABLED_FIRST -> "disabled plugins first";
            case PAPER_FIRST    -> "paper plugins first";
        };
        return Component.text(desc, color(theme.muted()));
    }

    private Material themeGlassMaterial(String key) {
        return switch (key) {
            case "gold"    -> Material.YELLOW_STAINED_GLASS_PANE;
            case "crimson" -> Material.RED_STAINED_GLASS_PANE;
            case "emerald" -> Material.GREEN_STAINED_GLASS_PANE;
            case "sunset"  -> Material.ORANGE_STAINED_GLASS_PANE;
            case "rainbow" -> Material.MAGENTA_STAINED_GLASS_PANE;
            case "ocean"   -> Material.BLUE_STAINED_GLASS_PANE;
            case "galaxy"  -> Material.PURPLE_STAINED_GLASS_PANE;
            case "cherry"  -> Material.PINK_STAINED_GLASS_PANE;
            case "arctic"  -> Material.CYAN_STAINED_GLASS_PANE;
            case "neon"    -> Material.LIME_STAINED_GLASS_PANE;
            default        -> Material.WHITE_STAINED_GLASS_PANE;
        };
    }

    // ── Config / theme loading ────────────────────────────────────────────────

    private ViewSettings readSettingsFromConfig() {
        return new ViewSettings(
                ViewStyle.from(getConfig().getString("defaults.style", "grouped")),
                SortMode.from(getConfig().getString("defaults.sort", "enabled-first")),
                normalizeTheme(getConfig().getString("defaults.theme", "gold")),
                getConfig().getBoolean("defaults.gradients", true),
                getConfig().getBoolean("defaults.show-versions", false),
                getConfig().getBoolean("defaults.show-legend", true),
                getConfig().getBoolean("defaults.show-paper-badge", true),
                getConfig().getBoolean("defaults.small-caps", true),
                getConfig().getBoolean("defaults.small-caps-names", false)
        );
    }

    private ThemeProfile loadTheme(String key) {
        String themeKey = normalizeTheme(key);
        ConfigurationSection section = getConfig().getConfigurationSection("themes." + themeKey);
        if (section == null) {
            section = Objects.requireNonNull(getConfig().getConfigurationSection("themes.gold"));
        }

        return new ThemeProfile(
                themeKey,
                section.getString("header-mode", "gradient"),
                section.getString("header-start", "#f7d774"),
                section.getString("header-end", "#c8922d"),
                section.getString("enabled", "#f3d27a"),
                section.getString("disabled", "#ff6b81"),
                section.getString("accent", "#e9c46a"),
                section.getString("muted", "#8a8f98"),
                section.getString("warning", "#ffd166"),
                section.getString("paper-badge", "#00b4d8")
        );
    }

    private String normalizeTheme(String input) {
        if (input == null) {
            return "gold";
        }

        String lowered = input.toLowerCase(Locale.ROOT);
        return THEMES.contains(lowered) ? lowered : "gold";
    }

    // ── Error display ─────────────────────────────────────────────────────────

    private void sendLastError(CommandSender sender, Plugin plugin) {
        File logFile = new File("logs/latest.log");
        if (!logFile.exists()) {
            sender.sendRichMessage("<red>Could not find logs/latest.log.</red>");
            return;
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(logFile.toPath());
        } catch (IOException e) {
            sender.sendRichMessage("<red>Failed to read log file.</red>");
            return;
        }

        String pluginName = plugin.getName();
        int errorStart = -1;
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            if (containsPluginError(line, pluginName)) {
                errorStart = i;
                for (int j = i - 1; j >= Math.max(0, i - 30); j--) {
                    String prev = lines.get(j);
                    if (isLogHeader(prev) && looksLikeError(prev)) {
                        errorStart = j;
                        break;
                    }
                    if (isLogHeader(prev)) {
                        break;
                    }
                    errorStart = j;
                }
                break;
            }
        }

        if (errorStart == -1) {
            sender.sendRichMessage("<yellow>No recent error found in the log for <white>" + pluginName + "</white>.</yellow>");
            return;
        }

        int maxLines = 10;
        List<String> block = new ArrayList<>();
        for (int i = errorStart; i < lines.size() && block.size() < maxLines; i++) {
            block.add(stripLogPrefix(lines.get(i)));
        }

        sender.sendRichMessage("<dark_gray>━━━━━━━━ </dark_gray><red><bold>last error</bold></red><dark_gray> · </dark_gray><white>" + pluginName + "</white><dark_gray> ━━━━━━━━</dark_gray>");
        for (String l : block) {
            sender.sendMessage(Component.text(l, NamedTextColor.RED));
        }
        sender.sendRichMessage("<dark_gray>(showing up to " + maxLines + " lines from logs/latest.log)</dark_gray>");
    }

    private boolean containsPluginError(String line, String pluginName) {
        String lower = line.toLowerCase(Locale.ROOT);
        String pluginLower = pluginName.toLowerCase(Locale.ROOT);
        return (lower.contains("error") || lower.contains("exception") || lower.contains("warn"))
                && lower.contains(pluginLower);
    }

    private boolean isLogHeader(String line) {
        return line.matches("^\\[\\d{2}:\\d{2}:\\d{2}\\].*");
    }

    private boolean looksLikeError(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("error") || lower.contains("exception") || lower.contains("warn") || lower.contains("severe");
    }

    private String stripLogPrefix(String line) {
        return line.replaceFirst("^\\[\\d{2}:\\d{2}:\\d{2}\\] \\[[A-Za-z]+\\]: ", "");
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private boolean isPaperPlugin(Plugin plugin) {
        try {
            Class<?> metaClass = plugin.getPluginMeta().getClass();
            String className = metaClass.getName();
            return !className.equals("org.bukkit.plugin.PluginDescriptionFile");
        } catch (NoSuchMethodError | NoClassDefFoundError | Exception e) {
            return false;
        }
    }

    private boolean isLegacy(PluginDescriptionFile description) {
        String apiVersion = description.getAPIVersion();
        return apiVersion == null || apiVersion.isBlank();
    }

    private TextColor color(String hex) {
        TextColor color = TextColor.fromHexString(hex);
        return color != null ? color : NamedTextColor.WHITE;
    }

    private boolean shouldOverride(String raw) {
        String cleaned = raw.startsWith("/") ? raw.substring(1) : raw;
        String root = cleaned.split(" ")[0].toLowerCase(Locale.ROOT);

        return root.equals("plugins")
                || root.equals("pl")
                || root.equals("bukkit:plugins");
    }

    private String extractRemainder(String raw) {
        String cleaned = raw.startsWith("/") ? raw.substring(1) : raw;
        int index = cleaned.indexOf(' ');
        if (index == -1) {
            return "";
        }
        return cleaned.substring(index + 1).trim();
    }

    private String escape(String input) {
        return input
                .replace("\\", "\\\\")
                .replace("<", "\\<");
    }

    // ── Book Preview ─────────────────────────────────────────────────────────

    private void openBookPreview(Player player, ViewSettings settings) {
        ThemeProfile theme = loadTheme(settings.themeKey());
        List<Plugin> plugins = new ArrayList<>(Arrays.asList(Bukkit.getPluginManager().getPlugins()));
        plugins.sort(comparator(settings.sortMode()));

        long enabledCount  = plugins.stream().filter(Plugin::isEnabled).count();
        long disabledCount = plugins.size() - enabledCount;
        long paperCount    = plugins.stream().filter(this::isPaperPlugin).count();

        List<Component> pages = new ArrayList<>();
        pages.add(buildBookSummaryPage(settings, theme, plugins.size(), enabledCount, disabledCount, paperCount));

        switch (settings.style()) {
            case COMPACT, GROUPED -> buildBookGroupedPages(pages, plugins, theme);
            case BOXED             -> buildBookListPages(pages, plugins, null, null, theme);
            case CATEGORIZED       -> buildBookCategorizedPages(pages, plugins, theme);
            case DETAILED          -> buildBookDetailedPages(pages, plugins, theme);
        }

        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.title(Component.text("Plugin List"));
        meta.author(Component.text("PluginListPlus"));
        meta.addPages(pages.toArray(new Component[0]));
        book.setItemMeta(meta);

        player.openBook(book);
    }

    private Component buildBookSummaryPage(ViewSettings settings, ThemeProfile theme,
                                            int total, long enabled, long disabled, long paper) {
        return Component.text()
                .append(Component.text("Plugin List\n", color(theme.accent())).decorate(TextDecoration.BOLD))
                .append(Component.text("─────────────\n", NamedTextColor.DARK_GRAY))
                .append(bookKv("style",  settings.style().key,    theme))
                .append(bookKv("sort",   settings.sortMode().key, theme))
                .append(bookKv("theme",  settings.themeKey(),     theme))
                .append(Component.newline())
                .append(bookKv("total",   Integer.toString(total), theme))
                .append(Component.text("enabled: ",  color(theme.muted())))
                .append(Component.text(enabled   + "\n", color(theme.enabled())))
                .append(Component.text("disabled: ", color(theme.muted())))
                .append(Component.text(disabled  + "\n", color(theme.disabled())))
                .append(Component.text("paper: ",    color(theme.muted())))
                .append(Component.text(paper     + "\n", color(theme.paperBadge())))
                .build();
    }

    private Component bookKv(String label, String value, ThemeProfile theme) {
        return Component.text(label + ": ", color(theme.muted()))
                .append(Component.text(value + "\n", color(theme.accent())));
    }

    private void buildBookGroupedPages(List<Component> pages, List<Plugin> plugins, ThemeProfile theme) {
        List<Plugin> enabled  = plugins.stream().filter(Plugin::isEnabled).collect(Collectors.toList());
        List<Plugin> disabled = plugins.stream().filter(p -> !p.isEnabled()).collect(Collectors.toList());
        if (!enabled.isEmpty())  buildBookListPages(pages, enabled,  "enabled",  color(theme.enabled()),  theme);
        if (!disabled.isEmpty()) buildBookListPages(pages, disabled, "disabled", color(theme.disabled()), theme);
    }

    private void buildBookCategorizedPages(List<Component> pages, List<Plugin> plugins, ThemeProfile theme) {
        List<Plugin> pEn  = plugins.stream().filter(p -> isPaperPlugin(p) && p.isEnabled()).collect(Collectors.toList());
        List<Plugin> pDis = plugins.stream().filter(p -> isPaperPlugin(p) && !p.isEnabled()).collect(Collectors.toList());
        List<Plugin> bEn  = plugins.stream().filter(p -> !isPaperPlugin(p) && p.isEnabled()).collect(Collectors.toList());
        List<Plugin> bDis = plugins.stream().filter(p -> !isPaperPlugin(p) && !p.isEnabled()).collect(Collectors.toList());
        if (!pEn.isEmpty())  buildBookListPages(pages, pEn,  "[P] enabled",  color(theme.enabled()),  theme);
        if (!pDis.isEmpty()) buildBookListPages(pages, pDis, "[P] disabled", color(theme.disabled()), theme);
        if (!bEn.isEmpty())  buildBookListPages(pages, bEn,  "[B] enabled",  color(theme.enabled()),  theme);
        if (!bDis.isEmpty()) buildBookListPages(pages, bDis, "[B] disabled", color(theme.disabled()), theme);
    }

    private void buildBookDetailedPages(List<Component> pages, List<Plugin> plugins, ThemeProfile theme) {
        int perPage = 5;
        for (int start = 0; start < plugins.size(); start += perPage) {
            TextComponent.Builder page = Component.text();
            int end = Math.min(start + perPage, plugins.size());
            for (int i = start; i < end; i++) {
                Plugin p = plugins.get(i);
                PluginDescriptionFile desc = p.getDescription();
                page.append(Component.text(p.getName(), color(p.isEnabled() ? theme.enabled() : theme.disabled()))
                        .decorate(TextDecoration.BOLD)
                        .clickEvent(p.isEnabled()
                                ? ClickEvent.runCommand("/version " + p.getName())
                                : ClickEvent.runCommand("/pluginlist error " + p.getName()))
                        .hoverEvent(HoverEvent.showText(Component.text(
                                p.isEnabled() ? "click: /version" : "click: show error", color(theme.muted())))));
                page.append(Component.newline());
                page.append(Component.text("  v" + desc.getVersion(), color(theme.muted())));
                if (!desc.getAuthors().isEmpty()) {
                    page.append(Component.text(" by " + String.join(", ", desc.getAuthors()), color(theme.accent())));
                }
                page.append(Component.newline());
                if (i < end - 1) page.append(Component.newline());
            }
            pages.add(page.build());
        }
    }

    private void buildBookListPages(List<Component> pages, List<Plugin> plugins,
                                     String header, TextColor headerColor, ThemeProfile theme) {
        int perPage = 12;
        for (int start = 0; start < plugins.size(); start += perPage) {
            TextComponent.Builder page = Component.text();
            if (header != null && start == 0) {
                page.append(Component.text(header + "\n",
                        headerColor != null ? headerColor : color(theme.accent())).decorate(TextDecoration.BOLD));
                page.append(Component.text("─────────────\n", NamedTextColor.DARK_GRAY));
            }
            int end = Math.min(start + perPage, plugins.size());
            for (int i = start; i < end; i++) {
                Plugin p = plugins.get(i);
                page.append(Component.text("▸ ", NamedTextColor.DARK_GRAY));
                page.append(Component.text(p.getName(), color(p.isEnabled() ? theme.enabled() : theme.disabled()))
                        .clickEvent(p.isEnabled()
                                ? ClickEvent.runCommand("/version " + p.getName())
                                : ClickEvent.runCommand("/pluginlist error " + p.getName()))
                        .hoverEvent(HoverEvent.showText(Component.text(
                                p.isEnabled() ? "click: /version" : "click: show error", color(theme.muted())))));
                page.append(Component.newline());
            }
            pages.add(page.build());
        }
    }

    // ── Plugin Browser ────────────────────────────────────────────────────────

    private static final int BROWSER_PLUGINS_PER_PAGE = 45;
    private static final int BROWSER_SLOT_PREV  = 45;
    private static final int BROWSER_SLOT_PAGE  = 49;
    private static final int BROWSER_SLOT_NEXT  = 50;
    private static final int BROWSER_SLOT_CLOSE = 53;

    private void openBrowserGui(Player player, ViewSettings settings, int page) {
        List<Plugin> plugins = new ArrayList<>(Arrays.asList(Bukkit.getPluginManager().getPlugins()));
        plugins.sort(comparator(settings.sortMode()));
        int totalPages = Math.max(1, (plugins.size() + BROWSER_PLUGINS_PER_PAGE - 1) / BROWSER_PLUGINS_PER_PAGE);
        page = Math.max(0, Math.min(page, totalPages - 1));
        browserSessions.put(player.getUniqueId(), new BrowserSession(settings, page));
        Inventory inv = buildBrowserGui(plugins, settings, page, totalPages);
        openBrowsers.put(player.getUniqueId(), inv);
        player.openInventory(inv);
    }

    private Inventory buildBrowserGui(List<Plugin> plugins, ViewSettings settings, int page, int totalPages) {
        ThemeProfile theme = loadTheme(settings.themeKey());
        Inventory inv = Bukkit.createInventory(null, 54,
                Component.text("☰ plugins  •  " + settings.sortMode().key + "  •  " + (page + 1) + "/" + totalPages,
                        color(theme.muted())));

        ItemStack filler = fillerPane();
        for (int i = 0; i < 54; i++) inv.setItem(i, filler);

        int start = page * BROWSER_PLUGINS_PER_PAGE;
        int end   = Math.min(start + BROWSER_PLUGINS_PER_PAGE, plugins.size());
        for (int i = start; i < end; i++) {
            inv.setItem(i - start, browserPluginItem(plugins.get(i), theme));
        }

        if (page > 0) {
            inv.setItem(BROWSER_SLOT_PREV, makeItem(Material.ARROW,
                    Component.text("◄ Previous", color(theme.accent())),
                    List.of(Component.text("page " + page + " / " + totalPages, color(theme.muted())))));
        }
        inv.setItem(BROWSER_SLOT_PAGE, makeItem(Material.PAPER,
                Component.text("page " + (page + 1) + " / " + totalPages, color(theme.accent())),
                List.of(Component.text(plugins.size() + " plugins total", color(theme.muted())))));
        if (page < totalPages - 1) {
            inv.setItem(BROWSER_SLOT_NEXT, makeItem(Material.ARROW,
                    Component.text("Next ►", color(theme.accent())),
                    List.of(Component.text("page " + (page + 2) + " / " + totalPages, color(theme.muted())))));
        }
        inv.setItem(BROWSER_SLOT_CLOSE, closeButton(theme));

        return inv;
    }

    private ItemStack browserPluginItem(Plugin plugin, ThemeProfile theme) {
        boolean isPaper = isPaperPlugin(plugin);
        boolean enabled = plugin.isEnabled();
        Material mat;
        if      (isPaper && enabled)  mat = Material.CYAN_STAINED_GLASS_PANE;
        else if (isPaper)             mat = Material.ORANGE_STAINED_GLASS_PANE;
        else if (enabled)             mat = Material.LIME_STAINED_GLASS_PANE;
        else                          mat = Material.RED_STAINED_GLASS_PANE;

        PluginDescriptionFile desc = plugin.getDescription();
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(enabled ? "● enabled" : "○ disabled",
                enabled ? color(theme.enabled()) : color(theme.disabled())));
        lore.add(Component.text("v" + desc.getVersion(), color(theme.muted())));
        if (!desc.getAuthors().isEmpty()) {
            lore.add(Component.text("by " + String.join(", ", desc.getAuthors()), color(theme.accent())));
        }
        String pluginDesc = desc.getDescription();
        if (pluginDesc != null && !pluginDesc.isBlank()) {
            String trimmed = pluginDesc.length() > 40 ? pluginDesc.substring(0, 37) + "…" : pluginDesc;
            lore.add(Component.text(trimmed, color(theme.muted())));
        }
        if (isPaper) lore.add(Component.text("[P] Paper plugin", color(theme.paperBadge())));
        lore.add(Component.text(enabled ? "click: /version " + plugin.getName()
                                         : "click: show last error", color(theme.warning())));

        ItemStack item = makeItem(mat,
                Component.text(plugin.getName(), enabled ? color(theme.enabled()) : color(theme.disabled())), lore);
        if (isPaper && enabled) setGlint(item);
        return item;
    }

    private void handleBrowserClick(Player player, int slot) {
        BrowserSession session = browserSessions.get(player.getUniqueId());
        if (session == null) return;

        List<Plugin> plugins = new ArrayList<>(Arrays.asList(Bukkit.getPluginManager().getPlugins()));
        plugins.sort(comparator(session.settings().sortMode()));
        int totalPages = Math.max(1, (plugins.size() + BROWSER_PLUGINS_PER_PAGE - 1) / BROWSER_PLUGINS_PER_PAGE);

        if (slot == BROWSER_SLOT_CLOSE) { player.closeInventory(); return; }

        if (slot == BROWSER_SLOT_PREV && session.page() > 0) {
            navigateBrowser(player, session.settings(), session.page() - 1, plugins, totalPages);
            return;
        }
        if (slot == BROWSER_SLOT_NEXT && session.page() < totalPages - 1) {
            navigateBrowser(player, session.settings(), session.page() + 1, plugins, totalPages);
            return;
        }

        if (slot < BROWSER_PLUGINS_PER_PAGE) {
            int idx = session.page() * BROWSER_PLUGINS_PER_PAGE + slot;
            if (idx >= plugins.size()) return;
            Plugin p = plugins.get(idx);
            player.closeInventory();
            if (p.isEnabled()) {
                player.performCommand("version " + p.getName());
            } else {
                player.performCommand("pluginlist error " + p.getName());
            }
        }
    }

    private void navigateBrowser(Player player, ViewSettings settings, int newPage,
                                   List<Plugin> plugins, int totalPages) {
        browserSessions.put(player.getUniqueId(), new BrowserSession(settings, newPage));
        Inventory existing = openBrowsers.get(player.getUniqueId());
        if (existing == null) return;
        Inventory fresh = buildBrowserGui(plugins, settings, newPage, totalPages);
        for (int i = 0; i < 54; i++) existing.setItem(i, fresh.getItem(i));
    }

    private record BrowserSession(ViewSettings settings, int page) {}

    // ── Enums and records ─────────────────────────────────────────────────────

    private enum ViewStyle {
        COMPACT("compact"),
        GROUPED("grouped"),
        BOXED("boxed"),
        CATEGORIZED("categorized"),
        DETAILED("detailed");

        private final String key;

        ViewStyle(String key) {
            this.key = key;
        }

        private static ViewStyle from(String input) {
            if (input == null) {
                return GROUPED;
            }

            String lowered = input.toLowerCase(Locale.ROOT);
            for (ViewStyle style : values()) {
                if (style.key.equals(lowered)) {
                    return style;
                }
            }
            return GROUPED;
        }
    }

    private enum SortMode {
        ALPHA("alpha"),
        ALPHA_DESC("alpha-desc"),
        ENABLED_FIRST("enabled-first"),
        DISABLED_FIRST("disabled-first"),
        PAPER_FIRST("paper-first");

        private final String key;

        SortMode(String key) {
            this.key = key;
        }

        private static SortMode from(String input) {
            if (input == null) {
                return ENABLED_FIRST;
            }

            String lowered = input.toLowerCase(Locale.ROOT);
            for (SortMode mode : values()) {
                if (mode.key.equals(lowered)) {
                    return mode;
                }
            }
            return ENABLED_FIRST;
        }
    }

    private record ViewSettings(
            ViewStyle style,
            SortMode sortMode,
            String themeKey,
            boolean gradients,
            boolean showVersions,
            boolean showLegend,
            boolean showPaperBadge,
            boolean smallCaps,
            boolean smallCapsNames
    ) { }

    private record ThemeProfile(
            String key,
            String headerMode,
            String headerStart,
            String headerEnd,
            String enabled,
            String disabled,
            String accent,
            String muted,
            String warning,
            String paperBadge
    ) { }
}
