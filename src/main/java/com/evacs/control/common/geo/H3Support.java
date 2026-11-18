package com.evacs.control.common.geo;

import com.uber.h3core.H3Core;
import com.uber.h3core.util.LatLng;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * H3 셀 계산 래퍼.
 *
 * <p>{@link H3Core}는 스레드 안전하지만 생성 비용이 있어 싱글턴 빈으로 한 번만 만든다.
 * 밀도 집계(차량 → 셀)와 cone 셀의 중심 좌표 환원에 쓴다.
 */
@Component
public class H3Support {

	/** H3 해상도 상·하한(라이브러리 제약). */
	public static final int MIN_RESOLUTION = 0;
	public static final int MAX_RESOLUTION = 15;

	private final H3Core h3;

	public H3Support() {
		try {
			this.h3 = H3Core.newInstance();
		} catch (IOException e) {
			throw new UncheckedIOException("H3 네이티브 라이브러리 초기화 실패", e);
		}
	}

	/** 좌표가 속한 셀의 H3 인덱스(16진 문자열). */
	public String cellOf(double latitude, double longitude, int resolution) {
		return h3.latLngToCellAddress(latitude, longitude, clamp(resolution));
	}

	/** 셀 중심 좌표. 대시보드가 셀을 그릴 때 기준점으로 쓴다. */
	public double[] centerOf(String cellAddress) {
		LatLng center = h3.cellToLatLng(cellAddress);
		return new double[] {center.lat, center.lng};
	}

	/** 셀 경계 폴리곤(위도, 경도 쌍의 목록). */
	public List<double[]> boundaryOf(String cellAddress) {
		return h3.cellToBoundary(cellAddress).stream()
				.map(p -> new double[] {p.lat, p.lng})
				.toList();
	}

	/** 셀의 해상도. cone 셀 목록처럼 해상도를 모르는 입력에 쓴다. */
	public int resolutionOf(String cellAddress) {
		return h3.getResolution(cellAddress);
	}

	public boolean isValidCell(String cellAddress) {
		try {
			return h3.isValidCell(h3.stringToH3(cellAddress));
		} catch (RuntimeException e) {
			return false;
		}
	}

	private int clamp(int resolution) {
		if (resolution < MIN_RESOLUTION || resolution > MAX_RESOLUTION) {
			throw new IllegalArgumentException(
					"H3 해상도는 %d~%d 사이여야 합니다: %d".formatted(MIN_RESOLUTION, MAX_RESOLUTION, resolution));
		}
		return resolution;
	}
}
