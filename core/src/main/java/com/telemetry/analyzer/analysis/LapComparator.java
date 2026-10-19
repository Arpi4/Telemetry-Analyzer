package com.telemetry.analyzer.analysis;

import com.telemetry.analyzer.domain.LapData;
import com.telemetry.analyzer.dto.SegmentDeltaDto;

import java.util.ArrayList;
import java.util.List;

/**
 * Distance-based lap comparison.
 *
 * <p>Sample-by-sample comparison (index i of lap A against index i of lap B) drifts apart as
 * soon as one lap is faster, because the same index then belongs to different places on the
 * track. Here both laps are first expressed as functions of lap distance, then evaluated on a
 * common grid, so every compared pair of values belongs to the same track position.
 */
public class LapComparator {

    public static final double DEFAULT_STEP_METERS = 2.0;

    public AlignedLapComparison compare(LapData reference, LapData compare) {
        return compare(LapTrace.of(reference), LapTrace.of(compare), DEFAULT_STEP_METERS, true);
    }

    /**
     * @param stepMeters grid resolution along the reference lap
     * @param normalize  scale the compare lap distance to the reference lap length, so that a
     *                   slightly different racing line does not accumulate as fake time delta
     */
    public AlignedLapComparison compare(LapTrace ref, LapTrace cmp, double stepMeters, boolean normalize) {
        double refLength = ref.lapLength();
        double cmpLength = cmp.lapLength();
        if (ref.size() < 2 || cmp.size() < 2 || refLength <= 0 || cmpLength <= 0) {
            throw new IllegalArgumentException("Both laps need at least two samples and a positive lap distance.");
        }

        double[] cmpAxis = normalize ? Interpolation.scale(cmp.distance(), refLength / cmpLength) : cmp.distance();
        double gridLength = normalize ? refLength : Math.min(refLength, cmpLength);
        double[] grid = Interpolation.grid(gridLength, stepMeters);

        double[] refTime = Interpolation.resample(ref.distance(), ref.elapsed(), grid);
        double[] cmpTime = Interpolation.resample(cmpAxis, cmp.elapsed(), grid);
        double[] delta = new double[grid.length];
        for (int k = 0; k < grid.length; k++) {
            delta[k] = cmpTime[k] - refTime[k];
        }

        return new AlignedLapComparison(
                ref.lapId(), cmp.lapId(), grid,
                Interpolation.resample(ref.distance(), ref.speed(), grid),
                Interpolation.resample(cmpAxis, cmp.speed(), grid),
                Interpolation.resample(ref.distance(), ref.throttle(), grid),
                Interpolation.resample(cmpAxis, cmp.throttle(), grid),
                Interpolation.resample(ref.distance(), ref.brake(), grid),
                Interpolation.resample(cmpAxis, cmp.brake(), grid),
                delta,
                ref.lapTime(), cmp.lapTime(), refLength, cmpLength,
                normalize,
                ref.distanceFromChannel() && cmp.distanceFromChannel()
        );
    }

    /**
     * Exact sector deltas read off the aligned delta trace: Δt(end) − Δt(start) of each of
     * {@code sectorCount} equal-length sectors. Unlike an average-speed estimate this needs no
     * assumption about the sampling rate, and the sector deltas sum to the final delta.
     */
    public List<SegmentDeltaDto> sectorDeltas(AlignedLapComparison c, int sectorCount) {
        int n = Math.clamp(sectorCount, 2, 50);
        double length = c.distance()[c.size() - 1];
        List<SegmentDeltaDto> out = new ArrayList<>(n);
        int startIdx = 0;
        for (int s = 0; s < n; s++) {
            double end = length * (s + 1) / n;
            int endIdx = indexAtOrAfter(c.distance(), end, startIdx);
            double refAvg = mean(c.referenceSpeed(), startIdx, endIdx);
            double cmpAvg = mean(c.compareSpeed(), startIdx, endIdx);
            double dt = c.deltaTime()[endIdx] - c.deltaTime()[startIdx];
            out.add(new SegmentDeltaDto(s + 1,
                    c.distance()[startIdx] / length, c.distance()[endIdx] / length,
                    c.distance()[startIdx], c.distance()[endIdx],
                    refAvg, cmpAvg, dt, c.distanceFromChannel()));
            startIdx = endIdx;
        }
        return out;
    }

    private static int indexAtOrAfter(double[] grid, double value, int from) {
        for (int i = from; i < grid.length; i++) {
            if (grid[i] >= value - 1e-9) {
                return i;
            }
        }
        return grid.length - 1;
    }

    private static double mean(double[] values, int from, int toInclusive) {
        double sum = 0;
        int count = 0;
        for (int i = from; i <= toInclusive && i < values.length; i++) {
            sum += values[i];
            count++;
        }
        return count == 0 ? 0 : sum / count;
    }
}
