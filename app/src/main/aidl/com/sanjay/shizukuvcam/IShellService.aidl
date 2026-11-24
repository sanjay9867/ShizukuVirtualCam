package com.sanjay.shizukuvcam;

import android.os.ParcelFileDescriptor;

interface IShellService {
    void destroy() = 16777114;
    String runCommand(String command) = 1;
    String deployVideoFd(in ParcelFileDescriptor pfd) = 2;
    boolean setVirtualCamEnabled(boolean enabled) = 3;
    boolean isVirtualCamEnabled() = 4;
    boolean hasDeployedVideo() = 5;
    String prepareAndRestartPackage(String packageName) = 6;
}
