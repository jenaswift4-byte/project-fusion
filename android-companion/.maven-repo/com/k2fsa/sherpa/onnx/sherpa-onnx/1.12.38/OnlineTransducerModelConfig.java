package com.k2fsa.sherpa.onnx;
public class OnlineTransducerModelConfig {
  public static Builder builder() { return new Builder(); }
  public static class Builder {
    public Builder setEncoder(String e) { return this; }
    public Builder setDecoder(String d) { return this; }
    public Builder setJoiner(String j) { return this; }
    public OnlineTransducerModelConfig build() { return new OnlineTransducerModelConfig(); }
  }
}