package com.danielealbano.androidremotecontrolmcp.services.shell;

interface IShellService {
    // Keys are defined in ShellResultKeys.
    Bundle exec(String command, long timeoutMs, int maxOutputBytes) = 1;

    // Transaction code reserved by Shizuku for UserService teardown.
    void destroy() = 16777114;
}
