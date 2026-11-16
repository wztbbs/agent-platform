package com.cexpilot.platform.observability;

/** 宿主提供的非执行性标记；turnId 仅用于交互关联，固定回复可指定自己的内容版本。 */
public record TraceAttributes(boolean realUser, boolean staticReply, String promptVersion, String turnId) {
    public static final TraceAttributes DEFAULT = new TraceAttributes(true, false, null, null);
}
