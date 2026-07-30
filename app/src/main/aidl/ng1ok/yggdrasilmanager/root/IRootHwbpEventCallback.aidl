package ng1ok.yggdrasilmanager.root;

import ng1ok.yggdrasilmanager.root.RootHwbpEvent;

oneway interface IRootHwbpEventCallback {
    void onEvent(in RootHwbpEvent event);
}
