#!/usr/bin/env bash
# Builds the desktop client and packages it for the current OS as a self-contained app image with a
# bundled, jlink-trimmed Java runtime, so it runs offline without a Java installation:
#
#   Windows  telemetry-analyzer-<version>-windows-x64.zip     Telemetry Analyzer/Telemetry Analyzer.exe
#   macOS    telemetry-analyzer-<version>-macos-<arch>.zip    Telemetry Analyzer.app
#   Linux    telemetry-analyzer-<version>-linux-x64.tar.gz    telemetry-analyzer/bin/telemetry-analyzer
#
# The archive is written to desktop/target/dist. Used locally and by
# .github/workflows/desktop-release.yml.
#
# Environment: JAVA_HOME must be a full JDK 21+ (jdeps, jlink, jpackage); it is also the runtime that
# gets bundled. MVN optionally names the Maven command; otherwise mvn on the PATH or the copy under
# backend/.mvn is used. Arguments are passed on to Maven, e.g. -DskipTests.
set -eo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
target="$root/desktop/target"
work="$target/jpackage"
dist="$target/dist"
main_class=com.telemetry.desktop.Launcher

if [ -z "$JAVA_HOME" ]; then
  echo "JAVA_HOME is not set; point it to a JDK 21+ that includes jpackage." >&2
  exit 1
fi
jdk_bin="$JAVA_HOME/bin"
if command -v cygpath >/dev/null 2>&1; then
  jdk_bin=$(cygpath -u "$jdk_bin")
fi
for tool in jdeps jlink jpackage jar; do
  if [ ! -x "$jdk_bin/$tool" ] && [ ! -x "$jdk_bin/$tool.exe" ]; then
    echo "$jdk_bin has no $tool; JAVA_HOME must be a full JDK 21+, not a JRE or a JetBrains runtime." >&2
    exit 1
  fi
done

mvn_cmd() {
  if [ -n "$MVN" ]; then
    "$MVN" "$@"
  elif command -v mvn >/dev/null 2>&1; then
    mvn "$@"
  else
    sh "$root/backend/.mvn/apache-maven-3.9.9/bin/mvn" "$@"
  fi
}

case "$(uname -s)" in
  MINGW* | MSYS* | CYGWIN*) os=windows ;;
  Darwin) os=macos ;;
  Linux) os=linux ;;
  *) echo "Unsupported OS: $(uname -s)" >&2; exit 1 ;;
esac
case "$(uname -m)" in
  x86_64 | amd64) arch=x64 ;;
  arm64 | aarch64) arch=aarch64 ;;
  *) arch=$(uname -m) ;;
esac
display_name="Telemetry Analyzer"
# Linux launchers are conventionally lower-case without spaces
if [ "$os" = linux ]; then
  app_name=telemetry-analyzer
else
  app_name=$display_name
fi

if [ "$os" = windows ]; then
  # jpackage edits the launcher's resources through Win32 calls limited to MAX_PATH (259 characters)
  exe_path=$(cygpath -w "$work/image/$app_name/$app_name.exe")
  # cygpath marks paths over the limit with the \\?\ long-path prefix
  exe_path=${exe_path#\\\\\?\\}
  if [ "${#exe_path}" -gt 259 ]; then
    echo "The checkout path is too long for jpackage: $exe_path has ${#exe_path} characters (at most 259)." >&2
    echo "Move or clone the repository to a shorter path, such as C:\\src\\Telemetry-Analyzer." >&2
    exit 1
  fi
fi

# clean: target/lib must not keep jars of replaced dependency versions
mvn_cmd -B -ntp -f "$root/pom.xml" -pl desktop -am clean package "$@"

version=$(mvn_cmd -q -B -f "$root/pom.xml" -pl desktop help:evaluate -Dexpression=project.version -DforceStdout | tr -d '\r')
# jpackage accepts numeric versions only (0.2.0-SNAPSHOT -> 0.2.0)
app_version=${version%%-*}
if ! [[ "$app_version" =~ ^[0-9]+(\.[0-9]+)*$ ]]; then
  echo "Cannot derive a numeric app version from '$version'." >&2
  exit 1
fi
main_jar="motorsport-telemetry-desktop-$version.jar"

rm -rf "$work" "$dist"
mkdir -p "$work/input" "$dist"
# same layout as target/: the jar's manifest class path points to lib/
cp "$target/$main_jar" "$work/input/"
cp -R "$target/lib" "$work/input/lib"

modules=$("$jdk_bin/jdeps" --print-module-deps --ignore-missing-deps --multi-release 21 \
  "$work/input/$main_jar" "$work/input/lib/"*.jar | tail -n 1 | tr -d '\r')
echo "Runtime modules: $modules"
# --strip-native-commands drops java(.exe) & co., as jpackage does for the runtimes it links itself
"$jdk_bin/jlink" --add-modules "$modules" --strip-debug --no-header-files --no-man-pages \
  --strip-native-commands --output "$work/runtime"

if [ "$os" = macos ] && [ "${app_version%%.*}" = 0 ]; then
  # macOS bundle versions must not start with 0, so 0.x builds keep jpackage's default (1.0)
  app_version=""
fi
# Windows shows the description as the process name (Task Manager) and the vendor as the company
"$jdk_bin/jpackage" --type app-image --name "$app_name" ${app_version:+--app-version "$app_version"} \
  --description "$display_name" --vendor "Tóth Árpád Zsolt" \
  --input "$work/input" --main-jar "$main_jar" --main-class "$main_class" \
  --runtime-image "$work/runtime" --dest "$work/image"

base="telemetry-analyzer-$version-$os-$arch"
case "$os" in
  windows)
    # Git Bash has no zip; the JDK's jar tool writes plain zip files
    (cd "$work/image" && "$jdk_bin/jar" --create --no-manifest --file "$dist/$base.zip" "$app_name")
    ;;
  macos)
    # ditto keeps the bundle's symlinks, permissions and ad-hoc code signature intact
    ditto -c -k --keepParent "$work/image/$app_name.app" "$dist/$base.zip"
    ;;
  linux)
    tar -C "$work/image" -czf "$dist/$base.tar.gz" "$app_name"
    ;;
esac

echo "Packaged:"
ls -l "$dist"
