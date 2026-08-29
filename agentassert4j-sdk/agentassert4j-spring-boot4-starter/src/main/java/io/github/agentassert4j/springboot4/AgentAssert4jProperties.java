package io.github.agentassert4j.springboot4;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AgentAssert4j Spring Boot 配置项（前缀 {@code agentassert4j}）。
 *
 * <p>v1 只暴露总开关与数据库位置——录制器内部参数（RingBuffer/批量/flush 间隔）
 * 走代码默认值；配置项是发布后的永久契约，按需最小面开放。</p>
 *
 * @author axy-yxa
 * @since 2026-08-28
 */
@ConfigurationProperties(prefix = "agentassert4j")
public class AgentAssert4jProperties {

    /**
     * 总开关；false 时自动装配整体退出，不创建任何 Bean、不包装 ChatModel。
     */
    private boolean enabled = true;

    /**
     * SQLite 数据库文件路径（v1 唯一存储后端）。
     */
    private String database = "agentassert4j.db";

    /**
     * 应用级默认 skillId：记录未声明且无工具调用时以此身份录制。
     * 单技能应用配置一次即得稳定身份；多技能应用在代码里用 RecordingContext 显式声明。
     */
    private String skillId = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getSkillId() {
        return skillId;
    }

    public void setSkillId(String skillId) {
        this.skillId = skillId;
    }
}
