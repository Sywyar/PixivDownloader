package acceptance.contract;
public final class Probe {
    private Probe() {}
    public record Request(String mode, int depth, byte[] data) {}
    public record Reply(String value, byte[] data) {}
    public interface Forward { Reply call(Request request); }
    public interface Backward { Reply call(Request request); }
}
