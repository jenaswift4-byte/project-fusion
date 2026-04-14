package com.k2fsa.sherpa.onnx;
import java.util.Map;
public class SpeakerEmbeddingManager {
  public SpeakerEmbeddingManager(int numThreads) {}
  public int addSpeaker(String name, float[] embedding) { return 0; }
  public int getSpeakerId(String name) { return 0; }
  public String getSpeakerName(int id) { return ""; }
  public int getNumSpeakers() { return 0; }
  public void dump(String path) {}
  public void load(String path) {}
}