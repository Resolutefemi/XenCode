#!/bin/sh
# Container environment script (generic): one script, two entry points.
#   1) First entry into the terminal: shows the initialization menu on the PTY (auto install / environment setup /
#      manual install / exit); once the user finishes, the /.provisioned marker is written and the menu never shows again.
#   2) Running `xencode` inside the container (equivalent to `provision.sh --env`): opens the "Environment setup"
#      menu directly, installing dev environments by scenario; reusable anytime, unaffected by the init marker.
# Both entry points share one logic: install runtimes via the container's package manager (apk/apt-get/dnf/yum/pacman), with optional China mirror switch.
# Extracted by the app at startup to ~/.xencode/provision.sh (/root/.xencode/provision.sh inside the container, visible via the -b bind);
# shared capabilities (package manager detection / package name mapping / version detection / mirror switching / scenarios) live in lib/env-common.sh, extracted together with this script.
# After changing package lists / install logic / mirrors, bump LinuxContainerEngine.PROVISION_VERSION by 1 so existing devices re-run initialization.
# Note: the apk repo branch v3.21 must match the alpine-rootfs version in assets (ContainerInstaller.INSTALL_VERSION).

PROVISION_VERSION="provision-script-v10"
PROVISION_SKIPPED="provision-script-skipped"
MARKER="/.provisioned"

# -- Load the shared library (package name mapping / mirror switching / scenarios, etc.). Located relative to the script's own directory:
# the production script is /root/.xencode/provision.sh with the library at /root/.xencode/lib/; the workspace draft copy (xencode-env/)
# has the same structure, so the same script runs in both. If missing, do not block: prompt to restart the app and re-enter the terminal. --
SELF_DIR=$(dirname "$0")
[ "${SELF_DIR#/}" = "$SELF_DIR" ] && SELF_DIR="$(pwd)/$SELF_DIR"
AICODE_LIB_DIR="$SELF_DIR/lib"
LIB_FILE="$AICODE_LIB_DIR/env-common.sh"
if [ ! -f "$LIB_FILE" ]; then
    AICODE_LIB_DIR="${HOME:-/root}/.xencode/lib"
    LIB_FILE="$AICODE_LIB_DIR/env-common.sh"
fi
if [ ! -f "$LIB_FILE" ]; then
    echo "Environment tools are not ready. Restart the app and open the terminal again." >&2
    exit 0
fi
. "$LIB_FILE"

detect_pmgr

