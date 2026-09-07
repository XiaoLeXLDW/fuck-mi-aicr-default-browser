package android.os;
public class Handler {
    private final java.util.concurrent.Executor executor;
    public Handler() { this(Runnable::run); }
    public Handler(java.util.concurrent.Executor executor) { this.executor = executor; }
    public boolean post(Runnable action) { executor.execute(action); return true; }
}
