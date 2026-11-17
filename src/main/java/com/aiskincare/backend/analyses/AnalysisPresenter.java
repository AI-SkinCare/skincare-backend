package com.aiskincare.backend.analyses;

import com.aiskincare.backend.aiclient.AiModels.DiseaseResult;
import com.aiskincare.backend.analyses.AnalysisRepository.ListRow;
import com.aiskincare.backend.analyses.AnalysisRows.AnalysisRow;
import com.aiskincare.backend.analyses.AnalysisRows.RecommendationRow;
import com.aiskincare.backend.analyses.dto.AnalysisResponses.AnalysisError;
import com.aiskincare.backend.analyses.dto.AnalysisResponses.AnalysisView;
import com.aiskincare.backend.analyses.dto.AnalysisResponses.CodeLabel;
import com.aiskincare.backend.analyses.dto.AnalysisResponses.Disease;
import com.aiskincare.backend.analyses.dto.AnalysisResponses.ListItem;
import com.aiskincare.backend.analyses.dto.AnalysisResponses.Recommendation;
import com.aiskincare.backend.common.enums.AnalysisStatus;
import com.aiskincare.backend.common.enums.RecommendationStatus;
import com.aiskincare.backend.common.error.ErrorCode;
import com.aiskincare.backend.common.util.TimeUtil;
import java.util.List;

/**
 * DB 행 → API 응답 (TRD §10.2·10.4, §11.1 상태 표). 분류는 상태와 무관하게 항상, recommendationStatus·guidance는
 * COMPLETED·WITHHELD일 때만, recommendation은 COMPLETED일 때만, error는 FAILED일 때만, 대기 순번은 QUEUED일 때만 싣는다.
 */
public final class AnalysisPresenter {
  private AnalysisPresenter() {}

  public record QueueInfo(Long queuePosition, Long estimatedWaitSec) {
    public static final QueueInfo NONE = new QueueInfo(null, null);
  }

  public static Disease disease(DiseaseResult disease) {
    return new Disease(
        disease.label().label(),
        disease.label(),
        disease.confidence(),
        disease.uncertain(),
        disease.probabilities(),
        disease.modelVersion(),
        disease.inferenceMs());
  }

  private static boolean finished(AnalysisRow analysis) {
    return analysis.status() == AnalysisStatus.COMPLETED
        || analysis.status() == AnalysisStatus.WITHHELD;
  }

  private static RecommendationStatus recommendationStatus(AnalysisRow analysis) {
    return finished(analysis) ? analysis.recommendationStatus() : null;
  }

  public static Recommendation recommendation(AnalysisRow analysis, RecommendationRow row) {
    if (analysis.status() != AnalysisStatus.COMPLETED) return null;
    if (row == null) throw new IllegalStateException("COMPLETED 분석에 추천 행이 없습니다");
    return new Recommendation(
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
        row.modelVersion(),
        row.inferenceMs());
  }

  private static AnalysisError error(AnalysisRow analysis) {
    if (analysis.status() != AnalysisStatus.FAILED) return null;
    ErrorCode stored = ErrorCode.fromName(analysis.errorCode());
    ErrorCode code = stored != null && stored.isAnalysisFailureCode() ? stored : ErrorCode.E4001;
    return new AnalysisError(code.name(), code.message());
  }

  public static AnalysisView view(
      AnalysisRow analysis,
      NormalizedSurvey survey,
      RecommendationRow recommendation,
      QueueInfo queue) {
    boolean queued = analysis.status() == AnalysisStatus.QUEUED;
    return new AnalysisView(
        analysis.id(),
        analysis.status(),
        disease(analysis.disease()),
        recommendationStatus(analysis),
        recommendation(analysis, recommendation),
        finished(analysis) ? analysis.guidance() : List.of(),
        error(analysis),
        queued ? queue.queuePosition() : null,
        queued ? queue.estimatedWaitSec() : null,
        TimeUtil.toIsoUtc(analysis.createdAt()),
        TimeUtil.toIsoUtc(analysis.completedAt()),
        survey);
  }

  public static ListItem listItem(ListRow row) {
    AnalysisRow analysis = row.analysis();
    return new ListItem(
        analysis.id(),
        TimeUtil.toIsoUtc(analysis.createdAt()),
        analysis.status(),
        recommendationStatus(analysis),
        disease(analysis.disease()),
        row.concerns());
  }
}
