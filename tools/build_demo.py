"""Generate test/mlb_demo.ods using the installed balldontlie MLB add-in,
built around the New York Yankees / Aaron Judge.

Run against a headless LibreOffice listening on a UNO socket (see
docs/INSTALL.md), with a real balldontlie API key available via the
BALLDONTLIE_API_KEY environment variable:

    soffice --headless --norestore --accept="socket,host=localhost,port=2002;urp;" &
    BALLDONTLIE_API_KEY="$(cat /path/to/key)" \
        "$LO_HOME/program/python" tools/build_demo.py

Every formula in the sheet takes its api_key argument from cell A1, rather
than relying on environment resolution -- this both demonstrates the
optional trailing api_key argument and means the whole demo is driven by
one visible, editable cell. During generation A1 holds the *real* key (from
BALLDONTLIE_API_KEY) so live results get baked into the saved cell values;
the very last step overwrites A1 with an obvious placeholder before saving.
This file is public, so a real key sitting in A1 would be a credential leak
the moment it's committed -- the placeholder is what ships. Paste your own
key into A1 and recalculate (Ctrl+Shift+F9) to bring the sheet back to life.

No team/player/game id is hardcoded: the sheet looks up the Yankees' team id
from the MLBTEAMS() table with INDEX/MATCH, and Aaron Judge's player id /
a game id from the MLBPLAYERSEARCH() and MLBGAMES() spills, all with plain
cell references -- so this keeps working even if balldontlie's ids change.

Functions that need a paid balldontlie plan (standings, season stats,
single-game stats) are included for reference too -- on a free-tier key
they'll correctly show #TIER, which is documented, expected behavior (see
MLBLASTERROR in the sheet).

Formulas are entered and resolved one distinct-cache-key group at a time
(rather than all at once) because the add-in throttles outgoing balldontlie
requests to ~13s apart to respect the free tier's ~5 req/min limit -- firing
every formula simultaneously would queue them all behind that single-file
throttle and blow past any reasonable per-cell wait budget.
"""
import os
import sys
import time
import uno
from com.sun.star.beans import PropertyValue

PLACEHOLDER_KEY = "YOUR_API_KEY_HERE"


def connect(port=2002, tries=60):
    local = uno.getComponentContext()
    resolver = local.ServiceManager.createInstanceWithContext(
        "com.sun.star.bridge.UnoUrlResolver", local)
    url = "uno:socket,host=localhost,port=%d;urp;StarOffice.ComponentContext" % port
    last = None
    for _ in range(tries):
        try:
            return resolver.resolve(url)
        except Exception as e:
            last = e
            time.sleep(0.5)
    raise SystemExit("could not connect to LibreOffice: %s" % last)


