package com.divalhr.core.platform.status;

import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public status endpoint for the browser status screen. Reveals no dependency names, versions of
 * third-party components, or configuration.
 */
@RestController
@RequestMapping("/api/v1/system")
public class SystemStatusController {

  private final ApplicationAvailability availability;
  private final String version;
  private final Clock clock;

  /**
   * Creates the controller.
   *
   * @param availability application availability state
   * @param version build version
   */
  public SystemStatusController(
      ApplicationAvailability availability,
      @Value("${divalhr.version:0.1.0}") String version) {
    this.availability = availability;
    this.version = version;
    this.clock = Clock.systemUTC();
  }

  /**
   * Returns the public status.
   *
   * @return 200 when ready, 503 otherwise
   */
  @Operation(operationId = "getSystemStatus")
  @GetMapping("/status")
  public ResponseEntity<SystemStatus> status() {
    boolean ready = availability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC;
    SystemStatus body =
        new SystemStatus("core-api", ready ? "UP" : "DOWN", version, Instant.now(clock));
    return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
        .body(body);
  }
}
