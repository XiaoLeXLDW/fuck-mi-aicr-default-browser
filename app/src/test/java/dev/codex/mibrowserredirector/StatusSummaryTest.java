package dev.codex.mibrowserredirector;

import org.junit.Test;
import static org.junit.Assert.*;

public class StatusSummaryTest {
    @Test public void runningSnapshotFoldsMetadataButKeepsAppliedConfiguration() {
        String state = "服务版本：v0.3.4（协议 200）\n服务代：30005\n控制器：运行中"
                + "\n模式：观察（始终放行）\n目标：org.mozilla.firefox"
                + "\n系统接口：ActivityTaskManager\nUserService UID/PID：2000/1234";
        assertEquals("控制器：运行中\n模式：观察（始终放行）\n目标：org.mozilla.firefox",
                MainActivity.summarizeServiceState(state));
    }

    @Test public void errorsAndUnknownStateLinesAreNeverFolded() {
        String state = "停止未确认：系统调用仍未返回，暂不能重新开启"
                + "\n错误：权限已撤销\n新增状态：等待恢复";
        assertEquals(state, MainActivity.summarizeServiceState(state));
    }

    @Test public void missingStateNeverClaimsToBeRunningOrStopped() {
        assertTrue(MainActivity.summarizeServiceState(null).contains("状态未知"));
        assertTrue(MainActivity.summarizeServiceState("").contains("状态未知"));
        assertTrue(MainActivity.summarizeServiceState("服务代：30005").contains("状态未知"));
    }
}
