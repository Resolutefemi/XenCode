#!/bin/sh
# XenCode container environment shared library: package manager detection, package name mapping, version detection, mirror switching, scenario definitions and installs.
#
# Sourced by the container environment script provision.sh — its two entry points (the first-run initialization menu,
# and the environment setup menu opened by the `xencode` command) share this library. Logic is centralized here; one change
# takes effect in both entries. If package lists / install logic change and existing devices must re-run initialization, also bump LinuxContainerEngine.PROVISION_VERSION.

# Shared library directory: used to locate android-sdk.sh and scenarios/. provision.sh sets SELF_DIR before sourcing and passes it in;
# when sourced standalone, falls back to ~/.xencode/lib.
: "${AICODE_LIB_DIR:=${HOME:-/root}/.xencode/lib}"

# Mirror candidates (priority-ordered; mirror switching auto-probes and skips unavailable ones; Aliyun is last since it 403s all server access, and probing skips it)
MIRRORS="mirrors.huaweicloud.com mirrors.tuna.tsinghua.edu.cn mirrors.ustc.edu.cn mirrors.cloud.tencent.com mirrors.aliyun.com"
MIRROR=""
# Base tools (not part of custom selection, always installed): git/ripgrep underpin AI workflows and version control; bash/curl are general dependencies
BASE_PKGS="bash curl ripgrep git"

# -- Diagnostic log: written to $HOME/.xencode/provision.log, visible on the host (/root/.xencode inside the container is bound to the app private dir).
# When the terminal PTY fails to start or hangs, the app-side "container diagnostics" appends the tail of this file to the log, to tell which step
# the script reached and whether the menu ever appeared. Over 256KB, keep only the last 64KB so it cannot grow unbounded from one appended line per terminal entry. All failures are silent and never block initialization.
LOG_FILE="$HOME/.xencode/provision.log"
plog() {
    [ -n "$HOME" ] || return 0
    printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null)" "$*" >> "$LOG_FILE" 2>/dev/null || return 0
    sz=$(wc -c < "$LOG_FILE" 2>/dev/null || echo 0)
    if [ "$sz" -gt 262144 ] 2>/dev/null; then
        tail -c 65536 "$LOG_FILE" > "$LOG_FILE.tmp" 2>/dev/null && mv "$LOG_FILE.tmp" "$LOG_FILE" 2>/dev/null
    fi
    return 0
}

# -- Terminal menu colors --
C_BOLD=$(printf '\033[1m')
C_CYAN=$(printf '\033[36m')
C_YELLOW=$(printf '\033[33m')
C_GREEN=$(printf '\033[32m')
C_RED=$(printf '\033[31m')
C_DIM=$(printf '\033[2m')
C_RESET=$(printf '\033[0m')

# -- Package manager detection: run once before the menu, shared by install/plan --
detect_pmgr() {
    if command -v apk >/dev/null 2>&1; then
        PMGR=apk
    elif command -v apt-get >/dev/null 2>&1; then
        PMGR=apt
    elif command -v dnf >/dev/null 2>&1; then
        PMGR=dnf
    elif command -v yum >/dev/null 2>&1; then
        PMGR=yum
    elif command -v pacman >/dev/null 2>&1; then
        PMGR=pacman
    else
        PMGR=
    fi
}

# -- Install a batch of packages with the current package manager --
pkg_add() {
    case "$PMGR" in
        apk)
            apk update
            apk add --no-cache "$@"
            ;;
        apt)
            export DEBIAN_FRONTEND=noninteractive
            apt-get update -y
            apt-get install -y "$@"
            ;;
        dnf) dnf install -y "$@" ;;
        yum) yum install -y "$@" ;;
        pacman) pacman -Sy --noconfirm "$@" ;;
        *)
            echo "Unsupported package manager (apk/apt-get/dnf/yum/pacman)" >&2
            return 1
            ;;
    esac
}

# -- Detect the available openjdk package name under apt (trixie has only 21/25, no 17; bookworm defaults to 17; prefer LTS 21) --
apt_java_headless() {
    for p in openjdk-21-jdk-headless openjdk-17-jdk-headless; do
        if apt-cache policy "$p" 2>/dev/null | grep -q '^  Candidate: [0-9]'; then
            echo "$p"
            return 0
        fi
    done
    echo "openjdk-17-jdk-headless"
}

