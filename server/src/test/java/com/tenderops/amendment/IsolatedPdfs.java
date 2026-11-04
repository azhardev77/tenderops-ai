package com.tenderops.amendment;

/** Backward-compatible delegate: the shared helper lives in com.tenderops. */
final class IsolatedPdfs {

  private IsolatedPdfs() {
  }

  static byte[] tag(byte[] pdf, String tag) {
    return com.tenderops.IsolatedPdfs.tag(pdf, tag);
  }
}
