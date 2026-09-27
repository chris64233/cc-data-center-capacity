package com.chris64233.datacentercapacity.domain;

/** 供电回路（双路供电的 A/B 两路）。 */
public enum Feed {
    A, B;

    public Feed other() {
        return this == A ? B : A;
    }
}