# -- Runtime -> package name mapping under the current package manager (shared by the custom install plan and the install itself) --
runtime_pkgs() {
    case "$PMGR:$1" in
        apk:node)    echo "nodejs npm" ;;
        apk:python)  echo "python3 py3-pip" ;;
        apk:java)    echo "openjdk17-jdk" ;;
        apk:go)      echo "go" ;;
        apt:node)    echo "nodejs npm" ;;
        apt:python)  echo "python3 python3-pip python3-venv" ;;
        apt:java)    echo "$(apt_java_headless)" ;;
        apt:go)      echo "golang-go" ;;
        dnf:node|yum:node)     echo "nodejs npm" ;;
        dnf:python|yum:python) echo "python3 python3-pip" ;;
        dnf:java|yum:java)     echo "java-17-openjdk-headless" ;;
        dnf:go|yum:go)         echo "golang" ;;
        pacman:node)   echo "nodejs npm" ;;
        pacman:python) echo "python python-pip" ;;
        pacman:java)   echo "jdk17-openjdk" ;;
        pacman:go)     echo "go" ;;
        apk:rust)      echo "rust cargo" ;;
        apk:php)       echo "php83 composer" ;;
        apt:rust)      echo "rustc cargo" ;;
        apt:php)       echo "php-cli composer" ;;
        dnf:rust|yum:rust) echo "rust cargo" ;;
        dnf:php)           echo "php-cli composer" ;;
        yum:php)           echo "php-cli" ;;
        pacman:rust)   echo "rust" ;;
        pacman:php)    echo "php composer" ;;
    esac
}

# -- List a package's available repo versions (one per line, newest first; keeps the full version string for pinning; empty on failure) --
pkg_versions() {
    pkg="$1"
    case "$PMGR" in
        # apk search -v output is "package-version - description..." and -e also matches provides-like packages (e.g. nodejs-current),
        # so filter with "package-digit" to keep only real package rows, then take the first column as the version; multi-source/multi-repo rows are deduped by version order (newest first)
        apk) apk search -e -v "$pkg" 2>/dev/null | grep -E "^$pkg-[0-9]" | sed -n "s/^$pkg-//p" | awk '{print $1}' | sort -Vu -r ;;
        # madison has no header, the first row is data; multiple sources/repos produce multiple rows, deduped by version order (newest first)
        apt) apt-cache madison "$pkg" 2>/dev/null | awk -F'|' '{gsub(/^ +| +$/, "", $2); print $2}' | sort -Vu -r ;;
        dnf) dnf list available --showduplicates "$pkg" 2>/dev/null | awk 'NR>2 {print $2}' | sort -Vu -r ;;
        yum) yum list available "$pkg" 2>/dev/null | awk 'NR>2 {print $2}' | sort -Vu -r ;;
        pacman) pacman -Si "$pkg" 2>/dev/null | sed -n 's/^Version *: //p' ;;
    esac
}

# -- Major-version package candidates: java/php are not locked to one version, list the major-version packages actually in the repo (newest first); other runtimes are single packages --
runtime_candidates() {
    case "$PMGR:$1" in
        # apk search outputs "package-version" which cannot be used directly; instead use a known candidate list + exact existence checks, outputting plain package names
        apk:java)
            for p in openjdk25-jdk openjdk21-jdk openjdk17-jdk openjdk11-jdk openjdk8-jdk; do
                [ -n "$(pkg_versions "$p" | head -1)" ] && echo "$p"
            done
            ;;
        apt:java)  apt-cache search '^openjdk-[0-9]*-jdk-headless$' 2>/dev/null | awk '{print $1}' | sort -V -r ;;
        dnf:java|yum:java)
            for p in java-21-openjdk-headless java-17-openjdk-headless; do
                [ -n "$(pkg_versions "$p" | head -1)" ] && echo "$p"
            done
            ;;
        pacman:java)
            for p in jdk21-openjdk jdk17-openjdk jdk-openjdk; do
                [ -n "$(pkg_versions "$p" | head -1)" ] && echo "$p"
            done
            ;;
        apk:php)
            for p in php84 php83 php82; do
                [ -n "$(pkg_versions "$p" | head -1)" ] && echo "$p"
            done
            ;;
        apt:php)
            for p in php8.4-cli php8.3-cli php8.2-cli; do
                [ -n "$(pkg_versions "$p" | head -1)" ] && echo "$p"
            done
            ;;
        *)
            echo "$(runtime_pkgs "$1" | awk '{print $1}')"
            ;;
    esac
}

