package com.aiskincare.backend.analyses;

import com.aiskincare.backend.analyses.AnalysisRows.AnalysisRow;
import com.aiskincare.backend.analyses.AnalysisRows.RecommendationRow;
import com.aiskincare.backend.analyses.AnalysisRows.SkinMetricRow;
import com.aiskincare.backend.analyses.Scoring.MetricBp;
import com.aiskincare.backend.analyses.Scoring.ScoreReport;
import com.aiskincare.backend.analyses.dto.AnalysisResponses.AnalysisDetail;
import com.aiskincare.backend.analyses.dto.AnalysisResponses.AnalysisError;
import com.aiskincare.backend.analyses.dto.AnalysisResponses.CodeLabel;
import com.aiskincare.backend.analyses.dto.AnalysisResponses.Measurement;
import com.aiskincare.backend.analyses.dto.AnalysisResponses.Recommendation;
import com.aiskincare.backend.analyses.dto.AnalysisResponses.TrendDelta;
import com.aiskincare.backend.analyses.dto.AnalysisResponses.TrendPoint;
import com.aiskincare.backend.analyses.dto.AnalysisResponses.TrendSummary;
import com.aiskincare.backend.common.enums.AnalysisStatus;
import com.aiskincare.backend.common.enums.Metric;
import com.aiskincare.backend.common.enums.RecommendationStatus;
import com.aiskincare.backend.common.enums.Roi;
import com.aiskincare.backend.common.error.ErrorCode;
import com.aiskincare.backend.common.util.TimeUtil;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** DB 행 → API 응답 (TRD §10.2·10.4·10.5). 0–1 원본은 여기서 0–100으로 바꾼다. */
public final class AnalysisPresenter {
  private AnalysisPresenter() {}

  public record QueueInfo(Long queuePosition, Long estimatedWaitSec) {
    public static final QueueInfo NONE = new QueueInfo(null, null);
  }

  /** DB 값(4자리)도 bp로 복원해서 측정 시점과 같은 함수로 계산한다 */
  public static ScoreReport report(List<SkinMetricRow> rows) {
    Map<Roi, MetricBp> byRoi = new EnumMap<>(Roi.class);
    for (SkinMetricRow row : rows) byRoi.put(row.roi(), MetricBp.of(row.ratios()));
    return Scoring.report(byRoi);
  }

  public static Recommendation recommendation(AnalysisRow analysis, RecommendationRow row) {
    return switch (analysis.status()) {
      case QUEUED, RECOMMENDING -> Recommendation.status(RecommendationStatus.PENDING);
      case FAILED -> Recommendation.status(RecommendationStatus.FAILED);
      case DONE -> {
        if (row == null) throw new IllegalStateException("done 분석에 추천 행이 없습니다");
        yield new Recommendation(
            RecommendationStatus.DONE,
            new CodeLabel<>(row.skinType(), row.skinType().label()),
            row.concerns().stream().map(c -> new CodeLabel<>(c, c.label())).toList(),
            row.ingredients(),
            row.routine(),
            row.reasoning(),
            row.answer(),
            row.inputNotes(),
            row.disclaimer(),
            row.filtered(),
            row.usedFallback(),
            row.modelVersion());
      }
    };
  }

  private static AnalysisError error(AnalysisRow analysis) {
    if (analysis.status() != AnalysisStatus.FAILED) return null;
    ErrorCode stored = ErrorCode.fromName(analysis.errorCode());
    ErrorCode code = stored != null && stored.isAnalysisFailureCode() ? stored : ErrorCode.E4001;
    return new AnalysisError(code.name(), code.message());
  }

  public static AnalysisDetail detail(
      AnalysisRow analysis,
      List<SkinMetricRow> metrics,
      NormalizedSurvey survey,
      RecommendationRow recommendation,
      QueueInfo queue) {
    ScoreReport report = report(metrics);
    boolean queued = analysis.status() == AnalysisStatus.QUEUED;
    return new AnalysisDetail(
        analysis.id(),
        analysis.status(),
        TimeUtil.toIsoUtc(analysis.createdAt()),
        TimeUtil.toIsoUtc(analysis.completedAt()),
        analysis.deviceType(),
        queued ? queue.queuePosition() : null,
        queued ? queue.estimatedWaitSec() : null,
        new Measurement(
            analysis.isDemo(),
            analysis.confidence(),
            analysis.calibrationApplied(),
            analysis.overallScore(),
            report.summary(),
            report.byRoi(),
            analysis.skinModelVersion()),
        recommendation(analysis, recommendation),
        survey,
        error(analysis));
  }

  public static TrendPoint trendPoint(AnalysisRow analysis, List<SkinMetricRow> metrics) {
    ScoreReport report = report(metrics);
    Map<Metric, Integer> values = new EnumMap<>(Metric.class);
    report.summary().forEach(item -> values.put(item.metric(), item.value()));
    return new TrendPoint(
        analysis.id(),
        TimeUtil.kstDate(analysis.createdAt()),
        analysis.overallScore(),
        new TrendSummary(
            values.get(Metric.MOISTURE),
            values.get(Metric.ELASTICITY),
            values.get(Metric.PIGMENTATION),
            values.get(Metric.PORE)));
  }

  /** delta = current − previous (정수끼리). pigmentation·pore는 음수가 좋아진 것 */
  public static TrendDelta delta(TrendPoint current, TrendPoint previous) {
    return new TrendDelta(
        current.overallScore() - previous.overallScore(),
        current.summary().moisture() - previous.summary().moisture(),
        current.summary().elasticity() - previous.summary().elasticity(),
        current.summary().pigmentation() - previous.summary().pigmentation(),
        current.summary().pore() - previous.summary().pore());
  }
}
