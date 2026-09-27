package com.chris64233.datacentercapacity.domain;

/** 供电回路。设备的主、备电源必须分配到不同回路。 */
public enum Feed {
    A, B;

    public Feed other() {
        return this == A ? B : A;
    }
}