# -- Runtime primary package version (display only; default candidate, minus apk's -rN suffix) --
runtime_ver() {
    pkg_versions "$(runtime_candidates "$1" | head -1)" | head -1 | sed 's/-r[0-9]*$//'
}

# -- Refresh package indexes (prerequisite for version detection and install) --
pkg_update() {
    case "$PMGR" in
        apk) apk update ;;
        apt) apt-get update -y ;;
        dnf) dnf makecache -y ;;
        yum) yum makecache -y ;;
        pacman) pacman -Sy --noconfirm ;;
    esac
}

# -- Major-version selection: list available major-version packages (plain names), pick one by number (Enter/invalid input falls back to the first); the result is written to $PICKED_PKG --
pick_candidate() {
    PICKED_PKG=""
    cands=$(runtime_candidates "$1")
    [ -z "$cands" ] && return 1
    n=1
    for c in $cands; do
        echo "    $n) $c"
        n=$((n + 1))
    done
    printf "Pick a major version (number): "
    read ans
    case "$ans" in
        ""|*[!0-9]*) PICKED_PKG=$(echo "$cands" | head -1) ;;
        *) PICKED_PKG=$(echo "$cands" | sed -n "${ans}p" | head -1) ;;
    esac
    [ -z "$PICKED_PKG" ] && PICKED_PKG=$(echo "$cands" | head -1)
}

# -- Runtime primary package name (custom install prefers the chosen candidate; falls back to the default candidate when unset) --
pkg_for() {
    case "$1" in
        node)   [ -n "$node_pkg" ] && echo "$node_pkg" || runtime_candidates node | head -1 ;;
        python) [ -n "$python_pkg" ] && echo "$python_pkg" || runtime_candidates python | head -1 ;;
        java)   [ -n "$java_pkg" ] && echo "$java_pkg" || runtime_candidates java | head -1 ;;
        go)     [ -n "$go_pkg" ] && echo "$go_pkg" || runtime_candidates go | head -1 ;;
        rust)   [ -n "$rust_pkg" ] && echo "$rust_pkg" || runtime_candidates rust | head -1 ;;
        php)    [ -n "$php_pkg" ] && echo "$php_pkg" || runtime_candidates php | head -1 ;;
    esac
}

# -- Version of the selected runtime primary package (display only) --
ver_for() {
    pkg_versions "$(pkg_for "$1")" | head -1 | sed 's/-r[0-9]*$//'
}

# -- Runtime install package list (chosen primary package + companion packages) --
runtime_install() {
    rest=$(runtime_pkgs "$1" | awk '{$1=""; sub(/^ /, ""); print}')
    if [ -n "$rest" ]; then
        echo "$(pkg_for "$1") $rest"
    else
        pkg_for "$1"
    fi
}

# -- One install-plan line: name -> package list (v version when detected; apk's -rN suffix stripped for display) --
plan_line() {
    line="    · $1 → $2"
    if [ -n "$3" ]; then
        disp=$(echo "$3" | sed 's/-r[0-9]*$//')
        line="$line (v$disp)"
    fi
    echo "$line"
}

# -- y/N prompt: $1 prompt text, $2 default (y/n), $3 optional version; y returns 0, n returns 1 --
ask_yn() {
    def="$2"
    while :; do
        if [ "$def" = "y" ]; then
            printf "%s (install by default) [Y/n]: " "$1"
        else
            printf "%s (skip by default) [y/N]: " "$1"
        fi
        read ans
        [ -z "$ans" ] && ans="$def"
        case "$ans" in
            y|Y) return 0 ;;
            n|N) return 1 ;;
            *) echo "  Please enter y or n" ;;
        esac
    done
}

