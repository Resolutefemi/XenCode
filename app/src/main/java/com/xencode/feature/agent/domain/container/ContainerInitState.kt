package com.xencode.feature.agent.domain.container

/**
 * Live progress state of container initialization. Exposed by [LinuxContainerEngine.initProgress] and shared by all
 * initialization entry points (terminal page / AI page, etc.) since the engine is a @Singleton. StateFlow conflates automatically; high-frequency updates keep only the latest value.
 */
sealed interface ContainerInitState {
    /** Initialization has not started yet. */
    data object Idle : ContainerInitState

    /** Cleaning up data left by the previous container (deleting the old rootfs after a reset/image switch); [processed] is the number of entries cleaned. */
    data class CleaningOldRootfs(val processed: Int) : ContainerInitState

    /** Extracting the Alpine rootfs; [processed] is the number of tar entries handled. */
    data class ExtractingRootfs(val processed: Int) : ContainerInitState

    /** Installing base packages (python3/git/pip/node/npm/rg); [line] is the latest apk output line (may be null). */
    data class InstallingPackages(val line: String?) : ContainerInitState

    /** Container is ready (rootfs extracted + base packages installed; proot ships with the APK, no deployment needed). */
    data object Ready : ContainerInitState

    /** Initialization failed; [reason] is the cause. */
    data class Failed(val reason: String) : ContainerInitState
}