# -- Overview of installed runtimes (for the environment setup menu) --
list_installed() {
    echo ""
    echo "Installed runtimes:"
    for c in "node:Node.js" "python3:Python 3" "java:Java" "go:Go" "rustc:Rust" "php:PHP" "git:Git" "rg:ripgrep"; do
        cmd=${c%%:*}
        name=${c#*:}
        if command -v "$cmd" >/dev/null 2>&1; then
            ver=$("$cmd" --version 2>&1 | head -1)
            echo "  · $name: $ver"
        else
            echo "  · $name: (not installed)"
        fi
    done
    [ -x "$HOME/android/sdk/cmdline-tools/latest/bin/sdkmanager" ] && echo "  · Android SDK: $HOME/android/sdk"
    [ -x "$HOME/.local/flutter-$FLUTTER_VERSION/bin/flutter" ] && echo "  · Flutter SDK: $HOME/.local/flutter-$FLUTTER_VERSION"
}

# -- Environment setup menu (scenario-based): shared by the init menu "Environment setup" and the `xencode` command --
env_install_menu() {
    TOTAL=$(echo $SCENARIO_IDS | wc -w)
    CUSTOM_IDX=$((TOTAL + 1))
    MIRROR_IDX=$((TOTAL + 2))
    LIST_IDX=$((TOTAL + 3))
    EXIT_IDX=$((TOTAL + 4))
    while :; do
        echo ""
        echo "${C_CYAN}Environment setup${C_RESET} — choose a development environment"
        echo "${C_YELLOW}──────────────────────────────${C_RESET}"
        i=1
        for id in $SCENARIO_IDS; do
            printf "  ${C_GREEN}%d.${C_RESET} %s\n" "$i" "$(scenario_name "$id")"
            i=$((i + 1))
        done
        printf "  ${C_GREEN}%d.${C_RESET} Custom install (pick packages)\n" "$CUSTOM_IDX"
        printf "  ${C_GREEN}%d.${C_RESET} Change software mirror only\n" "$MIRROR_IDX"
        printf "  ${C_GREEN}%d.${C_RESET} Show installed runtimes\n" "$LIST_IDX"
        printf "  ${C_DIM}%d. Exit${C_RESET}\n" "$EXIT_IDX"
        echo "${C_YELLOW}──────────────────────────────${C_RESET}"
        printf "Select: "
        if ! read choice; then
            echo ""
            break
        fi
        case "$choice" in
            ""|*[!0-9]*)
                echo "${C_RED}Invalid input. Please choose again.${C_RESET}"
                continue
                ;;
        esac
        if [ "$choice" -ge 1 ] && [ "$choice" -le "$TOTAL" ]; then
            id=$(echo $SCENARIO_IDS | awk -v i="$choice" '{print $i}')
            plog "env install: scenario $id"
            install_scenario "$id"
            rc=$?
            case "$rc" in
                0) echo ""; echo "${C_GREEN}Install complete.${C_RESET}" ;;
                2) echo ""; echo "Cancelled. Back to menu." ;;
                *) echo ""; echo "${C_RED}Install failed. Retry or pick another mirror.${C_RESET}" ;;
            esac
        elif [ "$choice" -eq "$CUSTOM_IDX" ]; then
            plog "env install: custom"
            install_custom
            rc=$?
            case "$rc" in
                0) echo ""; echo "${C_GREEN}Install complete.${C_RESET}" ;;
                2) echo ""; echo "Cancelled. Back to menu." ;;
                *) echo ""; echo "${C_RED}Install failed. Retry or pick another mirror.${C_RESET}" ;;
            esac
        elif [ "$choice" -eq "$MIRROR_IDX" ]; then
            plog "env install: mirror only"
            ask_mirror
            echo "Software mirror updated."
        elif [ "$choice" -eq "$LIST_IDX" ]; then
            list_installed
        elif [ "$choice" -eq "$EXIT_IDX" ]; then
            plog "env install: exit"
            break
        else
            echo "${C_RED}Invalid input. Please choose again.${C_RESET}"
        fi
    done
}

# == Entry point 2: `xencode` (provision.sh --env) -> opens the environment setup menu directly, unaffected by the init marker ==
if [ "$1" = "--env" ]; then
    plog "xencode opened env menu: PMGR=${PMGR:-unknown}"
    if [ -z "$PMGR" ]; then
        echo "${C_RED}Unknown package manager (apk/apt-get/dnf/yum/pacman). Cannot install packages.${C_RESET}"
        exit 1
    fi
    env_install_menu
    exit 0
fi

# == Entry point 1: first-run initialization when entering the terminal ==
plog "provision.sh start: version=$PROVISION_VERSION HOME=$HOME uid=$(id -u 2>/dev/null) PATH=$PATH"

# -- Host supplementary gid fix: proot passes the host process supplementary groups (Android AID 3003 inet,
# 9997 everybody, gids derived from the app own uid, etc.) into the container; /etc/group having no names for
# them makes commands like groups warn (cannot find name for group ID xxx). Idempotent backfill: the gid_
# namespace is cleared then refilled to avoid stale leftovers; placed before the MARKER check so it runs on
# every terminal entry (also on already-provisioned devices), automatically tracking gid changes. All failures are silent and never block entering the shell.
sed -i '/^gid_/d' /etc/group 2>/dev/null
for g in $(id -G 2>/dev/null); do
    grep -q ":x:$g:" /etc/group 2>/dev/null || echo "gid_$g:x:$g:" >> /etc/group 2>/dev/null
done

# Exit immediately if already provisioned for the current version, or the user chose manual install (skip)
if [ -f "$MARKER" ]; then
    state=$(cat "$MARKER" 2>/dev/null)
    if [ "$state" = "$PROVISION_VERSION" ]; then plog "marker already $PROVISION_VERSION, skip menu"; exit 0; fi
    if [ "$state" = "$PROVISION_SKIPPED" ]; then plog "user chose manual install, skip menu"; exit 0; fi
