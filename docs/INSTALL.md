# balldontlie MLB Calc Add-In — build & install

## 1. Prerequisites

### Linux / macOS

1. **A JDK 8** (`javac`, `jar`). If your distro's package manager has one
   (`apt install openjdk-8-jdk-headless`, `dnf install java-1.8.0-openjdk-devel`,
   …) use that. Otherwise, no root needed — fetch a JDK 8 build straight from
   Eclipse Adoptium/Temurin and unpack it under your home directory:

   ```bash
   curl -s "https://api.adoptium.net/v3/assets/latest/8/hotspot?architecture=x64&image_type=jdk&os=linux&vendor=eclipse" \
     | grep -o '"link": *"[^"]*tar.gz"' | head -1 | cut -d'"' -f4
   # download that URL, then:
   mkdir -p ~/opt && tar xzf OpenJDK8U-jdk_x64_linux_hotspot_*.tar.gz -C ~/opt
   export JAVA_HOME=~/opt/jdk8u<version>   # match the extracted directory name
   export PATH="$JAVA_HOME/bin:$PATH"
   ```

2. **LibreOffice + SDK.** If your distro packages a matching SDK
   (`apt install libreoffice-dev libreoffice-dev-common` on Debian/Ubuntu,
   or the `libreoffice` + `libreoffice-sdk` packages elsewhere), use that —
   the SDK typically lands alongside the main install, e.g.
   `/usr/lib64/libreoffice/sdk`. Otherwise, download the generic Linux
   tarballs (RPM-based; works on any distro since we only extract the
   `.rpm`s, not install them) from
   <https://download.documentfoundation.org/libreoffice/stable/>, extract
   each `.rpm` inside them with `rpm2cpio`/`cpio` into a prefix directory —
   no root required — and point `LO_HOME` at the resulting tree (same
   `program/` + `sdk/bin/` layout either way).

3. **Java vendor allow-list.** LibreOffice only loads a JVM whose
   `java.vendor` appears in `$LO_HOME/program/javavendors.xml` (Sun, Oracle,
   IBM, Blackdown, BEA, Azul, Amazon by default). A stock Temurin/Adoptium
   build reports vendor `Temurin`, which is **not** on that list — `unopkg`
   will fail with `CannotRegisterImplementationException: Could not create
   Java implementation loader` when installing the extension. Add an entry
   for it in your local `javavendors.xml` (this file lives inside your own
   LibreOffice install, not a system-shared one, so editing it is safe):

   ```xml
   <vendor name="Temurin">
     <minVersion>1.8.0</minVersion>
   </vendor>
   ```

   Insert it next to the other `<vendor>` entries, inside `<vendorInfos>`.
   (If your JDK came from your distro's package manager, its vendor is
   usually already on the list and this step is unnecessary.)

### Windows

1. **LibreOffice + SDK.** Default install path `C:\Program Files\LibreOffice`,
   with the SDK under `…\LibreOffice\sdk`. The SDK provides
   `sdk\bin\unoidl-write.exe` and `sdk\bin\javamaker.exe`.
2. **A JDK to build with** — any JDK 8 or newer (`javac`, `jar`). The build
   targets **Java 8 bytecode** (`--release 8`), so the add-in runs on the
   JRE 8 that LibreOffice accepts out of the box. Set `JAVA_HOME`, or put
   `javac` on `PATH`.
3. **Runtime JRE — nothing to change.** The component is Java-8 bytecode and
   uses only `java.net.HttpURLConnection`, so LibreOffice's existing/default
   JRE (8+) runs it as-is.

> Why not `java.net.http.HttpClient`? That needs Java 11+; targeting Java 8 +
> `HttpURLConnection` (both JDK standard library) avoids installing a new JRE
> while still meeting the "zero third-party dependencies" requirement.

Confirm the tools resolve:

```bash
"$LO_HOME/sdk/bin/unoidl-write"          # prints usage
"$LO_HOME/sdk/bin/javamaker"             # prints usage
"$JAVA_HOME/bin/javac" -version          # any 8+
```

## 2. Provide the balldontlie API key (never hardcoded)

Four ways, in priority order — see the README's "Provide the balldontlie
API key" section for the full explanation:

1. The optional trailing `api_key` formula argument.
2. `-Dballdontlie.api.key=...` JVM system property.
3. `BALLDONTLIE_API_KEY` environment variable, set in the shell that
   launches `soffice`.
4. `~/.config/libreoffice-mlb/balldontlie.properties` (`api.key=...`;
   macOS: `~/Library/Application Support/libreoffice-mlb/balldontlie.properties`) —
   works regardless of how LibreOffice is launched.

Get a free key at <https://www.balldontlie.io/>.

## 3. Build the .oxt

### Linux / macOS

```bash
export JAVA_HOME=~/opt/jdk8u<version>     # or wherever your JDK 8 lives
export LO_HOME=/usr/lib64/libreoffice     # or wherever LibreOffice + SDK live
./build.sh
# or pass paths explicitly instead of the env vars:
./build.sh --jdk ~/opt/jdk8u<version> --libreoffice /usr/lib64/libreoffice
```

This produces `build/MLB.oxt` via five steps:

