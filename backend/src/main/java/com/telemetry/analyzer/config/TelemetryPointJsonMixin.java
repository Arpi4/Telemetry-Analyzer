package com.telemetry.analyzer.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.telemetry.analyzer.domain.TelemetryPoint;
import org.springframework.boot.jackson.JsonMixin;

/**
 * JSON shape of {@link TelemetryPoint}: the vehicle-dynamics channels added later are left out
 * when a file does not have them, so sessions without them are not inflated by four null fields
 * per sample ({@code GET /api/sessions/{id}} returns every sample). The original fields keep
 * their explicit nulls.
 */
@JsonMixin(TelemetryPoint.class)
abstract class TelemetryPointJsonMixin {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    abstract Double lateralAcceleration();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    abstract Double longitudinalAcceleration();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    abstract Double steeringAngle();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    abstract Double engineRpm();
}