# -- Show the install plan and confirm: $1 is a space-separated runtime list (may be empty); y returns 0, anything else returns 1 --
show_plan() {
    echo ""
    echo "══════════════ Install plan ══════════════"
    echo "  Base tools: $BASE_PKGS"
    runtimes="$1"
    if [ -n "$runtimes" ]; then
        echo "  Runtimes:"
        for r in $runtimes; do
            case "$r" in
                node)   plan_line "Node.js" "$(runtime_install node)" "$(ver_for node)" ;;
                python) plan_line "Python 3" "$(runtime_install python)" "$(ver_for python)" ;;
                java)   plan_line "Java" "$(runtime_install java)" "$(ver_for java)" ;;
                go)     plan_line "Go" "$(runtime_install go)" "$(ver_for go)" ;;
                rust)   plan_line "Rust" "$(runtime_install rust)" "$(ver_for rust)" ;;
                php)    plan_line "PHP" "$(runtime_install php)" "$(ver_for php)" ;;
            esac
        done
    else
        echo "  Runtimes: (none selected, base tools only)"
    fi
    echo "══════════════════════════════════════"
    printf "  Enter y to install, any other key to go back: "
    read ans
    [ "$ans" = "y" ] || [ "$ans" = "Y" ]
}

# -- Mirror selection UI: list mirror candidates for a single pick (1=auto-detect, middle=specific mirror, last=keep default), then switch to the chosen one --
ask_mirror() {
    echo ""
    echo "Choose a software mirror:"
    echo "    1) Auto-detect (recommended)"
    n=2
    for m in $MIRRORS; do
        echo "    $n) $m"
        n=$((n + 1))
    done
    echo "    0) Keep the default mirror"
    printf "Enter a number (Enter = 1): "
    read ans
    case "$ans" in
        ""|*[!0-9]*|1)
            pick_mirror
            ;;
        0)
            echo "Keeping the default mirror."
            return 0
            ;;
        *)
            MIRROR=$(echo "$MIRRORS" | awk -v i="$((ans - 1))" '{print $i}')
            [ -z "$MIRROR" ] && { echo "Invalid number, using auto-detect."; pick_mirror; }
            ;;
    esac
    setup_mirror
}

# -- Custom install: pick runtimes item by item, base tools always installed, executes after plan confirmation --
# Returns: 0=done 1=install failed 2=user cancelled (plan not confirmed)
install_custom() {
    echo ""
    echo "Custom install will add base tools ($BASE_PKGS) plus the runtimes you pick."
    echo ""
    ask_mirror
    echo "Updating package lists..."
    pkg_update || { echo "${C_RED}Failed to update package lists. Check the network and retry.${C_RESET}"; return 1; }
    echo ""
    echo "Choose the packages to install"
    runtimes=""
    node_pkg=""; python_pkg=""; java_pkg=""; go_pkg=""; rust_pkg=""; php_pkg=""
    if ask_yn "Install Node.js?" y; then
        echo "  Available Node.js major versions:"
        pick_candidate node
        node_pkg=$PICKED_PKG
        runtimes="$runtimes node"
    fi
    if ask_yn "Install Python 3?" y; then
        echo "  Available Python 3 major versions:"
        pick_candidate python
        python_pkg=$PICKED_PKG
        runtimes="$runtimes python"
    fi
    if ask_yn "Install Java?" n; then
        echo "  Available Java major versions:"
        pick_candidate java
        java_pkg=$PICKED_PKG
        runtimes="$runtimes java"
    fi
    if ask_yn "Install Go?" n; then
        echo "  Available Go major versions:"
        pick_candidate go
        go_pkg=$PICKED_PKG
        runtimes="$runtimes go"
    fi
    if ask_yn "Install Rust?" n; then
        echo "  Available Rust major versions:"
        pick_candidate rust
        rust_pkg=$PICKED_PKG
        runtimes="$runtimes rust"
    fi
    if ask_yn "Install PHP?" n; then
        echo "  Available PHP major versions:"
        pick_candidate php
        php_pkg=$PICKED_PKG
        runtimes="$runtimes php"
    fi
    if [ -z "$runtimes" ]; then
        echo "No runtime selected. Only base tools ($BASE_PKGS) will be installed."
    fi
    show_plan "$runtimes" || return 2
    echo ""
    echo "Installing selected packages. This may take a few minutes..."
    pkgs="$BASE_PKGS"
    for r in $runtimes; do
        pkgs="$pkgs $(runtime_install "$r")"
    done
    pkg_add $pkgs || return 1
    return 0
}

# -- Probe one URL's HTTP status code (curl preferred, wget fallback, 000 when probing is impossible) --
http_code() {
    if command -v curl >/dev/null 2>&1; then
        curl -s -o /dev/null --max-time 8 -w '%{http_code}' "$1" 2>/dev/null || echo "000"
    elif command -v wget >/dev/null 2>&1; then
        wget -q -T 8 -O /dev/null "$1" 2>/dev/null && echo "200" || echo "000"
    else
        echo "000"
    fi
}

