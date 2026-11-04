#!/bin/sh
# 容器环境脚本（通用）：一个脚本，两个入口。
#   1) 首次进入终端：在 PTY 上弹出初始化菜单（自动安装 / 环境安装 / 手动安装 / 退出），用户完成
#      后写 /.provisioned 标记，之后进入终端不再弹。
#   2) 容器内执行命令 `aicode`（等价于 `provision.sh --env`）：直接打开「环境安装」菜单，按场景
#      安装开发环境，可反复使用，不受初始化标记影响。
# 两个入口共用同一套逻辑：按容器内包管理器（apk/apt-get/dnf/yum/pacman）安装运行时，并可换国内镜像源。
# 由 App 启动时提取到 ~/.aicode/provision.sh（容器内 /root/.aicode/provision.sh，经 -b 绑定可见）；
# 公共能力（包管理器探测/包名映射/版本探测/换源/场景）在 lib/env-common.sh，与本脚本一并提取。
# 修改包清单/安装逻辑/镜像源后，需同步在 LinuxContainerEngine.PROVISION_VERSION 上 +1 触发存量设备重跑。
# 注意：apk 源分支 v3.21 需与 assets 内 alpine-rootfs 版本（ContainerInstaller.INSTALL_VERSION）保持一致。

PROVISION_VERSION="provision-script-v10"
PROVISION_SKIPPED="provision-script-skipped"
MARKER="/.provisioned"

# ── 载入公共库（包名映射/换源/场景等）。按脚本自身所在目录定位：生产脚本在 /root/.aicode/provision.sh、
# 库在 /root/.aicode/lib/；工作区草稿副本（aicode-env/）结构相同，故同一份脚本两处都能跑。
# 缺失时不阻塞：提示重启 App 重新提取后重进终端。──
SELF_DIR=$(dirname "$0")
[ "${SELF_DIR#/}" = "$SELF_DIR" ] && SELF_DIR="$(pwd)/$SELF_DIR"
AICODE_LIB_DIR="$SELF_DIR/lib"
LIB_FILE="$AICODE_LIB_DIR/env-common.sh"
if [ ! -f "$LIB_FILE" ]; then
    AICODE_LIB_DIR="${HOME:-/root}/.aicode/lib"
    LIB_FILE="$AICODE_LIB_DIR/env-common.sh"
fi
if [ ! -f "$LIB_FILE" ]; then
    echo "Environment tools are not ready. Restart the app and open the terminal again." >&2
    exit 0
fi
. "$LIB_FILE"

detect_pmgr

# ── 已安装运行时一览（环境安装菜单用）──
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

# ── 环境安装菜单（场景化）：初始化菜单的「环境安装」与 `aicode` 命令共用 ──
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

# ══ 入口 2：`aicode`（provision.sh --env）→ 直接打开环境安装菜单，不受初始化标记影响 ══
if [ "$1" = "--env" ]; then
    plog "aicode opened env menu: PMGR=${PMGR:-unknown}"
    if [ -z "$PMGR" ]; then
        echo "${C_RED}Unknown package manager (apk/apt-get/dnf/yum/pacman). Cannot install packages.${C_RESET}"
        exit 1
    fi
    env_install_menu
    exit 0
fi

# ══ 入口 1：首次进入终端的初始化流程 ══
plog "provision.sh start: version=$PROVISION_VERSION HOME=$HOME uid=$(id -u 2>/dev/null) PATH=$PATH"

# ── 宿主 supplementary gid 修复：proot 会把宿主进程的补充组（Android AID 3003 inet、
# 9997 everybody、App 自身 uid 派生 gid 等）透传进容器，/etc/group 查不到名字会让
# groups 等命令报警（cannot find name for group ID xxx）。幂等补行：gid_ 命名空间先清再补
# 防历史残留；位于 MARKER 检查之前，每次进终端都执行（已配置设备同样生效），gid 变化自动跟上。
# 全部静默失败，不阻塞进入 shell。
sed -i '/^gid_/d' /etc/group 2>/dev/null
for g in $(id -G 2>/dev/null); do
    grep -q ":x:$g:" /etc/group 2>/dev/null || echo "gid_$g:x:$g:" >> /etc/group 2>/dev/null
done

# 已按当前版本完成或用户选择手动安装（跳过）则直接退出
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
${C_DIM}  Tip: run aicode anytime in the terminal to open environment setup${C_RESET}
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