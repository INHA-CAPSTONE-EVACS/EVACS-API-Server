package com.evacs.control.alert.dto;

import com.evacs.control.alert.domain.VehicleAlert;
import java.time.Instant;

/**
 * 특정 차량에 알림이 왔는지 여부.
 *
 * <p>알림이 없는 것은 404가 아니라 {@code alerted=false}다 — "그런 차량이 없다"와
 * "알림이 안 왔다"를 화면이 구분해야 한다(cone의 {@code available=false}와 같은 관례).
 */
public record VehicleAlertStatusResponse(
		String vehicleId, boolean alerted, VehicleAlertResponse alert, Instant streamPolledAt) {

	public static VehicleAlertStatusResponse of(
			String vehicleId, VehicleAlert alert, Instant streamPolledAt) {
		return new VehicleAlertStatusResponse(
				vehicleId,
				alert != null,
				alert == null ? null : VehicleAlertResponse.from(alert),
				streamPolledAt);
	}
}