# -- Probe whether mirror site $1 is usable from this container: picks a real content path per package manager;
#    apk requires a direct http 2xx (a 3xx redirects to https, unusable without CA certs in the minirootfs);
#    apt accepts 2xx/3xx (apt can follow https redirects); dnf/pacman just need any response --
probe_mirror() {
    m="$1"
    case "$PMGR" in
        apk)
            code=$(http_code "http://$m/alpine/v3.21/main/x86_64/APKINDEX.tar.gz")
            case "$code" in 2[0-9][0-9]) return 0 ;; *) return 1 ;; esac
            ;;
        apt)
            . /etc/os-release 2>/dev/null
            if [ "$ID" = "ubuntu" ]; then
                url="http://$m/ubuntu/dists/jammy/Release"
            else
                url="http://$m/debian/dists/stable/Release"
            fi
            code=$(http_code "$url")
            case "$code" in 2[0-9][0-9]|3[0-9][0-9]) return 0 ;; *) return 1 ;; esac
            ;;
        dnf|yum|pacman)
            code=$(http_code "https://$m/archlinux/core/os/x86_64/core.db")
            case "$code" in 2[0-9][0-9]|3[0-9][0-9]|4[0-9][0-9]) return 0 ;; *) return 1 ;; esac
            ;;
    esac
}

# -- Pick the first usable mirror from the candidates (falls back to the first one if none respond) --
pick_mirror() {
    for m in $MIRRORS; do
        if probe_mirror "$m"; then
            MIRROR="$m"
            return 0
        fi
    done
    MIRROR=$(echo "$MIRRORS" | awk '{print $1}')
}

# -- Mirrors: switch to a China mirror per package manager (multi-candidate auto-detection); automatically restores the original config on failure (never blocks the install) --
setup_mirror() {
    [ -z "$MIRROR" ] && pick_mirror
    if command -v apk >/dev/null 2>&1; then
        setup_apk_mirror
    elif command -v apt-get >/dev/null 2>&1; then
        setup_apt_mirror
    elif command -v dnf >/dev/null 2>&1; then
        setup_dnf_mirror
    elif command -v yum >/dev/null 2>&1; then
        echo "yum (RHEL/CentOS) does not support auto mirror switch; using the default source" >&2
        return 0
    elif command -v pacman >/dev/null 2>&1; then
        setup_pacman_mirror
    fi
}

setup_apk_mirror() {
    # Use http instead of https: the minirootfs has no ca-certificates, and apk verifies signatures on indexes and packages independently, so http does not affect integrity.
    mkdir -p /etc/apk
    # The Alpine major-version branch is read dynamically from the mirror itself, to support user-imported Alpine images of any version:
    # 1) Prefer extracting from the existing repositories (official and already-switched sources both contain `alpine/<branch>/`; edge works too);
    # 2) Fall back to VERSION_ID from /etc/os-release (e.g. 3.21.3 -> v3.21);
    # 3) Ultimate fallback: v3.21 (matching the built-in Alpine).
    branch=""
    if [ -f /etc/apk/repositories ]; then
        branch=$(sed -n 's#.*alpine/\([^/]*\)/.*#\1#p' /etc/apk/repositories 2>/dev/null | head -1)
    fi
    if [ -z "$branch" ] && [ -f /etc/os-release ]; then
        . /etc/os-release 2>/dev/null
        if [ "$ID" = "alpine" ] && [ -n "$VERSION_ID" ]; then
            branch="v$(echo "$VERSION_ID" | cut -d. -f1-2)"
        fi
    fi
    [ -z "$branch" ] && branch="v3.21"
    cat > /etc/apk/repositories <<EOF
http://$MIRROR/alpine/$branch/main
http://$MIRROR/alpine/$branch/community
EOF
}

