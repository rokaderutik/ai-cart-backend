package com.rutikrokade.projects.aicartapp.util;

import lombok.Getter;

/** One shared budget for planner and answer, including at most one validation repair. */
@Getter
public class ChatCallBudget {

    private int calls;

    private int inputTokens;

    private int outputTokens;

    private boolean repaired;

    public boolean claimRepair() {
        if (repaired) return false;
        repaired = true;
        return true;
    }

    public void called(int input, int output) {
        calls++;
        inputTokens += input;
        outputTokens += output;
    }
}
