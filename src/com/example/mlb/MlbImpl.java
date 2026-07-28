package com.example.mlb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.sun.star.lang.Locale;
import com.sun.star.lang.XSingleComponentFactory;
import com.sun.star.lib.uno.helper.Factory;
import com.sun.star.lib.uno.helper.WeakBase;
import com.sun.star.registry.XRegistryKey;
import com.sun.star.uno.Any;

/**
 * LibreOffice Calc add-in exposing balldontlie MLB worksheet functions.
 *
 * <p>Implements the custom {@link XMlb} interface plus the standard add-in
 * plumbing ({@code com.sun.star.sheet.XAddIn}, {@code XServiceName},
 * {@code XServiceInfo}). Function display names, descriptions and
 * per-argument help live in config/CalcAddIns.xcu; the {@code XAddIn}
 * accessors below return the programmatic names as a safe fallback.
 *
 * <p>Cell functions here never block and never throw. Every function
 * resolves through {@link MlbCache} (see that class for the non-blocking
 * cache + background-fetch pattern) and returns either the requested data or
 * one of the sentinel strings documented on {@link XMlb}.
 */
public final class MlbImpl extends WeakBase
        implements XMlb,
                   com.sun.star.sheet.XAddIn,
                   com.sun.star.lang.XServiceName,
                   com.sun.star.lang.XServiceInfo {

    /** Implementation name: must match the AddInInfo node in CalcAddIns.xcu. */
    private static final String IMPLEMENTATION_NAME = "com.example.mlb.MlbImpl";

    /** The one service that marks this component as a Calc add-in. */
    private static final String ADDIN_SERVICE = "com.sun.star.sheet.AddIn";

    private static final String[] SERVICE_NAMES = { ADDIN_SERVICE, IMPLEMENTATION_NAME };

    /** Current locale (tracked for XLocalizable; metadata is English-only here). */
    private Locale locale = new Locale("en", "US", "");

    // ------------------------------------------------------------------ //
    // Sentinels + cache TTLs                                             //
    // ------------------------------------------------------------------ //

    private static final String FETCHING = "#FETCHING";
    private static final String NO_API_KEY = "#NO_API_KEY";
    private static final String NOT_FOUND = "#NOT_FOUND";
    private static final String TIER = "#TIER";
    private static final String RATE_LIMIT = "#RATE_LIMIT";
    private static final String ERR = "#ERR";

    /** Normal (non-sentinel) status returned by {@link #status}. */
    private static final String READY = MlbCache.Result.READY;

    private static final long TTL_TEAMS = 24L * 3600 * 1000;        // 24h
    private static final long TTL_PLAYERS = 6L * 3600 * 1000;       // 6h
    private static final long TTL_GAMES = 5L * 60 * 1000;           // 5min
    private static final long TTL_STANDINGS = 3600L * 1000;         // 1h
    private static final long TTL_SEASON_STATS = 3600L * 1000;      // 1h
    private static final long TTL_STATS = 5L * 60 * 1000;           // 5min

    // ------------------------------------------------------------------ //
    // XMlb - the actual worksheet functions                              //
    // ------------------------------------------------------------------ //

    public Object[][] mlbTeams(Object leagueArg, Object divisionArg, Object apiKeyArg) {
        final String apiKey = MlbClient.resolveApiKey(optString(apiKeyArg));
        if (apiKey == null) return sentinelTable(NO_API_KEY);
        final String league = optString(leagueArg);
        final String division = optString(divisionArg);

        MlbCache.Result r = MlbCache.get(teamsKey(apiKey), TTL_TEAMS, new MlbCache.Fetcher() {
            public Object fetch() throws Exception { return MlbClient.teams(apiKey); }
        });
        String st = status(r);
        if (!READY.equals(st)) return sentinelTable(st);

        List<Map<String, Object>> filtered = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> t : cast(r.data)) {
            if (league != null && !league.equalsIgnoreCase(str(t.get("league")))) continue;
            if (division != null && !division.equalsIgnoreCase(str(t.get("division")))) continue;
            filtered.add(t);
        }
        if (filtered.isEmpty()) return sentinelTable(NOT_FOUND);

        Object[][] out = new Object[filtered.size()][6];
        for (int i = 0; i < filtered.size(); i++) {
            Map<String, Object> t = filtered.get(i);
            out[i][0] = t.get("id");
            out[i][1] = str(t.get("abbreviation"));
            out[i][2] = str(t.get("display_name"));
            out[i][3] = str(t.get("location"));
            out[i][4] = str(t.get("league"));
            out[i][5] = str(t.get("division"));
        }
        return out;
    }

    public Object mlbTeam(Object teamIdArg, String field, Object apiKeyArg) {
        final String apiKey = MlbClient.resolveApiKey(optString(apiKeyArg));
        if (apiKey == null) return NO_API_KEY;
        final Long teamId = toLong(optDouble(teamIdArg));
        final String fieldTrim = trim(field);
        if (teamId == null || fieldTrim.isEmpty()) return NOT_FOUND;

        MlbCache.Result r = MlbCache.get(teamsKey(apiKey), TTL_TEAMS, new MlbCache.Fetcher() {
            public Object fetch() throws Exception { return MlbClient.teams(apiKey); }
        });
        String st = status(r);
        if (!READY.equals(st)) return st;

        for (Map<String, Object> t : cast(r.data)) {
            if (teamId.equals(toLong(t.get("id")))) {
                Object v = getPath(t, fieldTrim);
                return v == null ? NOT_FOUND : v;
            }
        }
        return NOT_FOUND;
    }

    public Object[][] mlbPlayerSearch(String searchText, Object maxRowsArg, Object apiKeyArg) {
        final String apiKey = MlbClient.resolveApiKey(optString(apiKeyArg));
        if (apiKey == null) return sentinelTable(NO_API_KEY);
        final String query = trim(searchText);
        if (query.isEmpty()) return sentinelTable(NOT_FOUND);
        String key = playersSearchKey(query, apiKey);

        MlbCache.Result r = MlbCache.get(key, TTL_PLAYERS, new MlbCache.Fetcher() {
            public Object fetch() throws Exception { return MlbClient.playersSearch(query, apiKey); }
        });
        String st = status(r);
        if (!READY.equals(st)) return sentinelTable(st);

        List<Map<String, Object>> players = cast(r.data);
        if (players.isEmpty()) return sentinelTable(NOT_FOUND);

        Double maxRowsNum = optDouble(maxRowsArg);
        int maxRows = maxRowsNum == null ? 100 : Math.max(1, maxRowsNum.intValue());
        int n = Math.min(players.size(), maxRows);

        Object[][] out = new Object[n][5];
        for (int i = 0; i < n; i++) {
            Map<String, Object> p = players.get(i);
            out[i][0] = p.get("id");
            String full = str(p.get("full_name"));
            if (full.isEmpty()) full = (str(p.get("first_name")) + " " + str(p.get("last_name"))).trim();
            out[i][1] = full;
            out[i][2] = str(p.get("position"));
            out[i][3] = str(nested(p, "team").get("abbreviation"));
            out[i][4] = p.get("active");
        }
        return out;
    }

    public Object mlbPlayer(Object playerIdArg, String field, Object apiKeyArg) {
        final String apiKey = MlbClient.resolveApiKey(optString(apiKeyArg));
        if (apiKey == null) return NO_API_KEY;
        Double idNum = optDouble(playerIdArg);
        final String fieldTrim = trim(field);
        if (idNum == null || fieldTrim.isEmpty()) return NOT_FOUND;
        final String playerId = fmtNum(idNum);
        String key = playerByIdKey(playerId, apiKey);

        MlbCache.Result r = MlbCache.get(key, TTL_PLAYERS, new MlbCache.Fetcher() {
            public Object fetch() throws Exception { return MlbClient.playerById(playerId, apiKey); }
        });
        String st = status(r);
        if (!READY.equals(st)) return st;

        List<Map<String, Object>> players = cast(r.data);
        if (players.isEmpty()) return NOT_FOUND;
        Object v = getPath(players.get(0), fieldTrim);
        return v == null ? NOT_FOUND : v;
    }

    public Object[][] mlbGames(String dateOrSeason, Object teamIdArg, Object maxRowsArg, Object apiKeyArg) {
        final String apiKey = MlbClient.resolveApiKey(optString(apiKeyArg));
        if (apiKey == null) return sentinelTable(NO_API_KEY);
        final String ds = trim(dateOrSeason);
        if (ds.isEmpty()) return sentinelTable(NOT_FOUND);
        final boolean isDate = ds.matches("\\d{4}-\\d{2}-\\d{2}");
        final Long teamId = toLong(optDouble(teamIdArg));

        String key = (isDate ? "games:date=" : "games:season=") + ds
                + (teamId != null ? ";team=" + teamId : "") + ";k=" + keyTag(apiKey);

        MlbCache.Result r = MlbCache.get(key, TTL_GAMES, new MlbCache.Fetcher() {
            public Object fetch() throws Exception {
                return isDate ? MlbClient.gamesByDate(ds, teamId, apiKey) : MlbClient.gamesBySeason(ds, teamId, apiKey);
            }
        });
        String st = status(r);
        if (!READY.equals(st)) return sentinelTable(st);

        List<Map<String, Object>> games = cast(r.data);
        if (games.isEmpty()) return sentinelTable(NOT_FOUND);

        Double maxRowsNum = optDouble(maxRowsArg);
        int maxRows = maxRowsNum == null ? games.size() : Math.max(1, maxRowsNum.intValue());
        int n = Math.min(games.size(), maxRows);

        Object[][] out = new Object[n][8];
        for (int i = 0; i < n; i++) {
            Map<String, Object> g = games.get(i);
            out[i][0] = g.get("id");
            out[i][1] = dateOnly(g.get("date"));
            out[i][2] = str(g.get("away_team_name"));
            out[i][3] = getPath(g, "away_team_data.runs");
            out[i][4] = str(g.get("home_team_name"));
            out[i][5] = getPath(g, "home_team_data.runs");
            out[i][6] = str(g.get("status"));
            out[i][7] = str(g.get("season_type"));
        }
        return out;
    }

    public Object mlbGame(Object gameIdArg, String field, Object apiKeyArg) {
        final String apiKey = MlbClient.resolveApiKey(optString(apiKeyArg));
        if (apiKey == null) return NO_API_KEY;
        Double idNum = optDouble(gameIdArg);
        final String fieldTrim = trim(field);
        if (idNum == null || fieldTrim.isEmpty()) return NOT_FOUND;
        final String gameId = fmtNum(idNum);
        String key = gameByIdKey(gameId, apiKey);

        MlbCache.Result r = MlbCache.get(key, TTL_GAMES, new MlbCache.Fetcher() {
            public Object fetch() throws Exception { return MlbClient.gameById(gameId, apiKey); }
        });
        String st = status(r);
        if (!READY.equals(st)) return st;

        List<Map<String, Object>> games = cast(r.data);
        if (games.isEmpty()) return NOT_FOUND;
        Object v = getPath(games.get(0), fieldTrim);
        return v == null ? NOT_FOUND : v;
    }

    public Object[][] mlbStandings(String season, Object apiKeyArg) {
        final String apiKey = MlbClient.resolveApiKey(optString(apiKeyArg));
        if (apiKey == null) return sentinelTable(NO_API_KEY);
        final String seasonTrim = trim(season);
        if (seasonTrim.isEmpty()) return sentinelTable(NOT_FOUND);
        String key = standingsKey(seasonTrim, apiKey);

        MlbCache.Result r = MlbCache.get(key, TTL_STANDINGS, new MlbCache.Fetcher() {
            public Object fetch() throws Exception { return MlbClient.standings(seasonTrim, apiKey); }
        });
        String st = status(r);
        if (!READY.equals(st)) return sentinelTable(st);

        List<Map<String, Object>> rows = cast(r.data);
        if (rows.isEmpty()) return sentinelTable(NOT_FOUND);

        Object[][] out = new Object[rows.size()][8];
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            Map<String, Object> team = nested(row, "team");
            String teamLabel = str(team.get("abbreviation"));
            if (teamLabel.isEmpty()) teamLabel = str(team.get("name"));
            out[i][0] = teamLabel;
            out[i][1] = str(row.get("league_name"));
            out[i][2] = str(row.get("division_name"));
            out[i][3] = row.get("wins");
            out[i][4] = row.get("losses");
            out[i][5] = row.get("win_percent");
            out[i][6] = row.get("games_behind");
            out[i][7] = row.get("run_differential");
        }
        return out;
    }

    public Object mlbSeasonStats(Object playerIdArg, String season, String field, Object apiKeyArg) {
        final String apiKey = MlbClient.resolveApiKey(optString(apiKeyArg));
        if (apiKey == null) return NO_API_KEY;
        Double idNum = optDouble(playerIdArg);
        final String seasonTrim = trim(season);
        final String fieldTrim = trim(field);
        if (idNum == null || seasonTrim.isEmpty() || fieldTrim.isEmpty()) return NOT_FOUND;
        final String playerId = fmtNum(idNum);
        String key = "seasonstats:player=" + playerId + ";season=" + seasonTrim + ";k=" + keyTag(apiKey);

        MlbCache.Result r = MlbCache.get(key, TTL_SEASON_STATS, new MlbCache.Fetcher() {
            public Object fetch() throws Exception { return MlbClient.seasonStats(playerId, seasonTrim, apiKey); }
        });
        String st = status(r);
        if (!READY.equals(st)) return st;

        List<Map<String, Object>> rows = cast(r.data);
        if (rows.isEmpty()) return NOT_FOUND;
        Object v = getPath(rows.get(0), fieldTrim);
        return v == null ? NOT_FOUND : v;
    }

    public Object mlbStat(Object playerIdArg, Object gameIdArg, String field, Object apiKeyArg) {
        final String apiKey = MlbClient.resolveApiKey(optString(apiKeyArg));
        if (apiKey == null) return NO_API_KEY;
        Double playerIdNum = optDouble(playerIdArg);
        Double gameIdNum = optDouble(gameIdArg);
        final String fieldTrim = trim(field);
        if (playerIdNum == null || gameIdNum == null || fieldTrim.isEmpty()) return NOT_FOUND;
        final String playerId = fmtNum(playerIdNum);
        final String gameId = fmtNum(gameIdNum);
        String key = "stats:player=" + playerId + ";game=" + gameId + ";k=" + keyTag(apiKey);

        MlbCache.Result r = MlbCache.get(key, TTL_STATS, new MlbCache.Fetcher() {
            public Object fetch() throws Exception { return MlbClient.statsByPlayerGame(playerId, gameId, apiKey); }
        });
        String st = status(r);
        if (!READY.equals(st)) return st;

        List<Map<String, Object>> rows = cast(r.data);
        if (rows.isEmpty()) return NOT_FOUND;
        Object v = getPath(rows.get(0), fieldTrim);
        return v == null ? NOT_FOUND : v;
    }

    public String mlbLastError() {
        return MlbCache.lastError();
    }

    public double mlbCacheClear() {
        return MlbCache.clear();
    }

    // ------------------------------------------------------------------ //
    // Domain helpers                                                      //
    // ------------------------------------------------------------------ //

    /** Maps a cache result to READY or one of the sentinel strings above. */
    private static String status(MlbCache.Result r) {
        if (MlbCache.Result.LOADING.equals(r.status)) return FETCHING;
        if (MlbCache.Result.ERROR.equals(r.status)) return classifyError(r.error);
        return READY;
    }

    /** Distinguishes rate-limit / tier-gating failures from generic errors by message. */
    private static String classifyError(String message) {
        if (message != null) {
            if (message.indexOf("rate limit exceeded") >= 0) return RATE_LIMIT;
            if (message.indexOf("requires a higher plan") >= 0) return TIER;
        }
        return ERR;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cast(Object o) {
        return (List<Map<String, Object>>) o;
    }

    private static Map<String, Object> nested(Map<String, Object> row, String field) {
        Object o = row.get(field);
        if (o instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) o;
            return m;
        }
        return Collections.emptyMap();
    }

    /** Resolves a dot-path (e.g. "home_team_data.runs") against nested JSON maps. */
    private static Object getPath(Map<String, Object> obj, String path) {
        Object cur = obj;
        for (String part : path.split("\\.")) {
            if (!(cur instanceof Map)) return null;
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) cur;
            cur = m.get(part);
        }
        return cur;
    }

    private static String dateOnly(Object o) {
        String s = str(o);
        int t = s.indexOf('T');
        return t > 0 ? s.substring(0, t) : s;
    }

    private static Object[][] sentinelTable(String sentinel) {
        return new Object[][] { { sentinel } };
    }

    private static String fmtNum(double d) {
        if (d == Math.floor(d) && !Double.isInfinite(d)) return String.valueOf((long) d);
        return String.valueOf(d);
    }

    /**
     * A short, stable fingerprint of an API key, appended to cache keys so
     * that two different keys (e.g. a formula-supplied key overriding the
     * environment) never share cached data or a cached error/cooldown state.
     */
    private static String keyTag(String apiKey) {
        return Integer.toHexString(apiKey.hashCode());
    }

    private static String teamsKey(String apiKey) {
        return "teams;k=" + keyTag(apiKey);
    }

    private static String playersSearchKey(String query, String apiKey) {
        return "players:search=" + query.toLowerCase() + ";k=" + keyTag(apiKey);
    }

    private static String playerByIdKey(String playerId, String apiKey) {
        return "players:id=" + playerId + ";k=" + keyTag(apiKey);
    }

    private static String gameByIdKey(String gameId, String apiKey) {
        return "games:id=" + gameId + ";k=" + keyTag(apiKey);
    }

    private static String standingsKey(String season, String apiKey) {
        return "standings:season=" + season + ";k=" + keyTag(apiKey);
    }

    // ------------------------------------------------------------------ //
    // Argument / value helpers                                           //
    // ------------------------------------------------------------------ //

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    /** Unwrap a 1x1 matrix (a single-cell reference may arrive as Object[][]). */
    private static Object scalar(Object arg) {
        if (arg instanceof Object[][]) {
            Object[][] m = (Object[][]) arg;
            return (m.length > 0 && m[0].length > 0) ? m[0][0] : null;
        }
        return arg;
    }

    /** Interpret an optional string argument; VOID/empty -> null. */
    private static String optString(Object arg) {
        arg = scalar(arg);
        if (arg == null || arg instanceof Any) {
            return null; // omitted argument arrives as VOID Any
        }
        String s = String.valueOf(arg).trim();
        return s.isEmpty() ? null : s;
    }

    /** Interpret an optional numeric argument; VOID/empty/non-numeric -> null. */
    private static Double optDouble(Object arg) {
        arg = scalar(arg);
        if (arg == null || arg instanceof Any) {
            return null;
        }
        if (arg instanceof Number) {
            return ((Number) arg).doubleValue();
        }
        String s = String.valueOf(arg).trim();
        if (s.isEmpty()) return null;
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long toLong(Double d) {
        return d == null ? null : Long.valueOf(d.longValue());
    }

    private static Long toLong(Object o) {
        if (o instanceof Number) return Long.valueOf(((Number) o).longValue());
        if (o instanceof String) {
            try {
                return Long.valueOf((long) Double.parseDouble(((String) o).trim()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ //
    // XAddIn - function metadata                                         //
    //                                                                    //
    // Calc uses getDisplayFunctionName() as the AUTHORITATIVE display    //
    // (formula) name; CalcAddIns.xcu only supplies wizard help. So these //
    // must map programmatic <-> display names explicitly, or the cell    //
    // formula (=MLBTEAMS()) resolves to #NAME?.                         //
    // ------------------------------------------------------------------ //

    /** { programmatic, display } for every exposed function. */
    private static final String[][] FUNCS = {
        { "mlbTeams",        "MLBTEAMS" },
        { "mlbTeam",         "MLBTEAM" },
        { "mlbPlayerSearch", "MLBPLAYERSEARCH" },
        { "mlbPlayer",       "MLBPLAYER" },
        { "mlbGames",        "MLBGAMES" },
        { "mlbGame",         "MLBGAME" },
        { "mlbStandings",    "MLBSTANDINGS" },
        { "mlbSeasonStats",  "MLBSEASONSTATS" },
        { "mlbStat",         "MLBSTAT" },
        { "mlbLastError",    "MLBLASTERROR" },
        { "mlbCacheClear",   "MLBCACHECLEAR" },
    };

    private static String funcDescription(String prog) {
        if ("mlbTeams".equals(prog)) return "Lists MLB teams, optionally filtered by league and/or division, as a spillable array.";
        if ("mlbTeam".equals(prog)) return "Scalar lookup of one field on one team, by numeric team id.";
        if ("mlbPlayerSearch".equals(prog)) return "Searches players by name; returns a spillable array of matches.";
        if ("mlbPlayer".equals(prog)) return "Scalar lookup of one field on one player, by numeric player id.";
        if ("mlbGames".equals(prog)) return "Lists games for a date or a season, optionally filtered to one team.";
        if ("mlbGame".equals(prog)) return "Scalar lookup of one field on one game, by numeric game id.";
        if ("mlbStandings".equals(prog)) return "Returns league standings for a season as a spillable array. Requires a paid balldontlie plan.";
        if ("mlbSeasonStats".equals(prog)) return "Scalar lookup of one season-stat field for a player. Requires a paid balldontlie plan.";
        if ("mlbStat".equals(prog)) return "Scalar lookup of one field from a player's single-game stat line. Requires a paid balldontlie plan.";
        if ("mlbLastError".equals(prog)) return "Returns the most recent fetch error message, for diagnostics.";
        if ("mlbCacheClear".equals(prog)) return "Clears every cached response; returns the number of entries cleared.";
        return "";
    }

    private static final String ARG_KEY = "api_key";

    private static String[] argNames(String prog) {
        if ("mlbTeams".equals(prog)) return new String[] { "league", "division", ARG_KEY };
        if ("mlbTeam".equals(prog)) return new String[] { "team_id", "field", ARG_KEY };
        if ("mlbPlayerSearch".equals(prog)) return new String[] { "search_text", "max_rows", ARG_KEY };
        if ("mlbPlayer".equals(prog)) return new String[] { "player_id", "field", ARG_KEY };
        if ("mlbGames".equals(prog)) return new String[] { "date_or_season", "team_id", "max_rows", ARG_KEY };
        if ("mlbGame".equals(prog)) return new String[] { "game_id", "field", ARG_KEY };
        if ("mlbStandings".equals(prog)) return new String[] { "season", ARG_KEY };
        if ("mlbSeasonStats".equals(prog)) return new String[] { "player_id", "season", "field", ARG_KEY };
        if ("mlbStat".equals(prog)) return new String[] { "player_id", "game_id", "field", ARG_KEY };
        return new String[0];
    }

    private static final String ARG_KEY_DESC =
        "Optional. balldontlie API key for this call; overrides the environment "
        + "(system property, BALLDONTLIE_API_KEY, or properties file) when supplied. "
        + "May reference a cell.";

    private static String[] argDescriptions(String prog) {
        if ("mlbTeams".equals(prog)) {
            return new String[] {
                "Optional. \"American\" or \"National\" to filter to one league.",
                "Optional. \"East\", \"Central\", or \"West\" to filter to one division.",
                ARG_KEY_DESC,
            };
        }
        if ("mlbTeam".equals(prog)) {
            return new String[] {
                "Numeric team id, from MLBTEAMS.",
                "The exact balldontlie JSON field to return, e.g. \"abbreviation\" or \"display_name\".",
                ARG_KEY_DESC,
            };
        }
        if ("mlbPlayerSearch".equals(prog)) {
            return new String[] {
                "A full or partial player name.",
                "Optional. Caps the number of rows returned (default 100).",
                ARG_KEY_DESC,
            };
        }
        if ("mlbPlayer".equals(prog)) {
            return new String[] {
                "Numeric player id, from MLBPLAYERSEARCH.",
                "The exact balldontlie JSON field to return, e.g. \"full_name\" or \"team.abbreviation\" (dot notation for nested fields).",
                ARG_KEY_DESC,
            };
        }
        if ("mlbGames".equals(prog)) {
            return new String[] {
                "An ISO date \"YYYY-MM-DD\" (games on that date) or a 4-digit season year \"YYYY\" (games in that season).",
                "Optional. A numeric team id from MLBTEAM to filter to (honored in both date and season mode).",
                "Optional. Caps the number of rows returned.",
                ARG_KEY_DESC,
            };
        }
        if ("mlbGame".equals(prog)) {
            return new String[] {
                "Numeric game id, from MLBGAMES.",
                "The exact balldontlie JSON field to return, e.g. \"status\" or \"home_team_data.runs\" (dot notation for nested fields).",
                ARG_KEY_DESC,
            };
        }
        if ("mlbStandings".equals(prog)) {
            return new String[] { "4-digit season year, e.g. \"2024\".", ARG_KEY_DESC };
        }
        if ("mlbSeasonStats".equals(prog)) {
            return new String[] {
                "Numeric player id, from MLBPLAYERSEARCH.",
                "4-digit season year, e.g. \"2024\".",
                "The exact balldontlie JSON field to return, e.g. \"batting_avg\", \"pitching_era\", \"fielding_fp\".",
                ARG_KEY_DESC,
            };
        }
        if ("mlbStat".equals(prog)) {
            return new String[] {
                "Numeric player id, from MLBPLAYERSEARCH.",
                "Numeric game id, from MLBGAMES.",
                "The exact balldontlie JSON field to return from that player's stat line for that game.",
                ARG_KEY_DESC,
            };
        }
        return new String[0];
    }

    public String getProgrammaticFuntionName(String displayName) {
        for (String[] f : FUNCS) {
            if (f[1].equals(displayName)) return f[0];
        }
        return "";
    }

    public String getDisplayFunctionName(String programmaticName) {
        for (String[] f : FUNCS) {
            if (f[0].equals(programmaticName)) return f[1];
        }
        return "";
    }

    public String getFunctionDescription(String programmaticName) {
        return funcDescription(programmaticName);
    }

    public String getDisplayArgumentName(String programmaticName, int argument) {
        String[] a = argNames(programmaticName);
        return (argument >= 0 && argument < a.length) ? a[argument] : "";
    }

    public String getArgumentDescription(String programmaticName, int argument) {
        String[] a = argDescriptions(programmaticName);
        return (argument >= 0 && argument < a.length) ? a[argument] : "";
    }

    public String getProgrammaticCategoryName(String programmaticName) {
        return "Add-In";
    }

    public String getDisplayCategoryName(String programmaticName) {
        return "Add-In";
    }

    // ------------------------------------------------------------------ //
    // XLocalizable (inherited via XAddIn)                                //
    // ------------------------------------------------------------------ //

    public void setLocale(Locale locale) {
        this.locale = locale;
    }

    public Locale getLocale() {
        return locale;
    }

    // ------------------------------------------------------------------ //
    // XServiceName / XServiceInfo                                        //
    // ------------------------------------------------------------------ //

    public String getServiceName() {
        return IMPLEMENTATION_NAME;
    }

    public String getImplementationName() {
        return IMPLEMENTATION_NAME;
    }

    public boolean supportsService(String service) {
        for (String s : SERVICE_NAMES) {
            if (s.equals(service)) return true;
        }
        return false;
    }

    public String[] getSupportedServiceNames() {
        return SERVICE_NAMES.clone();
    }

    // ------------------------------------------------------------------ //
    // UNO component registration entry points                           //
    // ------------------------------------------------------------------ //

    public static XSingleComponentFactory __getComponentFactory(String implName) {
        if (IMPLEMENTATION_NAME.equals(implName)) {
            return Factory.createComponentFactory(MlbImpl.class, SERVICE_NAMES);
        }
        return null;
    }

    public static boolean __writeRegistryServiceInfo(XRegistryKey regKey) {
        return Factory.writeRegistryServiceInfo(IMPLEMENTATION_NAME, SERVICE_NAMES, regKey);
    }
}