```
1. unoidl-write  idl/**              -> build/types/XMlb.rdb   (UNO type library)
2. javamaker     build/types/XMlb.rdb -> build/gen/**.class    (Java bindings)
3. javac         src/**.java + bindings -> build/classes/**.class
4. jar           classes + bindings   -> build/oxt/mlb.jar     (+ RegistrationClassName)
5. zip           staging tree         -> build/MLB.oxt
```

Two JDK-8-specific quirks it works around, in case you're compiling by hand:

- **`javac --release 8` doesn't exist on JDK 8 itself** (the flag was added
  in JDK 9); `build.sh` detects a `1.x` `javac -version` and falls back to
  `-source 8 -target 8`, which is equivalent for a straight JDK-8 build.
- **`jar` on JDK 8 can reject duplicate directory entries** when packaging
  two class trees that share a package path (`com/example/mlb/` appears in
  both the compiled classes and the generated UNO bindings), throwing
  `java.util.zip.ZipException: duplicate entry: com/`. `build.sh` merges
  both trees into one staging directory first, then jars that single tree.

#### The equivalent commands by hand

```bash
LO="$LO_HOME"
export PATH="$LO/program:$PATH"

# 1. IDL -> UNO type library
"$LO/sdk/bin/unoidl-write" "$LO/program/types.rdb" idl build/types/XMlb.rdb

# 2. type library -> Java bindings
"$LO/sdk/bin/javamaker" -nD -Gc -O build/gen -X "$LO/program/types.rdb" build/types/XMlb.rdb

# 3. compile (JDK 9+: --release 8; JDK 8 itself: -source 8 -target 8)
"$JAVA_HOME/bin/javac" -source 8 -target 8 -cp "build/gen:$LO/program/classes/*" \
  -d build/classes $(find src -name '*.java')

# 4. merge classes + bindings (avoids the JDK-8 jar duplicate-entry issue), then jar
mkdir -p build/jarstage
cp -r build/classes/. build/jarstage/
cp -r build/gen/. build/jarstage/
"$JAVA_HOME/bin/jar" cfm build/oxt/mlb.jar registration/MANIFEST.MF -C build/jarstage .

# 5. stage config/types/manifest, then zip the four entries into build/MLB.oxt
#    (types/XMlb.rdb, mlb.jar, config/CalcAddIns.xcu, description.xml, META-INF/manifest.xml)
```

### Windows

A PowerShell port of `build.sh` is not included in this release; the by-hand
commands above translate directly (swap `$LO/sdk/bin/…` for
`…\sdk\bin\….exe`, `:` path separators for `;`, and `find`/`$()` for
`Get-ChildItem -Recurse`).

## 4. Install into LibreOffice

Close LibreOffice first, then use `unopkg`:

```bash
"$LO_HOME/program/unopkg" add --force build/MLB.oxt
# list / remove:
"$LO_HOME/program/unopkg" list
"$LO_HOME/program/unopkg" remove com.example.mlb
```

You can also install by double-clicking `build/MLB.oxt` (opens the Extension
Manager). After installing, **restart LibreOffice** from a shell that has
`BALLDONTLIE_API_KEY` set, if you're using the environment-variable route
(the properties-file route needs no special launch environment).

## 5. Try it

```
=MLBTEAMS()                      (array formula: Ctrl+Shift+Enter)
=MLBTEAM(147; "abbreviation")
=MLBGAMES("2024-06-01")          (array formula)
=MLBSTANDINGS("2024")            (array formula, paid tier)
```

See the README's "Try it" section for the full list.

## 6. Smoke test (optional)

`tools/test_mlb.py` runs every function against a headless LibreOffice
instance and checks that each one registers (no `#NAME?`) and, with no API
key configured, returns `#NO_API_KEY`:

```bash
"$LO_HOME/program/soffice" --headless --norestore \
  --accept="socket,host=localhost,port=2002;urp;" &
"$LO_HOME/program/python" tools/test_mlb.py
```

## Troubleshooting

- `unoidl-write` / `javamaker` "not found" → pass the right
  `--libreoffice` path; the SDK must be installed (it is a separate
  download from LibreOffice on some platforms).
- Functions show `#NAME?` → the extension isn't registered; confirm with
  `unopkg list` and restart LibreOffice.
- Every `MLB*` cell shows `#NO_API_KEY` → none of the four key-resolution
  mechanisms found a key. See the README's "Provide the balldontlie API
  key" section.
- A cell shows `#ERR` persistently → call `=MLBLASTERROR()` for the detail
  message; common causes are an invalid key or a persistent network/server
  error. A cell shows `#RATE_LIMIT` → you've hit the free tier's ~5
  requests/minute cap; it clears on its own after the cache's ~15s cooldown.
  A cell shows `#TIER` on `MLBSTANDINGS`/`MLBSEASONSTATS`/`MLBSTAT` → your
  balldontlie plan doesn't include that endpoint.
- `unopkg add` fails with `CannotRegisterImplementationException: Could not
  create Java implementation loader` (Linux/macOS) → your JDK's vendor isn't
  in `$LO_HOME/program/javavendors.xml`'s allow-list — see the Java vendor
  allow-list note in Prerequisites.
- `unopkg add` fails with a lock-file error (`The lock file indicates it is
  already running`) → an earlier LibreOffice process didn't shut down
  cleanly. Confirm nothing is actually using it (`pgrep soffice`), then
  remove `~/.config/libreoffice/4/.lock`.
