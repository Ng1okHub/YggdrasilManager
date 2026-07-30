package ng1ok.yggdrasilmanager.root;

import ng1ok.yggdrasilmanager.root.IRootHwbpEventCallback;

interface IRootHwbpService {
    int observe(int pid, long targetAddr, int mode, int len, int flags, int stackSize);
    int unobserve(int pid, long targetAddr);
    void startListening(IRootHwbpEventCallback callback);
    void stopListening();
}
