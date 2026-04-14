package com.k2fsa.sherpa.onnx;
public class OnlineRecognizer {
  public OnlineRecognizer(Object config) {}
  public OnlineStream createStream() { return new OnlineStream(); }
  public boolean isReady(OnlineStream s) { return false; }
  public void decode(OnlineStream s) {}
  public Result getResult(OnlineStream s) { return new Result(); }
  public static class Result { public String getText() { return ""; } }
}