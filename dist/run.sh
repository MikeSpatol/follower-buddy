#!/bin/sh
# Follower Buddy - runnable bundle. See INSTALL-BUNDLE.md.
#
# Everything needed is in lib/. No Gradle, no build, no download.
# -ea is required: RuneLite's loadBuiltin hard-fails without assertions.

cd "$(dirname "$0")" || exit 1

if ! command -v java >/dev/null 2>&1; then
    echo
    echo "Java was not found."
    echo
    echo "Install Java 17 or newer from https://adoptium.net/ then run this again."
    echo
    exit 1
fi

echo "Starting RuneLite with Follower Buddy..."
echo

exec java -ea -cp "lib/*" com.follower.FollowerPluginTest --developer-mode
