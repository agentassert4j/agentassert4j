package io.github.agentassert4j.result;

import java.util.ArrayList;
import java.util.List;

/**
 * 模板漂移检测报告 — 检测器对全库画像一次只读巡检的产出，不携带任何判定结论。
 *
 * <p>漂移点只覆盖存在画像对照关系的两种形态：同键漂移（画像键下最新记录的模板哈希
 * 与画像不一致，含画像未携带模板哈希的情况）与标签裂键（未建档新键的声明标签与既有
 * 画像标签相同）。无画像对照的全新键不入报告，由建档路径与巡检视图承接。</p>
 *
 * @author axy-yxa
 * @since 2026-09-03
 */
public class DriftReport {

    private final List<DriftPoint> sameKeyDrifts = new ArrayList<>();
    private final List<DriftPoint> labelSplits = new ArrayList<>();
    private final List<String> downstreamKeys = new ArrayList<>();
    private final List<String> zeroTemplateKeys = new ArrayList<>();
    private int skippedQueries;

    /**
     * 是否存在任何需要处置的漂移点（下游波及键不构成漂移本身）
     */
    public boolean hasDrift() {
        return !sameKeyDrifts.isEmpty() || !labelSplits.isEmpty();
    }

    public List<DriftPoint> getSameKeyDrifts() {
        return sameKeyDrifts;
    }

    public List<DriftPoint> getLabelSplits() {
        return labelSplits;
    }

    /**
     * 漂移键经依赖图下游扩散波及的调用点键（不含漂移键自身），升序
     */
    public List<String> getDownstreamKeys() {
        return downstreamKeys;
    }

    /**
     * 因无可比对模板身份而排除出检测的画像键（零模板点：无模板身份即无漂移语义）
     */
    public List<String> getZeroTemplateKeys() {
        return zeroTemplateKeys;
    }

    /**
     * 因无可比对模板身份而跳过检测的画像数
     */
    public int getZeroTemplateProfiles() {
        return zeroTemplateKeys.size();
    }

    /**
     * 单键查询失败被安全跳过的次数——退化可见，不中断巡检
     */
    public int getSkippedQueries() {
        return skippedQueries;
    }

    public void setSkippedQueries(int skippedQueries) {
        this.skippedQueries = skippedQueries;
    }

    /**
     * 单个漂移点：键、声明标签与两侧模板哈希投影。裂键点无画像侧，profileTemplateHash 为 null
     */
    public static class DriftPoint {
        private String invocationKey;
        private String label;
        private String profileTemplateHash;
        private String latestTemplateHash;

        public String getInvocationKey() {
            return invocationKey;
        }

        public void setInvocationKey(String invocationKey) {
            this.invocationKey = invocationKey;
        }

        /**
         * 声明标签（裂键点必有；同键漂移点取画像标签，未声明为 null）
         */
        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getProfileTemplateHash() {
            return profileTemplateHash;
        }

        public void setProfileTemplateHash(String profileTemplateHash) {
            this.profileTemplateHash = profileTemplateHash;
        }

        public String getLatestTemplateHash() {
            return latestTemplateHash;
        }

        public void setLatestTemplateHash(String latestTemplateHash) {
            this.latestTemplateHash = latestTemplateHash;
        }
    }
}