setup_apt_mirror() {
    . /etc/os-release 2>/dev/null
    codename="${VERSION_CODENAME:-}"
    [ -z "$codename" ] && { echo "Could not detect the apt codename; skipping mirror switch" >&2; return 1; }
    # Ubuntu packages for ARM architectures (common on phones) live in the ubuntu-ports repo (ports.ubuntu.com); x86 uses the main ubuntu repo.
    # Debian's main repo already carries arm64/armhf, no ports needed.
    arch=$(dpkg --print-architecture 2>/dev/null || uname -m)
    case "$ID" in
        ubuntu)
            case "$arch" in
                amd64|i386) repo="ubuntu" ;;
                *) repo="ubuntu-ports" ;;
            esac
            uri="http://$MIRROR/$repo/"
            suites="$codename $codename-updates $codename-backports $codename-security"
            components="main restricted universe multiverse"
            ;;
        debian)
            uri="http://$MIRROR/debian/"
            suites="$codename $codename-updates $codename-backports"
            components="main contrib non-free non-free-firmware"
            security_uri="http://$MIRROR/debian-security/"
            ;;
        *) echo "Unsupported apt distro: $ID; skipping mirror switch" >&2; return 1 ;;
    esac
    # Only write Signed-By when the keyring exists; without it apt falls back to trusted.gpg.d
    signed_by=""
    [ -f /usr/share/keyrings/ubuntu-archive-keyring.gpg ] && signed_by="Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg"
    [ -f /usr/share/keyrings/debian-archive-keyring.gpg ] && signed_by="Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg"
    # Back up and clear the old sources (handles both sources.list and sources.list.d layouts)
    backup_dir=/etc/apt/mirror-backup
    rm -rf "$backup_dir" && mkdir -p "$backup_dir"
    [ -f /etc/apt/sources.list ] && cp /etc/apt/sources.list "$backup_dir/" 2>/dev/null
    [ -d /etc/apt/sources.list.d ] && cp -a /etc/apt/sources.list.d "$backup_dir/" 2>/dev/null
    rm -f /etc/apt/sources.list
    rm -rf /etc/apt/sources.list.d
    mkdir -p /etc/apt/sources.list.d
    {
        echo "Types: deb"
        echo "URIs: $uri"
        echo "Suites: $suites"
        echo "Components: $components"
        [ -n "$signed_by" ] && echo "$signed_by"
    } > /etc/apt/sources.list.d/xencode-mirror.sources
    if [ -n "$security_uri" ]; then
        {
            echo "Types: deb"
            echo "URIs: $security_uri"
            echo "Suites: $codename-security"
            echo "Components: $components"
            [ -n "$signed_by" ] && echo "$signed_by"
        } > /etc/apt/sources.list.d/xencode-mirror-security.sources
    fi
    # Verify: if update fails, print the reason and restore the original config
    if ! apt-get update -y; then
        echo "apt update failed after switching mirrors; restored the original sources" >&2
        rm -rf /etc/apt/sources.list.d
        [ -f "$backup_dir/sources.list" ] && cp "$backup_dir/sources.list" /etc/apt/sources.list
        [ -d "$backup_dir/sources.list.d" ] && mv "$backup_dir/sources.list.d" /etc/apt/sources.list.d
        rm -rf "$backup_dir"
        return 1
    fi
    rm -rf "$backup_dir"
    return 0
}

