#!/usr/bin/env bash
#
# Launches RuneLite with this plugin side-loaded.
#
# The client has to run on the Windows side: it needs a real GPU and the Windows
# ~/.runelite profile, and Gradle's JavaExec cannot start a Windows JVM from a WSL path
# anyway (cmd.exe refuses UNC working directories). So this hands off to gradlew.bat,
# running from the C: path that WSL sees through the symlink in ~/projects.
#
# Compiling still works fine from WSL — ./gradlew build is the fast inner loop, and this
# script is for when you want to see it in the client.
#
# Usage:
#   ./run-client.sh            # build and launch
#   ./run-client.sh --refresh  # also re-resolve dependencies
#   ./run-client.sh --debug    # also listen on port 5005 for a debugger
#
# --debug lets an IDE hot-swap changed classes into the running client, so a relaunch is
# only needed for changes hot-swap cannot take. See "Iterating without relaunching" in
# README.md for what that covers and what it does not.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"

if [[ "$repo_root" != /mnt/* ]]; then
	cat >&2 <<-EOF
	This repo lives at $repo_root, which Windows cannot reach.

	The source has to sit on the Windows filesystem for the client to launch, with a
	symlink into WSL for editing:

	    mv "$repo_root" /mnt/c/Users/\$USER/projects/Doogle-Maps
	    ln -s /mnt/c/Users/\$USER/projects/Doogle-Maps "$repo_root"
	EOF
	exit 1
fi

windows_root="$(wslpath -w "$repo_root")"

# Keep Windows Gradle entirely out of the WSL side's .gradle and build/ directories. The
# daemon locks its project cache, and a running client holds its class files open, neither
# of which WSL can then delete. See the note in build.gradle.
gradle_args=(runClient --project-cache-dir .gradle-windows -PbuildSuffix=windows)
for arg in "$@"; do
	case "$arg" in
		--refresh) gradle_args+=(--refresh-dependencies) ;;
		--debug)   gradle_args+=(-PdebugClient) ;;
		*)
			echo "unknown option: $arg" >&2
			echo "usage: ./run-client.sh [--refresh] [--debug]" >&2
			exit 1
			;;
	esac
done

echo "Launching RuneLite from $windows_root"
echo "(first run downloads the RuneLite deps into the Windows Gradle cache, so give it a minute)"

# cmd.exe cannot cd into a \\wsl.localhost path, hence pushd on the Windows path.
cmd.exe /c "pushd ${windows_root} && gradlew.bat ${gradle_args[*]}"
