#!/bin/sh
# 场景：Kotlin / Android 开发（JDK 17 + Android SDK 36 + build-tools 35.0.0）
# 依赖 lib/android-sdk.sh 提供的共享安装逻辑。
scenario_name_android() { echo "Kotlin / Android development (JDK + Android SDK)"; }

install_scenario_android() {
    if [ "$PMGR" != "apt" ]; then
        echo "${C_YELLOW}Note${C_RESET}: compiling Android apps works best on Debian / Ubuntu images. Built-in Alpine (musl) needs extra work."
        printf "Current package manager is %s. Continue anyway? [y/N]: " "${PMGR:-unknown}"
        read a
        case "$a" in y|Y) ;; *) return 2 ;; esac
    fi
    echo ""
    echo "Installing JDK and base tools..."
    pkg_add "$(apt_jdk_pkg)" curl wget unzip || return 1
    install_android_sdk "36" "35.0.0" || return 1
    install_android_tools_arm64 || true
    write_android_profile
    echo ""
    echo "${C_GREEN}Android / Kotlin environment ready: $HOME/android/sdk${C_RESET}"
    echo "  New terminals pick up JAVA_HOME / ANDROID_HOME automatically. See the Android compile tutorial for next steps."
    return 0
}