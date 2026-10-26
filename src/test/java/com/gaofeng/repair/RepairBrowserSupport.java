package com.gaofeng.repair;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
/** Configurable local Chromium harness; no fixed machine drive or browser cache directory. */
final class RepairBrowserSupport {
    private RepairBrowserSupport(){}
    static void run(String script,Path log,String... arguments)throws Exception{
        String node=System.getProperty("repair.node",System.getenv().getOrDefault("REPAIR_NODE","node"));
        String module=System.getProperty("repair.playwright.module",System.getenv().getOrDefault("REPAIR_PLAYWRIGHT_MODULE",""));
        if(module.isBlank())for(Path candidate:List.of(Path.of("target/repair-browser-tools/node_modules/playwright")))if(Files.isDirectory(candidate)){module=candidate.toAbsolutePath().normalize().toString();break;}
        if(module.isBlank())throw new IllegalStateException("Browser tests need Playwright; set REPAIR_PLAYWRIGHT_MODULE or install it under target/repair-browser-tools");
        Path output=log.toAbsolutePath().normalize();if(!output.startsWith(Path.of("target").toAbsolutePath().normalize()))throw new IllegalArgumentException("Test output must stay inside target");Files.createDirectories(output.getParent());
        List<String> command=new ArrayList<>(List.of(node,Path.of("scripts",script).toString()));command.addAll(Arrays.asList(arguments));ProcessBuilder process=new ProcessBuilder(command);process.environment().put("REPAIR_PLAYWRIGHT_MODULE",module);process.redirectErrorStream(true);process.redirectOutput(output.toFile());
        Process running=process.start();boolean finished=running.waitFor(180,TimeUnit.SECONDS);if(!finished)running.destroyForcibly();assertThat(finished).describedAs("Browser timeout; see "+log).isTrue();assertThat(running.exitValue()).describedAs("See "+log).isZero();
    }
}
