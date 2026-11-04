package com.tenderops.visual;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Live-verified SAM grammar parsing (TC-VIS-03 parser half). */
class SamOutputParserTest {

  private static final String LIVE_BOX =
      "<|box;x1=118;y1=205;x2=1122;y2=375;w=1240;h=1753|>";
  private static final String LIVE_MASK = "<|mask;x=0;y=0;data=171,1005,QUJD|>";

  @Test
  void parsesLiveBoxAndMaskBehindHeader() {
    List<SamOutputParser.SamBox> boxes =
        SamOutputParser.parse("<0f>0" + LIVE_BOX + LIVE_MASK, 1240, 1753);

    assertThat(boxes).hasSize(1);
    SamOutputParser.SamBox box = boxes.get(0);
    assertThat(box.x0()).isEqualTo(118.0 / 1240.0);
    assertThat(box.y0()).isEqualTo(205.0 / 1753.0);
    assertThat(box.x1()).isEqualTo(1122.0 / 1240.0);
    assertThat(box.y1()).isEqualTo(375.0 / 1753.0);
    assertThat(box.confidence()).isEqualTo(0.90);
    assertThat(box.maskRef()).isEqualTo(LIVE_MASK);
  }

  @Test
  void blankOutputMeansNoDetections() {
    assertThat(SamOutputParser.parse("", 100, 100)).isEmpty();
    assertThat(SamOutputParser.parse("   ", 100, 100)).isEmpty();
  }

  @Test
  void orphanMaskYieldsNoDetections() {
    assertThat(SamOutputParser.parse(LIVE_MASK, 100, 100)).isEmpty();
  }

  @Test
  void multipleBoxesParseWithConfidenceAndClamp() {
    List<SamOutputParser.SamBox> boxes = SamOutputParser.parse(
        "<|box;x1=10;y1=20;x2=110;y2=220;c=0.5|>"
            + "<|box;x1=-50;y1=-50;x2=500;y2=500|>",
        200, 200);

    assertThat(boxes).hasSize(2);
    assertThat(boxes.get(0).confidence()).isEqualTo(0.5);
    assertThat(boxes.get(0).x0()).isEqualTo(0.05);
    assertThat(boxes.get(1).confidence()).isEqualTo(0.90);
    assertThat(boxes.get(1).x0()).isEqualTo(0.0);
    assertThat(boxes.get(1).y0()).isEqualTo(0.0);
    assertThat(boxes.get(1).x1()).isEqualTo(1.0);
    assertThat(boxes.get(1).y1()).isEqualTo(1.0);
  }

  @Test
  void malformedOutputsThrowSamFormatException() {
    assertThatThrownBy(() -> SamOutputParser.parse("No tables found.", 100, 100))
        .isInstanceOf(SamFormatException.class);
    assertThatThrownBy(() -> SamOutputParser.parse("<|blob;x1=0|>", 100, 100))
        .isInstanceOf(SamFormatException.class);
    assertThatThrownBy(
        () -> SamOutputParser.parse("<|box;x1=10;y1=10;x2=0;y2=0|>", 100, 100))
        .isInstanceOf(SamFormatException.class);
    assertThatThrownBy(
        () -> SamOutputParser.parse("<|box;x1=0;y1=0;x2=10;y2=10;c=1.5|>", 100, 100))
        .isInstanceOf(SamFormatException.class);
    assertThatThrownBy(() -> SamOutputParser.parse("<|box;x1=0;y1=0;x2=10|>", 100, 100))
        .isInstanceOf(SamFormatException.class);
    assertThatThrownBy(() -> SamOutputParser.parse("<|mask;x=0;y=0|>", 100, 100))
        .isInstanceOf(SamFormatException.class);
    assertThatThrownBy(() -> SamOutputParser.parse("<|box;x1=1", 100, 100))
        .isInstanceOf(SamFormatException.class);
    assertThatThrownBy(
        () -> SamOutputParser.parse("<|box;x1=0;y1=0;x2=1;y2=1;zzz=1|>", 100, 100))
        .isInstanceOf(SamFormatException.class);
  }
}
