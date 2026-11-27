package ng1ok.yggdrasilmanager.root;

import ng1ok.yggdrasilmanager.root.RootSyscallEvent;

interface IRootSyscallEventCallback {
    void onEvent(in RootSyscallEvent event);
    void onListenerFailure(int error);
}
