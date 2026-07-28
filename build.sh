#!/usr/bin/env bash
#
# build.sh - Assemble the balldontlie MLB Java Calc add-in into build/MLB.oxt
#
# Pipeline:
#   1. unoidl-write : idl/**.idl           -> build/types/XMlb.rdb   (UNO type library)
#   2. javamaker    : build/types/XMlb.rdb -> build/gen/**.class      (Java bindings)
#   3. javac        : src/**.java + bindings -> build/classes/**.class
#   4. jar          : classes + bindings    -> build/oxt/mlb.jar      (+ RegistrationClassName)
#   5. zip          : staging tree          -> build/MLB.oxt
#
# Requirements:
#   - LibreOffice with the SDK installed. Resolved from -l/--libreoffice, else
#     $LO_HOME, else /opt/libreoffice*, else ~/libreoffice*.
#   - A JDK (any 8+) to compile with. Resolved from -j/--jdk, else JAVA_HOME,
#     else PATH. Output targets Java 8 bytecode (--release 8) so it runs on
#     the JRE 8 that LibreOffice accepts; see docs/INSTALL.md.
#
# Usage:
#   ./build.sh
#   ./build.sh --libreoffice /usr/lib64/libreoffice --jdk ~/opt/jdk8

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
libreoffice="${LO_HOME:-}"
jdk="${JAVA_HOME:-}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        -l|--libreoffice) libreoffice="$2"; shift 2 ;;
        -j|--jdk) jdk="$2"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 1 ;;
    esac
done

if [[ -z "$libreoffice" ]]; then
    for candidate in "$HOME"/libreoffice[0-9]*.[0-9]* /opt/libreoffice[0-9]*.[0-9]* /usr/lib64/libreoffice /usr/lib/libreoffice; do
        [[ -d "$candidate" ]] && libreoffice="$candidate" && break
    done
fi
if [[ -z "$libreoffice" ]]; then
    echo "LibreOffice install not found; pass --libreoffice <dir> or set LO_HOME" >&2
    exit 1
fi

# --- Tool locations --------------------------------------------------------
sdk_bin="$libreoffice/sdk/bin"
program="$libreoffice/program"
types="$program/types.rdb"
uw="$sdk_bin/unoidl-write"
jmaker="$sdk_bin/javamaker"

if [[ -n "$jdk" ]]; then
    javac="$jdk/bin/javac"
    jar="$jdk/bin/jar"
    [[ -x "$javac" ]] || { echo "javac not found under --jdk/JAVA_HOME: $javac" >&2; exit 1; }
else
    javac=javac
    jar=jar
fi

for t in "$uw" "$jmaker"; do
    [[ -x "$t" ]] || { echo "SDK tool not found: $t  (check --libreoffice)" >&2; exit 1; }
done
[[ -f "$types" ]] || { echo "types.rdb not found: $types  (check --libreoffice)" >&2; exit 1; }

# unoidl-write and javamaker need the LibreOffice program dir on PATH for .so libs.
export PATH="$program:$PATH"
export LD_LIBRARY_PATH="$program${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

# --- Clean staging -----------------------------------------------------------
build="$root/build"
gen_dir="$build/gen"
cls_dir="$build/classes"
type_dir="$build/types"
stage="$build/oxt"

rm -rf "$gen_dir" "$cls_dir" "$type_dir" "$stage"
mkdir -p "$gen_dir" "$cls_dir" "$type_dir" "$stage/types" "$stage/config" "$stage/META-INF"

# --- 1. Compile IDL -> type library -----------------------------------------
echo '[1/5] unoidl-write : idl -> build/types/XMlb.rdb'
rdb="$type_dir/XMlb.rdb"
"$uw" "$types" "$root/idl" "$rdb"

# --- 2. Generate Java bindings from the type library ------------------------
echo '[2/5] javamaker    : build/types/XMlb.rdb -> build/gen'
"$jmaker" -nD -Gc -O "$gen_dir" -X "$types" "$rdb"

# --- 3. Compile Java sources -------------------------------------------------
echo '[3/5] javac        : src -> build/classes'
sdk_classes="$program/classes"
cp="$gen_dir:$sdk_classes/*"
mapfile -t sources < <(find "$root/src" -name '*.java')
# JDK 8's javac has no --release flag (added in JDK 9); it only ever targets 8
# anyway, so fall back to -source/-target on it.
javac_version="$("$javac" -version 2>&1 | grep -oE '[0-9]+' | head -1)"
if [[ "$javac_version" != "1" && "$javac_version" -ge 9 ]]; then
    release_flags=(--release 8)
else
    release_flags=(-source 8 -target 8)
fi
"$javac" "${release_flags[@]}" -cp "$cp" -d "$cls_dir" "${sources[@]}"

# --- 4. Package the jar (with RegistrationClassName manifest) ---------------
echo '[4/5] jar          : classes + bindings -> build/oxt/mlb.jar'
mlb_jar="$stage/mlb.jar"
mf="$root/registration/MANIFEST.MF"
# Merge classes + bindings into one tree first: this JDK's `jar` tool rejects
# duplicate directory entries (e.g. com/example/mlb/) when given as two
# separate -C trees, even though the class files themselves don't collide.
jar_dir="$build/jarstage"
rm -rf "$jar_dir"
mkdir -p "$jar_dir"
cp -r "$cls_dir"/. "$jar_dir"/
cp -r "$gen_dir"/. "$jar_dir"/
"$jar" cfm "$mlb_jar" "$mf" -C "$jar_dir" .

# --- 5. Stage remaining package files and zip the .oxt ----------------------
echo '[5/5] zip          : staging -> build/MLB.oxt'
cp "$rdb" "$stage/types/XMlb.rdb"
cp "$root/registration/CalcAddIns.xcu" "$stage/config/CalcAddIns.xcu"
cp "$root/registration/description.xml" "$stage/description.xml"
cp "$root/registration/manifest.xml" "$stage/META-INF/manifest.xml"

oxt="$build/MLB.oxt"
rm -f "$oxt"
( cd "$stage" && zip -q -r "$oxt" . )

echo
echo "Built $oxt"
echo 'Install with:  unopkg add --force build/MLB.oxt   (see docs/INSTALL.md)'