def main():
    real_key = os.environ.get("BALLDONTLIE_API_KEY", "").strip()
    if not real_key:
        raise SystemExit("Set BALLDONTLIE_API_KEY before running this script.")

    out_path = os.path.abspath(
        os.path.join(os.path.dirname(__file__), "..", "test", "mlb_demo.ods"))
    out_url = uno.systemPathToFileUrl(out_path)

    ctx = connect()
    smgr = ctx.ServiceManager
    desktop = smgr.createInstanceWithContext("com.sun.star.frame.Desktop", ctx)
    doc = desktop.loadComponentFromURL("private:factory/scalc", "_blank", 0, ())
    try:
        sh = doc.Sheets.getByIndex(0)
        sh.Name = "Yankees Demo"

        def put(col, row, value):
            sh.getCellByPosition(col, row).setString(value)

        def formula(col, row, f):
            c = sh.getCellByPosition(col, row)
            c.setFormula(f)
            return c

        def array_formula(c0, r0, c1, r1, f):
            rng = sh.getCellRangeByPosition(c0, r0, c1, r1)
            rng.setArrayFormula(f)
            return rng

        def settle(cell_or_range, max_wait=45, poll=2.0):
            """Recalc until the top-left value moves off #FETCHING, or timeout."""
            deadline = time.time() + max_wait
            top_left = cell_or_range.getCellByPosition(0, 0) if hasattr(cell_or_range, "getCellByPosition") else cell_or_range
            doc.calculateAll()
            while top_left.getString() == "#FETCHING" and time.time() < deadline:
                time.sleep(poll)
                doc.calculateAll()
            return top_left.getString()

        # --- A1: the api_key cell every formula below references. Real key
        # now, placeholder swapped in right before saving. ---
        sh.getCellByPosition(0, 0).setString(real_key)
        KEY = "$A$1"

        put(0, 1, "balldontlie MLB Calc Add-In - demo (New York Yankees / Aaron Judge)")
        put(0, 2, "A1 holds the balldontlie api_key every formula below references - paste your own key there.")
        put(0, 3, "#FETCHING means a background fetch just started - recalc (Ctrl+Shift+F9) again once it settles.")
        put(0, 4, "No ids are hardcoded: team/player/game ids below are looked up live from the tables themselves.")

        # ------------------------------------------------------------ #
        # MLBTEAMS() - full league table, array formula                #
        # ------------------------------------------------------------ #
        put(0, 6, "MLBTEAMS()  (array formula, every team)")
        teams_header = 7
        for i, h in enumerate(["id", "abbreviation", "display_name", "location", "league", "division"]):
            put(i, teams_header, h)
        teams_first = teams_header + 1
        # Exactly 30 -- MLB's real team count, verified live. An oversized
        # array-formula selection pads the excess rows with #N/A, and any
        # #N/A cell inside a MATCH/INDEX range poisons the whole lookup
        # even when the real match is earlier in the range -- so this must
        # be exact, not "a little extra for safety".
        teams_rows = 30
        teams_f = "=MLBTEAMS(;;%s)" % KEY
        teams_rng = array_formula(0, teams_first, 5, teams_first + teams_rows - 1, teams_f)
        print("teams ->", settle(teams_rng))
        teams_last = teams_first + teams_rows - 1
        note_row = teams_last + 1
        put(0, note_row, "{" + teams_f + "}  (select range, Ctrl+Shift+Enter)")

        # Yankees team id, resolved from the table above. Note: LibreOffice
        # can't run MATCH/COUNTIF/VLOOKUP *directly* against a range that is
        # itself the live output of another array formula from a UNO add-in
        # (confirmed: identical values pasted as plain static cells work
        # fine with MATCH; the exact same range driven by the add-in's array
        # formula returns #N/A even though EXACT() on a single cell in it
        # confirms the value is correct) -- so this script finds the row in
        # Python instead and writes a plain cell reference. See the
        # README's "Behavior notes" for the workaround if you hit this in
        # your own sheet (copy > paste special > values only, then look up
        # against that plain copy).
        teams_data = teams_rng.getDataArray()
        yankees_offset = next(i for i, row in enumerate(teams_data) if row[1] == "NYY")
        yankees_row_1based = teams_first + 1 + yankees_offset
        r = note_row + 2
        put(0, r, "Yankees team id (=$A$%d, the NYY row in the table above)" % yankees_row_1based)
        team_id_ref = "$A$%d" % yankees_row_1based
        c = formula(1, r, "=" + team_id_ref)
        doc.calculateAll()
        print("team_id ->", c.getString())

        # ------------------------------------------------------------ #
        # MLBTEAM() - scalar field lookups, reuse the cached team list #
        # ------------------------------------------------------------ #
        r += 2
        put(0, r, "Function")
        put(1, r, "Live result")
        put(2, r, "Formula")
        r += 1
        f = "=MLBTEAM(%s;\"display_name\";%s)" % (team_id_ref, KEY)
        put(0, r, "MLBTEAM (display_name)")
        put(2, r, f)
        c = formula(1, r, f)
        print("team display_name ->", settle(c, max_wait=20))
        r += 1
        f = "=MLBTEAM(%s;\"league\";%s)" % (team_id_ref, KEY)
        put(0, r, "MLBTEAM (league)")
        put(2, r, f)
        c = formula(1, r, f)
        print("team league ->", settle(c, max_wait=15))

        # ------------------------------------------------------------ #
        # MLBGAMES() - season games for the Yankees, array formula      #
        # ------------------------------------------------------------ #
        r += 2
        put(0, r, "MLBGAMES(\"2024\"; Yankees team id; max_rows=15)  (array formula)")
        games_header = r + 1
        for i, h in enumerate(["id", "date", "away_team", "away_runs", "home_team", "home_runs", "status", "season_type"]):
            put(i, games_header, h)
        games_first = games_header + 1
        games_rows = 15
        games_f = "=MLBGAMES(\"2024\";%s;%d;%s)" % (team_id_ref, games_rows, KEY)
        games_rng = array_formula(0, games_first, 7, games_first + games_rows - 1, games_f)
        print("games ->", settle(games_rng))
        games_last = games_first + games_rows - 1
        r = games_last + 1
        put(0, r, "{" + games_f + "}  (select range, Ctrl+Shift+Enter)")

        # A single game id from the table above - first row, no hardcoded id.
        game_id_ref = "$A$%d" % (games_first + 1)

        # ------------------------------------------------------------ #
        # MLBGAME() - scalar field lookup on that one game              #
        # ------------------------------------------------------------ #
        r += 2
        f = "=MLBGAME(%s;\"status\";%s)" % (game_id_ref, KEY)
        put(0, r, "MLBGAME (status, for the first game above)")
        put(2, r, f)
        c = formula(1, r, f)
        print("game status ->", settle(c))

        # ------------------------------------------------------------ #
        # MLBPLAYERSEARCH() - array formula                             #
        # ------------------------------------------------------------ #
        r += 2
        put(0, r, "MLBPLAYERSEARCH(\"Judge\")  (array formula)")
        ps_header = r + 1
        for i, h in enumerate(["id", "full_name", "position", "team", "active"]):
            put(i, ps_header, h)
        ps_first = ps_header + 1
        ps_rows = 5
        ps_f = "=MLBPLAYERSEARCH(\"Judge\";%d;%s)" % (ps_rows, KEY)
        ps_rng = array_formula(0, ps_first, 4, ps_first + ps_rows - 1, ps_f)
        print("playersearch ->", settle(ps_rng))
        ps_last = ps_first + ps_rows - 1
        r = ps_last + 1
        put(0, r, "{" + ps_f + "}  (select range, Ctrl+Shift+Enter)")

        # Aaron Judge's player id from the table above - no hardcoded id.
        player_id_ref = "$A$%d" % (ps_first + 1)

        # ------------------------------------------------------------ #
        # MLBPLAYER() - scalar field lookups, reuse the cached record   #
        # ------------------------------------------------------------ #
        r += 2
        f = "=MLBPLAYER(%s;\"full_name\";%s)" % (player_id_ref, KEY)
        put(0, r, "MLBPLAYER (full_name)")
        put(2, r, f)
        c = formula(1, r, f)
        print("player full_name ->", settle(c))
        r += 1
        f = "=MLBPLAYER(%s;\"team.abbreviation\";%s)" % (player_id_ref, KEY)
        put(0, r, "MLBPLAYER (team.abbreviation - dot notation for a nested field)")
        put(2, r, f)
        c = formula(1, r, f)
        print("player team.abbreviation ->", settle(c, max_wait=15))

        # ------------------------------------------------------------ #
        # Paid-tier endpoints - correctly show #TIER on a free key      #
        # ------------------------------------------------------------ #
        r += 2
        put(0, r, "MLBSTANDINGS(\"2024\")  (array formula)  [needs paid tier - shows #TIER on a free key]")
        st_header = r + 1
        for i, h in enumerate(["team", "league", "division", "wins", "losses", "win_pct", "games_behind", "run_diff"]):
            put(i, st_header, h)
        st_first = st_header + 1
        st_f = "=MLBSTANDINGS(\"2024\";%s)" % KEY
        st_rng = array_formula(0, st_first, 7, st_first + 4, st_f)
        print("standings ->", settle(st_rng, max_wait=30))
        r = st_first + 6
        put(0, r, "{" + st_f + "}  (select range, Ctrl+Shift+Enter)")

        r += 2
        f = "=MLBSEASONSTATS(%s;\"2024\";\"batting_avg\";%s)" % (player_id_ref, KEY)
        put(0, r, "MLBSEASONSTATS (batting_avg)  [needs paid tier - shows #TIER on a free key]")
        put(2, r, f)
        c = formula(1, r, f)
        print("seasonstats ->", settle(c, max_wait=30))

        r += 1
        f = "=MLBSTAT(%s;%s;\"hits\";%s)" % (player_id_ref, game_id_ref, KEY)
        put(0, r, "MLBSTAT (hits, first Yankees game above)  [needs paid tier - shows #TIER on a free key]")
        put(2, r, f)
        c = formula(1, r, f)
        print("stat ->", settle(c, max_wait=40))

        # ------------------------------------------------------------ #
        # Diagnostics - entered last so MLBLASTERROR reflects the most #
        # recent (paid-tier) failure above.                            #
        # ------------------------------------------------------------ #
        r += 2
        f = "=MLBLASTERROR()"
        put(0, r, "MLBLASTERROR()")
        put(2, r, f)
        c = formula(1, r, f)
        doc.calculateAll()
        print("lasterror ->", c.getString())

        r += 1
        f = "=MLBCACHECLEAR()"
        put(0, r, "MLBCACHECLEAR()  (clears the shared cache; harmless to call here, at the very end)")
        put(2, r, f)
        c = formula(1, r, f)
        doc.calculateAll()
        print("cacheclear ->", c.getString())

        # Widen columns a little for readability.
        cols = sh.Columns
        for i in range(8):
            cols.getByIndex(i).Width = 4200
        cols.getByIndex(0).Width = 9500

        doc.calculateAll()

        # Swap the real key for an obvious placeholder before saving - this
        # file is public; only the placeholder ever gets committed.
        sh.getCellByPosition(0, 0).setString(PLACEHOLDER_KEY)

        # Save as ODF spreadsheet (calc8 = .ods).
        fn = PropertyValue()
        fn.Name = "FilterName"
        fn.Value = "calc8"
        doc.storeToURL(out_url, (fn,))
        print("wrote", out_path)
    finally:
        doc.close(False)
        desktop.terminate()


if __name__ == "__main__":
    main()
