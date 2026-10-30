package com.rostersync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rostersync.messaging.OutboxRelay;
import com.rostersync.service.IngestService;
import com.rostersync.service.ReconcileService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Child JVM for the crash tests. With CHAOS_CRASH_AT set it halts mid-operation with exit
 * code 137, exactly like kill -9: no shutdown hooks, no rollback from the application side.
 */
public final class CrashScenario {

    private CrashScenario() {}

    public static void main(String[] args) throws Exception {
        // Command-line arguments: highest precedence, above application.yml.
        ConfigurableApplicationContext context = new SpringApplicationBuilder(RosterSyncApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .run("--rostersync.chaos.allowed=true",
                        "--rostersync.chaos.crash-at=" + System.getenv("CHAOS_CRASH_AT"),
                        "--spring.flyway.enabled=false",
                        "--rostersync.worker.enabled=false",
                        "--spring.datasource.hikari.maximum-pool-size=3");
        switch (args[0]) {
            case "ingest" -> {
                Map<?, ?> event = new ObjectMapper().readValue(System.getenv("EVENT"), Map.class);
                context.getBean(IngestService.class).ingest("acme", List.of(event), "crash-test");
            }
            case "relay" -> context.getBean(OutboxRelay.class).drain();
            case "reconcile" -> context.getBean(ReconcileService.class).processRun("acme", UUID.fromString(System.getenv("RUN_ID")), "doomed-worker");
            default -> throw new IllegalArgumentException(args[0]);
        }
        context.close();
        System.exit(0);
    }
}