setup_dnf_mirror() {
    # Fedora: back up the original repo and write the Huawei Cloud baseurl (metalink is unreachable in China, so overwrite the repo files directly)
    backup_dir=/etc/yum.repos.d/mirror-backup
    rm -rf "$backup_dir" && mkdir -p "$backup_dir"
    cp -a /etc/yum.repos.d/ "$backup_dir/" 2>/dev/null
    rm -f /etc/yum.repos.d/*.repo
    cat > /etc/yum.repos.d/fedora.repo <<EOF
[fedora]
name=Fedora \$releasever - \$basearch
baseurl=https://$MIRROR/fedora/releases/\$releasever/Everything/\$basearch/os/
enabled=1
gpgcheck=1
EOF
    cat > /etc/yum.repos.d/fedora-updates.repo <<EOF
[updates]
name=Fedora \$releasever - \$basearch - Updates
baseurl=https://$MIRROR/fedora/updates/\$releasever/\$basearch/
enabled=1
gpgcheck=1
EOF
    if ! dnf makecache -y >/dev/null 2>&1; then
        echo "dnf makecache failed after switching mirrors; restored the original repos" >&2
        rm -f /etc/yum.repos.d/fedora.repo /etc/yum.repos.d/fedora-updates.repo
        cp -a "$backup_dir/." /etc/yum.repos.d/ 2>/dev/null
        rm -rf "$backup_dir"
        return 1
    fi
    rm -rf "$backup_dir"
    return 0
}

setup_pacman_mirror() {
    backup=/etc/pacman.d/mirrorlist
    [ -f "$backup" ] && cp "$backup" "$backup.backup"
    cat > "$backup" <<EOF
Server = https://$MIRROR/archlinux/\$repo/os/\$arch
EOF
    if ! pacman -Sy --noconfirm >/dev/null 2>&1; then
        echo "pacman -Sy failed after switching mirrors; restored the original mirrorlist" >&2
        [ -f "$backup.backup" ] && mv "$backup.backup" "$backup"
        return 1
    fi
    rm -f "$backup.backup"
    return 0
}

git_config() {
    # Skip credential setup when git is not installed (manual install / exit paths)
    command -v git >/dev/null 2>&1 || return 0
    # Minimized credential injection: applies only to repositories under the workspace root ($HOME/workspace/) —
    # credential.helper is written into gitconfig.credential and loaded conditionally per directory via includeIf,
    # so git repositories elsewhere in the container get nothing injected (the custom xencode helper decodes the credential file and passes instantly on a hit; on a miss it pops a dialog via file IPC).
    # Use $HOME instead of hard-coding /root: the container home depends on the environment, keep them consistent (the app side points GIT_CONFIG_GLOBAL at $HOME/.xencode/.gitconfig too).
    XENCODE_DIR="$HOME/.xencode"
    mkdir -p "$XENCODE_DIR"
    cat > "$XENCODE_DIR/gitconfig.credential" <<EOF
[credential]
    helper = $XENCODE_DIR/git-credential-xencode
EOF
    # Clear the old includeIf section first (idempotent), then write the workspace-root-scoped includeIf.
    # The section name must carry a subsection: the real section is [includeIf "gitdir:..."], so a bare `--remove-section includeIf`
    # does not match it; the fatal error gets swallowed by 2>/dev/null, and every initialization run would --add another duplicate path.
    git config --global --remove-section "includeIf.gitdir:$HOME/workspace/" 2>/dev/null || true
    git config --global --add includeIf."gitdir:$HOME/workspace/".path "$XENCODE_DIR/gitconfig.credential"
    # Remove the global top-level [credential] section: the helper should only load per directory via the includeIf above;
    # at top level it would apply to every repository in the container, breaking the "inject only under the workspace root" minimization.
    git config --global --remove-section credential 2>/dev/null || true
    # Default the initial branch of new repositories to main (the standard on modern platforms like GitHub / GitLab, avoiding master mismatches)
    git config --global init.defaultBranch main
}

# -- Scenario framework --
# One script per dev environment under lib/scenarios/, defining scenario_name_<id> and install_scenario_<id>;
# SCENARIO_IDS controls the menu order. android / flutter are heavier and keep their logic in their own files (sharing lib/android-sdk.sh).
SCENARIO_IDS="common python node android flutter java go rust php base"

# Load the shared tools and each scenario script (android-sdk.sh is shared by the android / flutter scenarios)
[ -f "$AICODE_LIB_DIR/android-sdk.sh" ] && . "$AICODE_LIB_DIR/android-sdk.sh"
for f in "$AICODE_LIB_DIR/scenarios/"*.sh; do
    [ -f "$f" ] && . "$f"
done

# Scenario name (falls back to the id when undefined)
scenario_name() {
    fn="scenario_name_$1"
    if command -v "$fn" >/dev/null 2>&1; then "$fn"; else echo "$1"; fi
}

# Scenario install dispatch
install_scenario() {
    fn="install_scenario_$1"
    if command -v "$fn" >/dev/null 2>&1; then "$fn"; else echo "${C_RED}Unknown scenario: $1${C_RESET}"; return 1; fi
}

# -- Light-scenario installer (for lightweight scenarios): switch mirrors -> refresh indexes -> show plan for confirmation -> install packages.
#    Takes the runtime's default latest major version; to pick a specific major version use "Custom install".
#    Returns: 0=done 1=install failed 2=user cancelled (plan not confirmed)
install_light_runtimes() {
    runtimes="$1"
    ask_mirror
    echo ""
    echo "Updating package lists..."
    pkg_update || { echo "${C_RED}Failed to update package lists. Check the network and retry.${C_RESET}"; return 1; }
    echo ""
    show_plan "$runtimes" || return 2
    echo ""
    echo "Installing. This may take a few minutes..."
    pkgs="$BASE_PKGS"
    for r in $runtimes; do
        pkgs="$pkgs $(runtime_install "$r")"
    done
    pkg_add $pkgs || return 1
    return 0
}
