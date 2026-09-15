package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.BaselineStep;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.InvocationProfile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 基线侧投影组件 — 全部「基线对照」路径的基线侧步骤单源工厂。
 *
 * <p>判定基线的统一不变式：候选侧（当前证据）永远现场重提；基线侧 = 批准真相的
 * 定格投影，按路径三源取用——链路径 = 上一条真实链的记录（两侧同为记录，对称
 * 现场重提）；验收包 = 导出时刻的定格提取；CI 对照 = 画像活跃指纹（establish/
 * accept 时刻的定格提取）。三个工厂方法只做投影，产物统一喂对齐器：链路径进
 * {@link TaskAligner#align}，CI/包路径进链末判定入口
 * {@link TaskAligner#alignLatestPerInvocation}。</p>
 *
 * @author axy-yxa
 * @since 2026-09-14
 */
public final class BaselineSides {

    private BaselineSides() {
    }

    /**
     * 验收包步骤投影：按完整 invocationKey 分组并从键回填声明标签
     * （包步骤无标签字段，标签是分组口径的必需品）。
     */
    public static Map<String, List<BaselineStep>> fromPackSteps(List<BaselineStep> packSteps) {
        Map<String, List<BaselineStep>> baselineSteps = new LinkedHashMap<>();
        for (BaselineStep step : packSteps) {
            step.setInvocationId(TaskAligner.declaredLabelOfKey(step.getInvocationKey()));
            baselineSteps.computeIfAbsent(step.getInvocationKey(), k -> new ArrayList<>()).add(step);
        }
        return baselineSteps;
    }

    /**
     * 画像指纹投影（CI 基线对照）：每条传入记录产出一份基线步骤，指纹与版本取自该
     * 记录调用点画像的活跃定格值。链末判定路径喂裁剪后的链末记录集（每调用点一份
     * 步骤）；步骤身份（键与标签）镜像记录自身——保证基线组键与新链分组键逐字相等，
     * 对齐只可能 MATCHED（CI 面缺步骤/新增步骤结构性不可能）。
     *
     * <p>解析不到画像或画像无活跃指纹即抛 IllegalStateException——CI 路径的未建档
     * 守卫先于此入口，到达即为数据违约，宁可响亮失败不做静默缺步（缺步会伪装成
     * 行为差异）。</p>
     *
     * @param newChainRecords 新链记录（候选侧同一批；只取身份，指纹不用）
     * @param profileResolver 调用点键 → 画像（null = 无画像）
     */
    public static Map<String, List<BaselineStep>> fromProfiles(List<InteractionRecord> newChainRecords, Function<String, InvocationProfile> profileResolver) {
        Map<String, List<BaselineStep>> baselineSteps = new LinkedHashMap<>();
        for (InteractionRecord record : newChainRecords) {
            String key = record.getInvocationKey();
            if (key == null || key.isEmpty()) {
                continue;
            }
            InvocationProfile profile = profileResolver.apply(key);
            if (profile == null) {
                throw new IllegalStateException("No baseline profile for invocation " + key + "; establish the baseline before judging in --ci mode.");
            }
            if (profile.getFingerprint() == null) {
                throw new IllegalStateException("Baseline profile for invocation " + key + " holds no active fingerprint; re-establish the baseline.");
            }
            BaselineStep step = new BaselineStep();
            step.setInvocationKey(key);
            step.setInvocationId(record.getInvocationId());
            step.setFingerprint(profile.getFingerprint());
            step.setVersionTag(profile.getVersionTag());
            baselineSteps.computeIfAbsent(key, k -> new ArrayList<>()).add(step);
        }
        return baselineSteps;
    }
}
