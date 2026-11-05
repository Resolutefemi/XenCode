// Shizuku UserService interface: runs in the shell (uid 2000) process and executes shell commands on behalf of the app.
// Returns a Bundle: output(String) command output, exitCode(int) exit code (negative on timeout/exception).
package com.xencode.feature.agent.domain.shizuku;

import android.os.Bundle;

interface IShizukuShellService {
    // Shizuku-reserved destroy entry: the transaction code is fixed (aidl declares 16777114, the server actually calls 16777115).
    // Fired on unbindUserService(remove=true). Implementations should end the process here. Do not change.
    void destroy() = 16777114;

    Bundle exec(String command, int timeoutMs) = 1;
}
