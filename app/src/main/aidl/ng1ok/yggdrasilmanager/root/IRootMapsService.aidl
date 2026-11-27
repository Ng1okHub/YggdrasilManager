package ng1ok.yggdrasilmanager.root;

interface IRootMapsService {
    int addRule(int hasPid, int pid, int action, int matchKind, int ruleFlags,
        String matchPerms, String setPerms, long addrStart, long addrEnd, String pattern);
    int removeRule(int hasPid, int pid, int action, int matchKind, int ruleFlags,
        String matchPerms, String setPerms, long addrStart, long addrEnd, String pattern);
    int clear(int hasPid, int pid, int allScopes);
    byte[] get(int hasPid, int pid);
}
