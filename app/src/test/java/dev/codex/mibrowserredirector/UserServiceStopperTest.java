package dev.codex.mibrowserredirector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class UserServiceStopperTest {
    @Test
    public void disablesRemovesAndWaitsForGracefulDeathInOrder() {
        List<String> calls = new ArrayList<>();
        SequenceEndpoint endpoint = new SequenceEndpoint(calls, true, true, false);

        UserServiceStopper.Result result = UserServiceStopper.stop(
                endpoint,
                () -> calls.add("remove"),
                4,
                0L,
                ignored -> calls.add("wait")
        );

        assertTrue(result.stopped);
        assertEquals(
                Arrays.asList("disable", "remove", "alive", "wait", "alive", "wait", "alive"),
                calls
        );
    }

    @Test
    public void neverClaimsSuccessWhileBinderRemainsAlive() {
        List<String> calls = new ArrayList<>();
        SequenceEndpoint endpoint = new SequenceEndpoint(calls, true, true, true, true);

        UserServiceStopper.Result result = UserServiceStopper.stop(
                endpoint,
                () -> calls.add("remove"),
                3,
                0L,
                ignored -> calls.add("wait")
        );

        assertFalse(result.stopped);
        assertTrue(result.detail.contains("仍存活"));
        assertTrue(calls.contains("destroy"));
    }

    @Test
    public void deadBinderIsSuccessEvenWhenDisableCannotReply() {
        UserServiceStopper.Endpoint endpoint = new UserServiceStopper.Endpoint() {
            @Override
            public String disable() throws Exception {
                throw new Exception("binder closed");
            }

            @Override
            public void destroy() throws Exception {
                throw new AssertionError("dead Binder must not need forced destroy");
            }

            @Override
            public boolean isAlive() {
                return false;
            }
        };

        UserServiceStopper.Result result = UserServiceStopper.stop(
                endpoint,
                () -> { },
                1,
                0L,
                ignored -> { }
        );

        assertTrue(result.stopped);
        assertTrue(result.detail.contains("已确认"));
    }

    private static final class SequenceEndpoint implements UserServiceStopper.Endpoint {
        private final List<String> calls;
        private final boolean[] states;
        private int stateIndex;

        SequenceEndpoint(List<String> calls, boolean... states) {
            this.calls = calls;
            this.states = states;
        }

        @Override
        public String disable() {
            calls.add("disable");
            return "已停用";
        }

        @Override
        public void destroy() {
            calls.add("destroy");
        }

        @Override
        public boolean isAlive() {
            calls.add("alive");
            int index = Math.min(stateIndex, states.length - 1);
            stateIndex++;
            return states[index];
        }
    }
}
