package com.k2fsa.sherpa.onnx;
public class OnlineModelConfig {
  public static Builder builder() { return new Builder(); }
  public static class Builder {
    public Builder setTransducer(Object t) { return this; }
    public Builder setTokens(String t) { return this; }
    public OnlineModelConfig build() { return new OnlineModelConfig(); }
  }
}