package com.k2fsa.sherpa.onnx;
public class SpeakerEmbeddingExtractorConfig {
  public static Builder builder() { return new Builder(); }
  public static class Builder {
    public Builder setModel(String m) { return this; }
    public Builder setNumThreads(int n) { return this; }
    public SpeakerEmbeddingExtractorConfig build() { return new SpeakerEmbeddingExtractorConfig(); }
  }
}