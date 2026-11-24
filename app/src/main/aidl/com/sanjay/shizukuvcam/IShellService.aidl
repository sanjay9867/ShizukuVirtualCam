package com.sanjay.shizukuvcam;

interface IShellService {
    void destroy() = 16777114;
    String runCommand(String command) = 1;
    boolean deployVideoFeed(String sourcePath) = 2;
    boolean setVirtualCamEnabled(boolean enabled) = 3;
    boolean isVirtualCamEnabled() = 4;
    boolean hasDeployedVideo() = 5;
    void forceStopPackage(String packageName) = 6;
}
