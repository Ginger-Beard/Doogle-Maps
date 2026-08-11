#!/usr/bin/env bash
#
# Hot-swaps changed classes into a client that was launched with ./run-client.sh --debug,
# without an IDE. Recompiles on the Windows side (the build the client is running from),
# then attaches jdb to the debug port and redefines whatever the compile rewrote.
#
# Usage:
#   ./hotswap.sh        # recompile and swap changed classes
#
# Same limit as the IDE route (see "Iterating without relaunching" in DEVELOPMENT.md):
# stock HotSwap replaces method bodies only. Adding or removing a field or method,
# changing a signature, or editing an enum makes jdb report a schema-change error, and
# that change needs a client relaunch. Classes the client has not loaded yet also refuse
# to redefine, which is harmless — they load fresh from disk on first use.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"

if [[ "$repo_root" != /mnt/* ]]; then
	echo "This script only makes sense on the Windows-filesystem checkout (see run-client.sh)." >&2
	exit 1
fi

windows_root="$(wslpath -w "$repo_root")"
build_dir="$repo_root/build-windows"
# Main classes only. The test tree holds the launcher and the unit tests, which the running
# client never loads mid-session - swapping them was 30 extra redefines of noise per run.
class_dirs=("$build_dir/classes/java/main")
pending_file="$build_dir/.hotswap-pending"

# 1. Recompile into the build directory the running client loaded from. `classes` also
# runs processResources, so edited resources are picked up the next time the plugin's
# classloader reads them.
before=$(date +%s)
cmd.exe /c "pushd ${windows_root} && gradlew.bat classes --project-cache-dir .gradle-windows -PbuildSuffix=windows"

# 2. Collect what that compile rewrote — Gradle only rewrites class files whose sources
# recompiled, so "newer than the compile started" is exactly the changed set. The one-second
# slack absorbs the mount's timestamp granularity. A pending file from a run whose swap
# failed (client not up yet, usually) is folded back in so those classes are not lost.
changed=()
for dir in "${class_dirs[@]}"; do
	[[ -d "$dir" ]] || continue
	while IFS= read -r f; do
		changed+=("$f")
	done < <(find "$dir" -name '*.class' -newermt "@$((before - 1))")
done
if [[ -f "$pending_file" ]]; then
	while IFS= read -r f; do
		[[ -f "$f" ]] && changed+=("$f")
	done < "$pending_file"
fi

if [[ ${#changed[@]} -eq 0 ]]; then
	rm -f "$pending_file"
	echo "Nothing recompiled — nothing to swap."
	exit 0
fi

# 3. Turn each class file into a jdb redefine command. Windows paths, since jdb runs on
# the Windows side — the debug port is bound to Windows loopback on purpose (JDWP has no
# authentication), so WSL cannot reach it and jdb has to go through cmd.exe like the
# client launch does.
cmds="$build_dir/hotswap-commands.txt"
: > "$cmds"
classes=()
while IFS= read -r f; do
	for dir in "${class_dirs[@]}"; do
		if [[ "$f" == "$dir"/* ]]; then
			rel="${f#"$dir"/}"
			cls="${rel%.class}"
			cls="${cls//\//.}"
			win_rel="${f#"$repo_root"/}"
			classes+=("$cls")
			echo "redefine $cls ${windows_root}\\${win_rel//\//\\}" >> "$cmds"
			break
		fi
	done
done < <(printf '%s\n' "${changed[@]}" | awk '!seen[$0]++')
echo "quit" >> "$cmds"

echo "Swapping ${#classes[@]} class(es): ${classes[*]}"

# 4. Attach and redefine. quit only detaches — the client keeps running and the port goes
# back to listening for the next swap.
#
# The explicit SocketAttach connector, not -attach: on Windows, jdb's default transport is
# shared memory, so "-attach 127.0.0.1:5005" was read as a shmem address name and died with
# "shmemBase_attach failed" without ever touching the port.
out="$(cmd.exe /c "jdb -connect com.sun.jdi.SocketAttach:hostname=127.0.0.1,port=5005 < ${windows_root}\\build-windows\\hotswap-commands.txt" 2>&1)" || true

if grep -qiE 'unable to attach|connection refused|handshake failed' <<< "$out"; then
	printf '%s\n' "${changed[@]}" | awk '!seen[$0]++' > "$pending_file"
	echo "$out" >&2
	echo >&2
	echo "Could not attach to 127.0.0.1:5005 — is the client running via ./run-client.sh --debug?" >&2
	echo "The changed classes are remembered and will swap on the next successful run." >&2
	exit 1
fi

rm -f "$pending_file"

if grep -qiE 'error|exception|not implemented|failed' <<< "$out"; then
	echo "$out"
	echo
	echo "jdb reported problems above. Schema changes (new/removed fields or methods," >&2
	echo "signature or enum edits) cannot hot-swap and need a client relaunch;" >&2
	echo "'redefine' failures for classes the client never loaded are harmless." >&2
	exit 1
fi

echo "Done — the running client is on the new code."
echo "If the change touched a constructor or startUp, toggle the plugin off and on to apply it."
