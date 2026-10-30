package com.rostersync.support;

import com.rostersync.config.RosterSyncProperties;
import org.springframework.stereotype.Component;

/**
 * Failure injection. With {@code CHAOS_CRASH_AT=<point>} (and chaos allowed) the JVM halts at
 * that point exactly like kill -9 or an OOM: no shutdown hooks, no rollback from our side.
 * Used by the crash tests and the live demo; disabled unless explicitly allowed.
 */
@Component
public class Chaos {

    public static final String INGEST_AFTER_EMPLOYEE_WRITE = "ingest.afterEmployeeWrite";
    public static final String RELAY_AFTER_PUBLISH = "relay.afterPublish";
    public static final String RECONCILE_MID_APPLY = "reconcile.midApply";

    private final String crashAt;

    public Chaos(RosterSyncProperties props) {
        String point = props.chaos() == null ? null : props.chaos().crashAt();
        this.crashAt = props.chaos() != null && props.chaos().allowed() && point != null && !point.isBlank() ? point : null;
    }

    public void maybeCrash(String point) {
        if (point.equals(crashAt)) {
            System.err.println("[chaos] crashing at " + point);
            System.err.flush();
            Runtime.getRuntime().halt(137);
        }
    }
}
