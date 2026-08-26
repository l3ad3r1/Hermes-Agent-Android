package com.hermes.agent.service;

interface IPrivilegedShellService {
    void destroy() = 16777114;
    int getUid() = 1;
    String execute(String command) = 2;
}