fi

plog "show init menu: PMGR=${PMGR:-unknown}"

while :; do
    echo ""
    cat <<EOF
${C_CYAN}    _    ___ ____ ___  ____  _____ ${C_RESET}
${C_CYAN}   / \  |_ _/ ___/ _ \|  _ \| ____|${C_RESET}
${C_CYAN}  / _ \  | | |  | | | | | | |  _|  ${C_RESET}
${C_CYAN} / ___ \ | | |__| |_| | |_| | |___ ${C_RESET}
${C_CYAN}/_/   \_\___\____\___/|____/|_____|${C_RESET}
${C_YELLOW}══════════════════════════════════════════════${C_RESET}
${C_BOLD}  Container setup · choose an install method${C_RESET}
${C_YELLOW}══════════════════════════════════════════════${C_RESET}
  ${C_GREEN}1. Auto-install dependencies${C_RESET} (recommended)
  ${C_GREEN}2. Environment setup${C_RESET} (pick a runtime scenario)
  ${C_BOLD}3. Manual install${C_RESET} (do not show again)
  ${C_DIM}4. Exit${C_RESET}
${C_YELLOW}══════════════════════════════════════════════${C_RESET}
${C_DIM}  Tip: run xencode anytime in the terminal to open environment setup${C_RESET}
EOF
    printf "Select: "
    if ! read choice; then
        echo ""
        echo "Input interrupted. Leaving setup."
        break
    fi
    case "$choice" in
        1)
            plog "user chose 1 (auto install)"
            echo ""
            echo "${C_YELLOW}Install notes${C_RESET}:"
            echo "  · This can take a while. Enable Keepalive and keep the app in the foreground"
            echo "  · Do not switch away or lock the screen, or the system may kill the install"
            echo ""
            install_scenario common
            rc=$?
            case "$rc" in
                0)
                    echo "$PROVISION_VERSION" > "$MARKER"
                    plog "auto install done, wrote marker $PROVISION_VERSION"
                    echo ""
                    echo "${C_GREEN}Base dependencies installed. You are ready to go!${C_RESET}"
                    break
                    ;;
                2)
                    printf '\033[2J\033[H'
                    echo "Install cancelled. Back to menu."
                    ;;
                *)
                    echo ""
                    echo "${C_RED}Install failed. Ask the AI to read this terminal output, or reopen the terminal to retry.${C_RESET}"
                    break
                    ;;
            esac
            ;;
        2)
            plog "user chose 2 (env setup)"
            echo ""
            echo "${C_YELLOW}Install notes${C_RESET}:"
            echo "  · This can take a while. Enable Keepalive and keep the app in the foreground"
            echo "  · Do not switch away or lock the screen, or the system may kill the install"
            env_install_menu
            echo "$PROVISION_VERSION" > "$MARKER"
            plog "env setup done, wrote marker $PROVISION_VERSION"
            echo ""
            echo "${C_GREEN}Environment setup complete. You are ready to go!${C_RESET}"
            break
            ;;
        3)
            plog "user chose 3 (manual install)"
            echo ""
            echo "${C_YELLOW}Manual install notes${C_RESET}:"
            echo "  · ripgrep (rg) is required; missing it hurts the workflow"
            echo "  · git is required for visual version control"
            echo "  · MCP tools need runtimes such as python3 / nodejs"
            echo ""
            printf "Confirm manual install and stop showing this menu? [y/N]: "
            read manual_confirm
            if [ "$manual_confirm" = "y" ] || [ "$manual_confirm" = "Y" ]; then
                echo "$PROVISION_SKIPPED" > "$MARKER"
                plog "confirmed manual install, wrote skip marker"
                echo "Manual install selected. This menu will not show again."
                break
            else
                printf '\033[2J\033[H'
                echo "Cancelled. Back to menu."
            fi
            ;;
        4)
            plog "user chose 4 (exit, show again next time)"
            echo "Exited. This menu will show again next time."
            break
            ;;
        *)
            printf '\033[2J\033[H'
            echo "${C_RED}Invalid input. Please choose again.${C_RESET}"
            ;;
    esac
done
plog "init menu finished"
git_config