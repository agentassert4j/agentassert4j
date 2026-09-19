package io.github.agentassert4j.cli;

import picocli.CommandLine;

/**
 * picocli --version 输出提供者——框架版本经构建期资源过滤从根 POM 填充
 * （POM 为唯一真源），本类不持版本字面量。
 *
 * @author axy-yxa
 * @since 2026-09-19
 */
public final class FrameworkVersionProvider implements CommandLine.IVersionProvider {

    @Override
    public String[] getVersion() {
        return new String[]{"AgentAssert4j " + AgentAssert4jCli.FRAMEWORK_VERSION};
    }
}
