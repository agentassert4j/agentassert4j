package io.github.agentassert4j.model;

/**
 * 检查点 — 记录一次回归测试的结果快照。
 */
public class Checkpoint {

    private String id;
    private String name;
    private long timestamp;
    private int passed;
    private int failed;
    private int diff;
    private String fullReport;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public int getPassed() { return passed; }
    public void setPassed(int passed) { this.passed = passed; }

    public int getFailed() { return failed; }
    public void setFailed(int failed) { this.failed = failed; }

    public int getDiff() { return diff; }
    public void setDiff(int diff) { this.diff = diff; }

    public String getFullReport() { return fullReport; }
    public void setFullReport(String fullReport) { this.fullReport = fullReport; }
}
