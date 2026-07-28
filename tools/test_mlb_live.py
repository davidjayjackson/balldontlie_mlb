"""Live smoke test for the balldontlie MLB Calc add-in, using a real API key
resolved via ~/.config/libreoffice-mlb/balldontlie.properties.

Run:
    soffice --headless --norestore --accept="socket,host=localhost,port=2002;urp;"
    <LO>/program/python tools/test_mlb_live.py
"""
import sys
import time
import uno


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


NOT_READY = ("#FETCHING",)


def wait_scalar(doc, sheet, col, row, formula, max_wait=45):
    c = sheet.getCellByPosition(col, row)
    deadline = time.time() + max_wait
    val = None
    while True:
        c.setFormula(formula)
        doc.calculateAll()
        val = c.getString()
        if val not in NOT_READY or time.time() > deadline:
            return val
        time.sleep(1.5)


def wait_table(doc, sheet, addr, formula, max_wait=45):
    rng = sheet.getCellRangeByName(addr)
    deadline = time.time() + max_wait
    while True:
        rng.setArrayFormula(formula)
        doc.calculateAll()
        data = rng.getDataArray()
        if data[0][0] not in NOT_READY or time.time() > deadline:
            return data
        time.sleep(1.5)


def main():
    ctx = connect()
    smgr = ctx.ServiceManager
    desktop = smgr.createInstanceWithContext("com.sun.star.frame.Desktop", ctx)
    doc = desktop.loadComponentFromURL("private:factory/scalc", "_blank", 0, ())
    checks = {}
    try:
        sheet = doc.Sheets.getByIndex(0)
        bad = ("#FETCHING", "#NO_API_KEY", "#ERR", "#NOT_FOUND", "#RATE_LIMIT")

        teams = wait_table(doc, sheet, "B1:H40", '=MLBTEAMS()')
        print("MLBTEAMS() row0         ->", repr(teams[0]))
        checks["teams_ok"] = teams[0][0] not in bad
        team_id = teams[0][0] if checks["teams_ok"] else None
        team_abbrev = teams[0][1] if checks["teams_ok"] else None

        if team_id is not None:
            team_field = wait_scalar(doc, sheet, 0, 0, '=MLBTEAM(%s;"abbreviation")' % team_id)
            print("MLBTEAM(id,abbreviation)->", repr(team_field))
            checks["team_field_matches"] = team_field == team_abbrev
        else:
            checks["team_field_matches"] = False

        players = wait_table(doc, sheet, "B42:H60", '=MLBPLAYERSEARCH("Judge")')
        print("MLBPLAYERSEARCH(Judge) row0 ->", repr(players[0]))
        checks["playersearch_ok"] = players[0][0] not in bad
        player_id = players[0][0] if checks["playersearch_ok"] else None

        if player_id is not None:
            player_field = wait_scalar(doc, sheet, 0, 1, '=MLBPLAYER(%s;"full_name")' % player_id)
            print("MLBPLAYER(id,full_name) ->", repr(player_field))
            checks["player_field_ok"] = player_field not in bad
        else:
            checks["player_field_ok"] = False

        games = wait_table(doc, sheet, "B62:I80", '=MLBGAMES("2024-06-01")')
        print("MLBGAMES(2024-06-01) row0 ->", repr(games[0]))
        checks["games_ok"] = games[0][0] not in bad and games[0][1] == "2024-06-01"
        game_id = games[0][0] if games[0][0] not in bad else None

        if game_id is not None:
            game_field = wait_scalar(doc, sheet, 0, 2, '=MLBGAME(%s;"status")' % game_id)
            print("MLBGAME(id,status)      ->", repr(game_field))
            checks["game_field_ok"] = game_field not in bad
        else:
            checks["game_field_ok"] = False

        # Paid-tier endpoints on a free key should degrade to #TIER cleanly,
        # not hang or crash. On a paid key they should return real data.
        standings = wait_table(doc, sheet, "B82:I95", '=MLBSTANDINGS("2024")')
        print("MLBSTANDINGS(2024) row0 ->", repr(standings[0]))
        checks["standings_handled"] = standings[0][0] in ("#TIER",) or standings[0][0] not in (
            "#FETCHING", "#NO_API_KEY", "#ERR")

        if player_id is not None:
            season_stat = wait_scalar(doc, sheet, 0, 3, '=MLBSEASONSTATS(%s;"2024";"batting_avg")' % player_id)
            print("MLBSEASONSTATS(...)     ->", repr(season_stat))
            checks["seasonstats_handled"] = season_stat in ("#TIER",) or season_stat not in (
                "#FETCHING", "#NO_API_KEY", "#ERR")
        else:
            checks["seasonstats_handled"] = False

        if player_id is not None and game_id is not None:
            stat = wait_scalar(doc, sheet, 0, 4, '=MLBSTAT(%s;%s;"hits")' % (player_id, game_id))
            print("MLBSTAT(...)            ->", repr(stat))
            checks["stat_handled"] = stat in ("#TIER",) or stat not in (
                "#FETCHING", "#NO_API_KEY", "#ERR")
        else:
            checks["stat_handled"] = False

        lasterror = sheet.getCellByPosition(0, 5)
        lasterror.setFormula('=MLBLASTERROR()')
        doc.calculateAll()
        le = lasterror.getString()
        print("MLBLASTERROR()          ->", repr(le))
    finally:
        doc.close(False)
        desktop.terminate()

    print("---")
    for name, ok in checks.items():
        print("CHECK %-24s %s" % (name, "PASS" if ok else "FAIL"))
    ok = all(checks.values())
    print("RESULT:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
