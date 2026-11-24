package com.aiskincare.backend.knowledge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class Knowledge {
  private Knowledge() {}

  public record Query(
      @NotBlank @Size(min = 2, max = 160) String query,
      @Pattern(regexp = "guide|research|label") String kind) {
    public Query {
      query = query == null ? null : query.strip();
      kind = kind == null ? "guide" : kind;
    }
  }

  public record Source(
      String id, String title, String text, String publisher, String url, String date) {}

  public record Answer(
      String kind,
      String asOf,
      String origin,
      String answer,
      String answerSourceId,
      List<Source> sources) {}
}
