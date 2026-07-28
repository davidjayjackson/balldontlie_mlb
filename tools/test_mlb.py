"""Smoke test for the balldontlie MLB Calc add-in.

Run with LibreOffice's bundled Python (it ships the `uno` module) against a
headless instance listening on a UNO socket:

    soffice --headless --norestore --accept="socket,host=localhost,port=2002;urp;"
    <LO>/program/python tools/test_mlb.py

With no BALLDONTLIE_API_KEY set (and no properties file), every data
function should register (no #NAME?) and evaluate to the #NO_API_KEY
sentinel, while MLBLASTERROR/MLBCACHECLEAR (which don't need a key) work
normally. Prints RESULT: PASS / FAIL and exits non-zero on failure.
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
        except Exception as e:  # not yet listening
            last = e
            time.sleep(0.5)
    raise SystemExit("could not connect to LibreOffice: %s" % last)


def main():
    ctx = connect()
    smgr = ctx.ServiceManager
    desktop = smgr.createInstanceWithContext("com.sun.star.frame.Desktop", ctx)
    doc = desktop.loadComponentFromURL("private:factory/scalc", "_blank", 0, ())
    results = {}
    try:
        sheet = doc.Sheets.getByIndex(0)

        formulas = {
            "team":         '=MLBTEAM(1;"abbreviation")',
            "player":       '=MLBPLAYER(1;"full_name")',
            "game":         '=MLBGAME(1;"status")',
            "standings_sc": '=MLBSTANDINGS("2024")',  # also tested as array below
            "seasonstats":  '=MLBSEASONSTATS(1;"2024";"batting_avg")',
            "stat":         '=MLBSTAT(1;1;"hits")',
            "lasterror":    '=MLBLASTERROR()',
            "cacheclear":   '=MLBCACHECLEAR()',
        }
        cells = {}
        for i, (name, f) in enumerate(formulas.items()):
            c = sheet.getCellByPosition(0, i)
            c.setFormula(f)
            cells[name] = c

        table_formulas = {
            "teams":        ('=MLBTEAMS()', "B1:H30"),
            "playersearch": ('=MLBPLAYERSEARCH("Judge")', "B32:H40"),
            "games":        ('=MLBGAMES("2024-06-01")', "B42:J60"),
            "standings":    ('=MLBSTANDINGS("2024")', "B62:J80"),
        }
        ranges = {}
        for name, (f, addr) in table_formulas.items():
            rng = sheet.getCellRangeByName(addr)
            rng.setArrayFormula(f)
            ranges[name] = rng

        doc.calculateAll()

        for name, c in cells.items():
            results[name] = (c.getString(), c.getError(), c.getFormula())
        for name, rng in ranges.items():
            results[name] = (rng.getDataArray(),)
    finally:
        doc.close(False)
        desktop.terminate()

    checks = {}
    for name in ("team", "player", "game", "standings_sc", "seasonstats", "stat"):
        s, err, formula = results[name]
        print("%-12s formula=%-40s value=%r err=%s" % (name, formula, s, err))
        checks[name + "_registered"] = formula != "" and "#NAME?" not in s
        checks[name + "_no_api_key"] = s == "#NO_API_KEY"

    le_val, le_err, _ = results["lasterror"]
    print("lasterror    value=%r err=%s" % (le_val, le_err))
    checks["lasterror_registered"] = le_err == 0

    cc_val, cc_err, _ = results["cacheclear"]
    print("cacheclear   value=%r err=%s" % (cc_val, cc_err))
    checks["cacheclear_registered"] = cc_err == 0

    for name in ("teams", "playersearch", "games", "standings"):
        rows = results[name][0]
        print("%-12s top-left=%r" % (name, rows[0][0]))
        checks[name + "_registered"] = rows[0][0] == "#NO_API_KEY"

    print("---")
    for name, ok in checks.items():
        print("CHECK %-24s %s" % (name, "PASS" if ok else "FAIL"))

    ok = all(checks.values())
    print("RESULT:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
