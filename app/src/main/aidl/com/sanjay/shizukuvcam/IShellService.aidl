package com.sanjay.shizukuvcam;

import android.os.ParcelFileDescriptor;

interface IShellService {
    void destroy() = 16777114;
    String runCommand(String command) = 1;
    String deployNamedFileFd(in ParcelFileDescriptor pfd, String fileName, String activePackage) = 2;
    boolean writeControlState(String controlText, String activePackage) = 3;
    String readControlState() = 4;
    boolean hasDeployedFile(String fileName) = 5;
    String prepareAndRestartPackage(String packageName) = 6;
}
