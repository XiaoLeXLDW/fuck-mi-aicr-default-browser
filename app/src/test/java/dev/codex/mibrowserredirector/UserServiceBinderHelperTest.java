package dev.codex.mibrowserredirector;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class UserServiceBinderHelperTest {
    @Test
    public void destroyTransactionMatchesShizukuUserServiceContract() {
        assertEquals(16_777_115, UserServiceBinderHelper.destroyTransactionCode());
        assertEquals(IRedirectorService.Stub.TRANSACTION_destroy,
                UserServiceBinderHelper.destroyTransactionCode());
    }
}
