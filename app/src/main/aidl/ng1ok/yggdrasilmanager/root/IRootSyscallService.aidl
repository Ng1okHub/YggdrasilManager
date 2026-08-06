package ng1ok.yggdrasilmanager.root;

import ng1ok.yggdrasilmanager.root.IRootSyscallEventCallback;

interface IRootSyscallService {
    int setRule(int syscallNr, int flags, long targetId);
    int deleteRule(int syscallNr, int selectorFlags, long targetId);
    int startListening(IRootSyscallEventCallback callback);
    void stopListening();
}
