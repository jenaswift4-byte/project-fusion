package com.k2fsa.sherpa.onnx;
public class OnlineRecognizerConfig {
  public static Builder builder() { return new Builder(); }
  public static class Builder {
    public Builder setModel(Object m) { return this; }
    public Builder setTokens(String t) { return this; }
    public Builder setNumThreads(int n) { return this; }
    public Builder setDebug(boolean d) { return this; }
    public OnlineRecognizerConfig build() { return new OnlineRecognizerConfig(); }
  }
}